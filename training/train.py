"""Local-only CUDA LoRA training; never imports or executes application code."""
import argparse
import hashlib
import json
import os
import random
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HOME"] = str(ROOT / "cache")
os.environ["TOKENIZERS_PARALLELISM"] = "false"

import torch
from peft import LoraConfig, PeftModel, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer


def load_rows(split):
    return [json.loads(s) for s in (ROOT / "data" / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]


def examples(tokenizer, rows, max_length):
    result, rejected = [], []
    for row in rows:
        messages = row["messages"]
        for end, m in enumerate(messages):
            if m["role"] != "assistant":
                continue
            prefix = tokenizer.apply_chat_template(messages[:end], tokenize=False, add_generation_prompt=True)
            full = tokenizer.apply_chat_template(messages[:end+1], tokenize=False, add_generation_prompt=False)
            assert full.startswith(prefix), "Chat template prefix is inconsistent"
            # Encode separately so token merges cannot shift the loss boundary.
            prompt_ids = tokenizer.encode(prefix, add_special_tokens=False)
            answer_ids = tokenizer.encode(full[len(prefix):], add_special_tokens=False)
            ids = prompt_ids + answer_ids
            if len(ids) > max_length:
                rejected.append({"id": row["id"], "tokens": len(ids)})
                continue
            assert answer_ids
            result.append({"input_ids": ids, "labels": [-100] * len(prompt_ids) + answer_ids,
                           "id": row["id"], "family": row["family"]})
    return result, rejected


def batch(example, device):
    return {k: torch.tensor([example[k]], dtype=torch.long, device=device) for k in ("input_ids", "labels")}


def load_model(path):
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is required; this script never silently trains on CPU.")
    if not (Path(path) / "model.safetensors").is_file():
        raise FileNotFoundError("A local pretrained model.safetensors is required.")
    dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    model = AutoModelForCausalLM.from_pretrained(path, local_files_only=True, dtype=dtype,
                                                attn_implementation="sdpa")
    model.config.use_cache = False
    return model.to("cuda"), dtype


@torch.no_grad()
def loss_eval(model, data):
    model.eval()
    total, tokens = 0.0, 0
    for ex in data:
        count = sum(x != -100 for x in ex["labels"])
        total += float(model(**batch(ex, "cuda")).loss) * count
        tokens += count
    model.train()
    return total / tokens


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")))
    parser.add_argument("--output", default="runs/fily-lora-v1")
    parser.add_argument("--steps", type=int, default=0, help="0 = one full epoch")
    parser.add_argument("--max-length", type=int, default=1536)
    parser.add_argument("--accumulation", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=1e-4)
    parser.add_argument("--smoke", action="store_true", help="Stress longest sequences; separate adapter output")
    parser.add_argument("--init-adapter", help="Continue adapting a saved local adapter, without overwriting it")
    parser.add_argument("--train-file", default="data/train.jsonl")
    parser.add_argument("--validation-file", default="data/validation.jsonl")
    args = parser.parse_args()
    torch.set_num_threads(4)
    torch.manual_seed(14112026)
    random.seed(14112026)
    output = (ROOT / args.output).resolve()
    if not output.is_relative_to(ROOT):
        raise ValueError("Output must stay inside training/")
    if (output / "adapter_model.safetensors").exists():
        raise FileExistsError("Choose a fresh output path; an existing adapter will not be overwritten.")
    output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True)
    def read_local(relative):
        path = (ROOT / relative).resolve()
        if not path.is_relative_to(ROOT):
            raise ValueError("Dataset must stay inside training/")
        return [json.loads(s) for s in path.read_text(encoding="utf-8").splitlines()]
    data, rejected = examples(tokenizer, read_local(args.train_file), args.max_length)
    validation, val_rejected = examples(tokenizer, read_local(args.validation_file), args.max_length)
    summary = {"train_examples": len(data), "validation_examples": len(validation),
               "max_tokens": max(len(x["input_ids"]) for x in data),
               "rejected_train": rejected, "rejected_validation": val_rejected}
    (output / "tokenization.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if rejected or val_rejected:
        raise ValueError("Examples exceed the token budget; inspect tokenization.json rather than silently truncating them.")
    print(json.dumps(summary), flush=True)
    model, dtype = load_model(args.model)
    targets = ["q_proj", "k_proj", "v_proj", "out_proj", "in_proj", "w1", "w2", "w3"]
    if args.init_adapter:
        adapter = (ROOT / args.init_adapter).resolve()
        if not adapter.is_relative_to(ROOT):
            raise ValueError("Adapter must stay inside training/")
        model = PeftModel.from_pretrained(model, adapter, is_trainable=True)
    else:
        model = get_peft_model(model, LoraConfig(r=8, lora_alpha=16, lora_dropout=0.05,
                              target_modules=targets, task_type="CAUSAL_LM", bias="none"))
    if dtype == torch.float16:
        # T4/P100 (Kaggle) have no bf16: the frozen base stays fp16, trainable LoRA weights must be
        # fp32 or GradScaler refuses to unscale fp16 gradients.
        for p in model.parameters():
            if p.requires_grad:
                p.data = p.data.float()
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    model.print_trainable_parameters()
    optimizer = torch.optim.AdamW((p for p in model.parameters() if p.requires_grad), lr=args.learning_rate)
    scaler = torch.amp.GradScaler("cuda", enabled=dtype == torch.float16)
    validation = random.Random(71).sample(validation, min(24, len(validation)))
    baseline = loss_eval(model, validation)
    print(json.dumps({"baseline_validation_loss": baseline, "gpu": torch.cuda.get_device_name(0)}), flush=True)
    random.shuffle(data)
    if args.smoke:
        data.sort(key=lambda x: len(x["input_ids"]), reverse=True)
    steps = args.steps or ((len(data) + args.accumulation - 1) // args.accumulation)
    started = time.monotonic()
    log = []
    model.train()
    torch.cuda.reset_peak_memory_stats()
    for step in range(steps):
        optimizer.zero_grad(set_to_none=True)
        losses = []
        for micro in range(args.accumulation):
            ex = data[(step * args.accumulation + micro) % len(data)]
            with torch.autocast("cuda", dtype=dtype):
                raw_loss = model(**batch(ex, "cuda")).loss
                if not torch.isfinite(raw_loss):
                    raise FloatingPointError("Non-finite training loss")
                loss = raw_loss / args.accumulation
            scaler.scale(loss).backward()
            losses.append(float(raw_loss.detach()))
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_((p for p in model.parameters() if p.requires_grad), 1.0, error_if_nonfinite=True)
        scaler.step(optimizer)
        scaler.update()
        # Short warmup followed by linear decay.
        fraction = min((step+1) / max(1, min(20, steps//10)), max(0.1, 1-(step+1)/steps))
        for group in optimizer.param_groups:
            group["lr"] = args.learning_rate * fraction
        entry = {"step": step+1, "loss": sum(losses)/len(losses), "seconds": round(time.monotonic()-started, 2),
                 "peak_allocated_MiB": round(torch.cuda.max_memory_allocated()/2**20, 1)}
        log.append(entry)
        if (step+1) % 10 == 0 or step == 0 or step+1 == steps:
            print(json.dumps(entry), flush=True)
            (output / "progress.json").write_text(json.dumps(entry) + "\n", encoding="utf-8")
        if (step+1) % 100 == 0:
            checkpoint = output / f"checkpoint-{step+1}"
            model.save_pretrained(checkpoint)
            torch.save({"optimizer": optimizer.state_dict(), "step": step+1,
                        "rng": torch.get_rng_state(), "cuda_rng": torch.cuda.get_rng_state()}, checkpoint / "training_state.pt")
    final_loss = loss_eval(model, validation)
    model.save_pretrained(output)
    tokenizer.save_pretrained(output)
    report = {"arguments": vars(args), "device": torch.cuda.get_device_name(0), "dtype": str(dtype),
              "steps": steps, "training_examples": len(data), "validation_examples": len(validation),
              "baseline_validation_loss": baseline, "final_validation_loss": final_loss,
              "elapsed_seconds": round(time.monotonic()-started, 2),
              "peak_allocated_MiB": round(torch.cuda.max_memory_allocated()/2**20, 1),
              "base_model_sha256": hashlib.file_digest(open(Path(args.model)/"model.safetensors", "rb"), "sha256").hexdigest(),
              "versions": {"torch": torch.__version__}, "log": log,
              "limitations": "Synthetic curriculum; loss is not real voice/tool accuracy. No app integration."}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in {"log", "arguments"}}), flush=True)


if __name__ == "__main__":
    main()
