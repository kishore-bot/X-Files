"""Training-only calibration for an importance-aware Q4_K_M export."""
import os
import argparse
import hashlib
import json
import random
from transformers import AutoTokenizer
from export_quantized import ROOT,run


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--export",default="exports/fily-domain-v4")
    p.add_argument("--train-file",default="data/grounded_domain_train.jsonl")
    args=p.parse_args()
    dest=ROOT/args.export
    source=(ROOT/args.train_file).resolve()
    if not source.is_relative_to(ROOT):raise ValueError('Calibration source must be inside training/')
    rows=[json.loads(s) for s in source.read_text(encoding="utf-8").splitlines()]
    selected=random.Random(281).sample(rows,32)
    tokenizer=AutoTokenizer.from_pretrained(os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")),local_files_only=True)
    text="\n\n".join(tokenizer.apply_chat_template(r["messages"],tokenize=False,add_generation_prompt=False) for r in selected)
    calibration=dest/"calibration-training-only.txt"
    calibration.write_text(text,encoding="utf-8")
    tools=ROOT/"tools/llama-b12377/bin"
    matrix=dest/"importance.gguf"
    run([str(tools/"llama-imatrix.exe"),"-m",str(dest/"Fily-EzFiles-350M-F16.gguf"),"-f",str(calibration),
         "-o",str(matrix),"-c","512","--chunks","16","-t","4","-ngl","0","--no-ppl"],dest/"calibration.log")
    output=dest/"Fily-EzFiles-350M-Q4_K_M-I.gguf"
    run([str(tools/"llama-quantize.exe"),"--imatrix",str(matrix),str(dest/"Fily-EzFiles-350M-F16.gguf"),
         str(output),"Q4_K_M","4"],dest/"quantize-Q4_K_M-I.log")
    report={"calibration_source":args.train_file,"rows":len(selected),"chunks":16,"context":512,
            "calibration_sha256":hashlib.sha256(calibration.read_bytes()).hexdigest(),
            "artifact":output.name,"bytes":output.stat().st_size,"sha256":hashlib.sha256(output.read_bytes()).hexdigest()}
    (dest/"calibration-manifest.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report),flush=True)


if __name__=="__main__":
    main()
