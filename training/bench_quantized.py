"""Comparable local CPU benchmarks; these are not Android measurements."""
import argparse
import json
import subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parent


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--export",default="exports/fily-domain-v3")
    args=p.parse_args()
    dest=ROOT/args.export
    exe=ROOT/"tools/llama-b12377/bin/llama-bench.exe"
    reports=[]
    kinds=["F16","Q8_0","Q4_K_M","Q4_0"]
    if (dest/"Fily-EzFiles-350M-Q4_K_M-I.gguf").exists():
        kinds.append("Q4_K_M-I")
    for kind in kinds:
        model=dest/f"Fily-EzFiles-350M-{kind}.gguf"
        command=[str(exe),"-m",str(model),"-p","256","-n","64","-r","3","-t","4","-ngl","0","-o","json"]
        result=subprocess.run(command,capture_output=True,text=True,errors="replace",creationflags=0x08000000)
        (dest/f"bench-{kind}.stderr.log").write_text(result.stderr,encoding="utf-8")
        if result.returncode:
            print(result.stderr[-1500:])
            raise RuntimeError(f"Benchmark failed for {kind}")
        data=json.loads(result.stdout)
        (dest/f"bench-{kind}.json").write_text(json.dumps(data,indent=2)+"\n",encoding="utf-8")
        report={"quantization":kind,"bytes":model.stat().st_size,"measurements":data}
        reports.append(report)
        print(json.dumps({"quantization":kind,"size_MB":round(model.stat().st_size/1e6,2),
              "tests":[{"prompt_tokens":r.get("n_prompt"),"generated_tokens":r.get("n_gen"),"tokens_per_second":r.get("avg_ts")} for r in data]}),flush=True)
    (dest/"benchmarks.json").write_text(json.dumps({"device":"local Windows CPU","threads":4,"repetitions":3,"phone_test":False,"results":reports},indent=2)+"\n",encoding="utf-8")


if __name__=="__main__":
    main()
