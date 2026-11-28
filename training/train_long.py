"""Bounded, checkpointed CUDA training using compact disk-backed token caches."""
import argparse
import ctypes
import hashlib
import json
import math
import os
import random
import struct
import time
from datetime import datetime,timezone,timedelta
from pathlib import Path
import numpy as np
import torch
from peft import PeftModel
from transformers import AutoTokenizer
from train import ROOT,load_model

SEED=20261114
INDEX_DTYPE=np.dtype([("offset","<u8"),("length","<u4"),("prefix","<u4")])


def atomic_json(path,value):
    temp=path.with_suffix(path.suffix+".tmp")
    temp.write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")
    os.replace(temp,path)


def local_path(relative):
    path=(ROOT/relative).resolve()
    if not path.is_relative_to(ROOT):raise ValueError("Training paths must remain inside training/")
    return path


def prepare_cache(source,tokenizer,max_length):
    with source.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
    token_hash=hashlib.sha256((tokenizer.chat_template+json.dumps(tokenizer.get_vocab(),sort_keys=True)).encode()).hexdigest()[:10]
    dest=ROOT/"cache/edge_v5"/f"{source.stem}-{digest[:12]}-{token_hash}-{max_length}"
    dest.mkdir(parents=True,exist_ok=True)
    manifest=dest/"manifest.json"
    if manifest.exists():return Packed(dest)
    count=offset=longest=0
    rejected=[]
    with source.open(encoding='utf-8') as rows,(dest/'tokens.bin').open('wb') as tokens,(dest/'index.bin').open('wb') as index,(dest/'meta.jsonl').open('w',encoding='utf-8') as meta:
        for line in rows:
            row=json.loads(line)
            for end,message in enumerate(row['messages']):
                if message['role']!='assistant':continue
                prompt=tokenizer.apply_chat_template(row['messages'][:end],tokenize=False,add_generation_prompt=True)
                full=tokenizer.apply_chat_template(row['messages'][:end+1],tokenize=False,add_generation_prompt=False)
                if not full.startswith(prompt):raise ValueError('Inconsistent chat-template prefix')
                prefix=tokenizer.encode(prompt,add_special_tokens=False)
                answer=tokenizer.encode(full[len(prompt):],add_special_tokens=False)
                ids=prefix+answer
                if len(ids)>max_length:
                    rejected.append({'id':row['id'],'tokens':len(ids)})
                    continue
                if not answer:raise ValueError('Empty supervised target')
                np.asarray(ids,dtype='<i4').tofile(tokens)
                index.write(struct.pack('<QII',offset,len(ids),len(prefix)))
                value=json.loads(message['content'])
                meta.write(json.dumps({'id':row['id'],'family':row['family'],'kind':value['kind'],'name':value.get('name')})+'\n')
                count+=1;offset+=len(ids);longest=max(longest,len(ids))
            if count and count%2000<4:print(json.dumps({'cache':source.name,'targets_prepared':count}),flush=True)
    if rejected:
        atomic_json(dest/'rejected.json',rejected)
        raise ValueError(f'{len(rejected)} targets exceed {max_length}; no truncation allowed. See {dest}/rejected.json')
    atomic_json(manifest,{'source':str(source),'sha256':digest,'targets':count,'tokens':offset,'max_length':longest,'tokenizer_template_hash':token_hash})
    return Packed(dest)


class Packed:
    def __init__(self,path):
        self.path=path
        self.info=json.loads((path/'manifest.json').read_text())
        self.index=np.memmap(path/'index.bin',dtype=INDEX_DTYPE,mode='r')
        self.tokens=np.memmap(path/'tokens.bin',dtype='<i4',mode='r')
        if len(self.index)!=self.info['targets'] or len(self.tokens)!=self.info['tokens']:raise ValueError('Incomplete token cache')
        if np.any(self.index['prefix']>=self.index['length']) or np.any(self.index['offset']+self.index['length']>len(self.tokens)):
            raise ValueError('Invalid packed token boundaries')
    def __len__(self):return len(self.index)
    def batch(self,index,device='cuda'):
        record=self.index[index]
        start,length,prefix=int(record['offset']),int(record['length']),int(record['prefix'])
        ids=torch.tensor(np.asarray(self.tokens[start:start+length],dtype=np.int64),device=device).unsqueeze(0)
        labels=ids.clone();labels[:,:prefix]=-100
        return {'input_ids':ids,'labels':labels},length-prefix
    def validation_indices(self,limit):
        groups={}
        with (self.path/'meta.jsonl').open(encoding='utf-8') as f:
            for i,line in enumerate(f):
                row=json.loads(line)
                groups.setdefault((row['family'],row['kind']),[]).append(i)
        rng=random.Random(SEED)
        keys=list(groups);rng.shuffle(keys)
        result=[rng.choice(groups[k]) for k in keys[:limit]]
        if len(result)<limit:
            rest=list(set(range(len(self)))-set(result));rng.shuffle(rest)
            result+=rest[:limit-len(result)]
        return result


