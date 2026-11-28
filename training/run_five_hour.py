"""Own one five-hour local training job and its automatic post-training pipeline."""
import argparse
import contextlib
import ctypes
import json
import os
import subprocess
import sys
import traceback
from datetime import datetime,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parent


def write(path,value):
    temp=path.with_suffix('.tmp')
    temp.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
    os.replace(temp,path)


@contextlib.contextmanager
def job_lock(path):
    with path.open('a+b') as f:
        if f.tell()==0:f.write(b'0');f.flush()
        f.seek(0)
        if os.name=='nt':
            import msvcrt
            msvcrt.locking(f.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
        try:yield
        finally:
            f.seek(0)
            if os.name=='nt':msvcrt.locking(f.fileno(),msvcrt.LK_UNLCK,1)
            else:fcntl.flock(f,fcntl.LOCK_UN)


def run(script,*args):
    command=[sys.executable,str(ROOT/script),*map(str,args)]
    print(json.dumps({'command':command,'utc':datetime.now(timezone.utc).isoformat()}),flush=True)
    subprocess.run(command,cwd=ROOT.parent,check=True,creationflags=0x08000000 if os.name=='nt' else 0)


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--output',default='runs/fily-edge-v5-5h')
    p.add_argument('--seconds',type=float,default=18000)
    p.add_argument('--resume',action='store_true')
    # edge_v6 (build_screen_control.py) continues from the deployed v5 adapter.
    p.add_argument('--data',default='edge_v5')
    p.add_argument('--init-adapter',default='runs/fily-domain-v4')
    args=p.parse_args()
    output=(ROOT/args.output).resolve()
    if not output.is_relative_to(ROOT):raise ValueError('Job must remain inside training/')
    output.mkdir(parents=True,exist_ok=True)
    pipeline=output/'pipeline.json'
    with job_lock(output/'worker.lock'):
        if pipeline.exists() and json.loads(pipeline.read_text()).get('status')=='completed':raise FileExistsError('Completed jobs are preserved')
        state={'status':'training','pid':os.getpid(),'budget_seconds':args.seconds,'started_utc':datetime.now(timezone.utc).isoformat(),
               'training_output':str(output.relative_to(ROOT)),'application_source_changed':False}
        write(pipeline,state)
        try:
            if os.name=='nt':ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
            if not (output/'report.json').exists():
                extra=['--resume'] if args.resume else []
                run('train_long.py','--output',args.output,'--seconds',args.seconds,'--init-adapter',args.init_adapter,
                    '--train-file',f'data/{args.data}/train.jsonl','--validation-file',f'data/{args.data}/validation.jsonl',*extra)
            training=json.loads((output/'report.json').read_text())
            if training['status']!='completed':
                write(pipeline,{**state,'status':training['status'],'training':training});return
            selected=training['selected_adapter']
            export_relative='exports/'+output.name
            export=ROOT/export_relative
            state.update({'status':'exporting','selected_adapter':selected,'export':export_relative})
            write(pipeline,state)
            if not (export/'manifest.json').exists():
                run('export_quantized.py','--adapter',selected,'--output',export_relative)
            elif json.loads((export/'manifest.json').read_text())['adapter']!=selected:
                raise ValueError('Existing export belongs to a different selected checkpoint')
            state['status']='calibrating';write(pipeline,state)
            if not (export/'calibration-manifest.json').exists():
                run('calibrate_quantization.py','--export',export_relative,'--train-file',f'data/{args.data}/train.jsonl')
            state['status']='evaluating';write(pipeline,state)
            evaluations=[]
            for label,model,suite,limit in [
                ('baseline-edge','exports/fily-domain-v4/Fily-EzFiles-350M-Q8_0.gguf','edge_v5/test',128),
                ('new-edge-Q8',export_relative+'/Fily-EzFiles-350M-Q8_0.gguf','edge_v5/test',128),
                ('new-edge-Q4',export_relative+'/Fily-EzFiles-350M-Q4_K_M-I.gguf','edge_v5/test',128),
                ('new-capabilities-Q8',export_relative+'/Fily-EzFiles-350M-Q8_0.gguf','grounded_domain_test',128),
                ('new-capabilities-Q4',export_relative+'/Fily-EzFiles-350M-Q4_K_M-I.gguf','grounded_domain_test',128),
            ] + ([
                ('new-screen-Q8',export_relative+'/Fily-EzFiles-350M-Q8_0.gguf',f'{args.data}/screen_test',70),
                ('new-screen-Q4',export_relative+'/Fily-EzFiles-350M-Q4_K_M-I.gguf',f'{args.data}/screen_test',70),
            ] if (ROOT/'data'/args.data/'screen_test.jsonl').exists() else []):
                report=export/f'evaluation-{label}.json'
                if not report.exists():
                    run('evaluate_gguf.py','--model',model,'--suite',suite,'--limit',limit,'--output',str(report.relative_to(ROOT)))
                data=json.loads(report.read_text())
                evaluations.append({k:v for k,v in data.items() if k!='results'})
            state['status']='benchmarking';write(pipeline,state)
            run('bench_quantized.py','--export',export_relative)
            final={**state,'status':'completed','completed_utc':datetime.now(timezone.utc).isoformat(),
                   'training':training,'evaluations':evaluations,
                   'note':'Synthetic conditional tests. Inspect semantic failures; no app execution, voice or phone acceptance.'}
            write(output/'completed.json',final);write(pipeline,final)
            lines=['# Five-hour Fily run completed','',
                f"Training steps: {training['steps']}; elapsed active job seconds: {training['elapsed_seconds']:.1f}.",
                f"Selected adapter by validation loss: {selected}.",f"Export: {export_relative}.",'',
                '| Suite/model | Turns | Contract valid | Exact tool outputs | Strict matches |',
                '|---|---:|---:|---:|---:|']
            for item in evaluations:
                lines.append(f"| {item['suite']} / {Path(item['model']).name} | {item['examples']} | {item['contract_valid']} | {item['tool_exact_matches']}/{item['tool_examples']} | {item['exact_matches']} |")
            lines += ['',final['note'],'','Inspect raw failures before choosing a default model. Prior exports remain intact.']
            (output/'RUN_REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
            print(json.dumps({'status':'completed','report':str(output/'completed.json')}),flush=True)
        except BaseException as exc:
            write(pipeline,{**state,'status':'failed','error':type(exc).__name__+': '+str(exc),'traceback':traceback.format_exc(),
                            'resume_command':f'training/.venv/Scripts/python.exe training/run_five_hour.py --output {args.output} --seconds {args.seconds} --resume'})
            raise
        finally:
            if os.name=='nt':ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)


if __name__=='__main__':main()