class Sampler:
    def __init__(self,size,epoch=0,cursor=0):
        self.size=size;self.epoch=epoch;self.cursor=cursor
        self.order=np.random.default_rng(SEED+epoch).permutation(size)
    def next(self):
        if self.cursor==self.size:
            self.epoch+=1;self.cursor=0
            self.order=np.random.default_rng(SEED+self.epoch).permutation(self.size)
        value=int(self.order[self.cursor]);self.cursor+=1
        return value


@torch.no_grad()
def evaluate_loss(model,data,indices):
    model.eval();total=tokens=0
    for index in indices:
        batch,count=data.batch(index)
        loss=model(**batch).loss
        if not torch.isfinite(loss):raise FloatingPointError('Non-finite validation loss')
        total+=float(loss)*count;tokens+=count
    model.train()
    return total/tokens


def checkpoint(model,optimizer,scaler,tokenizer,output,step,sampler,elapsed,train_hash,validation_hash,loss,signature):
    dest=output/f'checkpoint-{step:06d}'
    dest.mkdir(exist_ok=True)
    model.save_pretrained(dest);tokenizer.save_pretrained(dest)
    torch.save({'optimizer':optimizer.state_dict(),'scaler':scaler.state_dict(),'torch_rng':torch.get_rng_state(),
                'cuda_rng':torch.cuda.get_rng_state()},dest/'training_state.pt')
    meta={'step':step,'epoch':sampler.epoch,'cursor':sampler.cursor,'elapsed_seconds':elapsed,
          'train_hash':train_hash,'validation_hash':validation_hash,'run_signature':signature,'validation_loss':loss,'adapter':str(dest.relative_to(ROOT))}
    atomic_json(dest/'checkpoint.json',meta)
    atomic_json(output/'latest.json',meta)
    return meta


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--model',default=os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")))
    p.add_argument('--init-adapter',default='runs/fily-domain-v4')
    p.add_argument('--output',default='runs/fily-edge-v5-5h')
    p.add_argument('--train-file',default='data/edge_v5/train.jsonl')
    p.add_argument('--validation-file',default='data/edge_v5/validation.jsonl')
    p.add_argument('--seconds',type=float,default=18000)
    p.add_argument('--max-steps',type=int,default=0)
    p.add_argument('--accumulation',type=int,default=4)
    p.add_argument('--max-length',type=int,default=1536)
    p.add_argument('--validation-samples',type=int,default=256)
    p.add_argument('--interval',type=float,default=900)
    p.add_argument('--learning-rate',type=float,default=3e-5)
    p.add_argument('--resume',action='store_true')
    p.add_argument('--smoke-longest',action='store_true')
    args=p.parse_args()
    if min(args.seconds,args.accumulation,args.interval,args.validation_samples)<=0:raise ValueError('Budgets and batch sizes must be positive')
    output=local_path(args.output);output.mkdir(parents=True,exist_ok=True)
    if (output/'report.json').exists():raise FileExistsError('Finished runs are never overwritten')
    observed_elapsed=0.0
    if args.resume:
        for filename in ['progress.json','failure.json']:
            if (output/filename).exists():
                observed_elapsed=max(observed_elapsed,json.loads((output/filename).read_text()).get('elapsed_seconds',0.0))
    atomic_json(output/'progress.json',{'status':'preparing_token_cache','arguments':vars(args)})
    torch.set_num_threads(4);torch.manual_seed(SEED);random.seed(SEED)
    tokenizer=AutoTokenizer.from_pretrained(args.model,local_files_only=True)
    train=prepare_cache(local_path(args.train_file),tokenizer,args.max_length)
    val=prepare_cache(local_path(args.validation_file),tokenizer,args.max_length)
    indices=val.validation_indices(min(args.validation_samples,len(val)))
    signature=hashlib.sha256(json.dumps({'train':train.info,'validation':val.info,'max_length':args.max_length,
        'accumulation':args.accumulation,'learning_rate':args.learning_rate,'longest':args.smoke_longest},sort_keys=True).encode()).hexdigest()
    atomic_json(output/'tokenization.json',{'train':train.info,'validation':val.info,'validation_indices':indices})
    resume=None
    if args.resume and (output/'latest.json').exists():
        resume=json.loads((output/'latest.json').read_text())
        if (resume['train_hash'],resume['validation_hash'])!=(train.info['sha256'],val.info['sha256']):raise ValueError('Resume datasets changed')
        if resume.get('run_signature')!=signature:raise ValueError('Resume tokenizer or training configuration changed')
    elif (output/'latest.json').exists():raise FileExistsError('Use --resume to continue this existing run')
    atomic_json(output/'progress.json',{'status':'loading_cuda_model','train_targets':len(train),'validation_targets':len(val)})
    model,dtype=load_model(args.model)
    adapter=local_path(resume['adapter'] if resume else args.init_adapter)
    model=PeftModel.from_pretrained(model,adapter,is_trainable=True)
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant':False});model.enable_input_require_grads()
    optimizer=torch.optim.AdamW((x for x in model.parameters() if x.requires_grad),lr=args.learning_rate)
    scaler=torch.amp.GradScaler('cuda',enabled=dtype==torch.float16)
    sampler=Sampler(len(train),resume['epoch'] if resume else 0,resume['cursor'] if resume else 0)
    if args.smoke_longest:sampler.order=np.argsort(train.index['length'])[::-1]
    step=resume['step'] if resume else 0
    prior_elapsed=max(resume['elapsed_seconds'],observed_elapsed) if resume else 0.0
    if resume:
        # Only the job's own local checkpoint is accepted; no downloaded pickle state.
        state=torch.load(adapter/'training_state.pt',map_location='cpu',weights_only=True)
        optimizer.load_state_dict(state['optimizer']);scaler.load_state_dict(state['scaler'])
        for values in optimizer.state.values():
            for key,value in values.items():
                if isinstance(value,torch.Tensor) and key!='step':values[key]=value.to('cuda')
        torch.set_rng_state(state['torch_rng']);torch.cuda.set_rng_state(state['cuda_rng'])
    # The run's baseline is the loss before its first step; a resume measures its checkpoint, not that.
    baseline_file=output/'baseline.json'
    resumed_loss=evaluate_loss(model,val,indices)
    if baseline_file.exists():baseline=json.loads(baseline_file.read_text())['validation_loss']
    else:baseline=resumed_loss;atomic_json(baseline_file,{'validation_loss':baseline,'step':step})
    best=json.loads((output/'best.json').read_text()) if (output/'best.json').exists() else None
    if best is None:
        best=checkpoint(model,optimizer,scaler,tokenizer,output,step,sampler,prior_elapsed,train.info['sha256'],val.info['sha256'],resumed_loss,signature)
        atomic_json(output/'best.json',best)
    atomic_json(output/'arguments.json',vars(args))
    started=time.monotonic();next_check=prior_elapsed+args.interval
    started_utc=datetime.now(timezone.utc)
    torch.cuda.reset_peak_memory_stats();model.train()
    metrics=output/'metrics.jsonl'
    if resume and metrics.exists():
        # Steps after the checkpoint were lost with the crash and will run again; keep one entry per step.
        kept=[line for line in metrics.read_text(encoding='utf-8').splitlines() if line.strip() and json.loads(line).get('step',0)<=step]
        metrics.write_text(''.join(line+'\n' for line in kept),encoding='utf-8')
    (output/'failure.json').unlink(missing_ok=True)
    log=metrics.open('a',encoding='utf-8')
    reason='time_budget'
    try:
        if os.name=='nt':ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
        while prior_elapsed+time.monotonic()-started<args.seconds:
            if args.max_steps and step>=args.max_steps:
                reason='max_steps';break
            if (output/'CANCEL').exists():reason='cancelled';break
            optimizer.zero_grad(set_to_none=True);losses=[]
            elapsed=prior_elapsed+time.monotonic()-started
            warm=min(1.0,(step+1)/100)
            decay=0.1+0.9*0.5*(1+math.cos(math.pi*min(1,elapsed/args.seconds)))
            for group in optimizer.param_groups:group['lr']=args.learning_rate*warm*decay
            for _ in range(args.accumulation):
                batch,_=train.batch(sampler.next())
                with torch.autocast('cuda',dtype=dtype):
                    raw=model(**batch).loss
                    if not torch.isfinite(raw):raise FloatingPointError('Non-finite training loss')
                    loss=raw/args.accumulation
                scaler.scale(loss).backward();losses.append(float(raw.detach()))
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_((x for x in model.parameters() if x.requires_grad),1.0,error_if_nonfinite=True)
            scaler.step(optimizer);scaler.update();step+=1
            elapsed=prior_elapsed+time.monotonic()-started
            entry={'status':'training','step':step,'epoch':sampler.epoch,'cursor':sampler.cursor,'loss':sum(losses)/len(losses),
                   'elapsed_seconds':elapsed,'remaining_seconds':max(0,args.seconds-elapsed),
                   'eta_utc':(datetime.now(timezone.utc)+timedelta(seconds=max(0,args.seconds-elapsed))).isoformat(),
                   'peak_allocated_MiB':torch.cuda.max_memory_allocated()/2**20,'gpu':torch.cuda.get_device_name(0)}
            log.write(json.dumps(entry)+'\n');log.flush()
            if step%10==0 or step==1:
                atomic_json(output/'progress.json',entry);print(json.dumps(entry),flush=True)
            if elapsed>=next_check:
                atomic_json(output/'progress.json',{**entry,'status':'validating'})
                loss=evaluate_loss(model,val,indices)
                meta=checkpoint(model,optimizer,scaler,tokenizer,output,step,sampler,prior_elapsed+time.monotonic()-started,train.info['sha256'],val.info['sha256'],loss,signature)
                if loss<best['validation_loss']:best=meta;atomic_json(output/'best.json',best)
                print(json.dumps({'checkpoint_step':step,'validation_loss':loss,'best_step':best['step']}),flush=True)
                next_check=prior_elapsed+time.monotonic()-started+args.interval
        final_loss=evaluate_loss(model,val,indices)
        elapsed=prior_elapsed+time.monotonic()-started
        meta=checkpoint(model,optimizer,scaler,tokenizer,output,step,sampler,elapsed,train.info['sha256'],val.info['sha256'],final_loss,signature)
        if final_loss<best['validation_loss']:best=meta;atomic_json(output/'best.json',best)
        model.save_pretrained(output/'final');tokenizer.save_pretrained(output/'final')
        report={'status':'completed' if reason!='cancelled' else 'cancelled','reason':reason,'arguments':vars(args),'steps':step,
                'started_utc':started_utc.isoformat(),'elapsed_seconds':elapsed,'train_targets':len(train),'validation_targets':len(val),
                'validation_samples':len(indices),'baseline_validation_loss':baseline,'final_validation_loss':final_loss,
                'selected_adapter':best['adapter'],'selected_validation_loss':best['validation_loss'],
                'peak_allocated_MiB':torch.cuda.max_memory_allocated()/2**20,'gpu':torch.cuda.get_device_name(0),
                'limitations':'Synthetic supplied-context curriculum. Time budget includes periodic validation/checkpointing, excludes cache preparation. Teacher-forced loss is not production accuracy.'}
        atomic_json(output/'report.json',report);atomic_json(output/'progress.json',report)
        print(json.dumps(report),flush=True)
    except BaseException as exc:
        atomic_json(output/'failure.json',{'status':'failed','error':type(exc).__name__+': '+str(exc),'step':step,
                    'elapsed_seconds':prior_elapsed+time.monotonic()-started,'resume_available':(output/'latest.json').exists()})
        raise
    finally:
        log.close()
        if os.name=='nt':ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)


if __name__=='__main__':main()
