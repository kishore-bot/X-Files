"""Evaluate quantized predictions on a private localhost server; never execute tools."""
import argparse
import copy
import json
import os
import random
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from transformers import AutoTokenizer
from catalog import TOOLS
from validate_dataset import validate_row

ROOT=Path(__file__).resolve().parent


def schema(row):
    variants=[]
    for name in row["tools"]:
        # Full per-tool schemas reorder optional/required properties in llama.cpp's grammar.
        # Preserve natural argument order; validate the complete contract after decoding.
        variants.append({"type":"object","properties":{"kind":{"const":"tool"},"name":{"const":name},
                         "arguments":{"type":"object","additionalProperties":True}},
                         "required":["kind","name","arguments"],"additionalProperties":False})
    variants += [{"type":"object","properties":{"kind":{"const":"reply"},"text":{"type":"string"},
                  "citations":{"type":"array","items":{"type":"string"}}},"required":["kind","text","citations"],"additionalProperties":False},
                 {"type":"object","properties":{"kind":{"const":"clarify"},"text":{"type":"string"}},"required":["kind","text"],"additionalProperties":False}]
    return {"oneOf":variants}


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--model",required=True)
    p.add_argument("--suite",default="domain_test")
    p.add_argument("--limit",type=int,default=30)
    p.add_argument("--output",required=True)
    p.add_argument("--unconstrained",action="store_true")
    args=p.parse_args()
    model=(ROOT/args.model).resolve()
    output=(ROOT/args.output).resolve()
    assert model.is_relative_to(ROOT) and output.is_relative_to(ROOT)
    exe=ROOT/"tools/llama-b12377/bin/llama-server.exe"
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        port=sock.getsockname()[1]
    url=f"http://127.0.0.1:{port}"
    log=output.with_suffix(".server.log").open("w",encoding="utf-8")
    server=subprocess.Popen([str(exe),"-m",str(model),"--host","127.0.0.1","--port",str(port),
                             "-c","2048","-np","1","-t","4","-tb","4","-ngl","0","--no-webui"],
                            stdout=log,stderr=subprocess.STDOUT,creationflags=0x08000000)
    try:
        for _ in range(120):
            if server.poll() is not None:
                raise RuntimeError("Local server exited; inspect its log")
            try:
                with urllib.request.urlopen(url+"/health",timeout=1) as response:
                    if response.status==200:
                        break
            except (OSError,urllib.error.URLError):
                time.sleep(0.5)
        else:
            raise TimeoutError("Local inference server did not become ready")
        tokenizer=AutoTokenizer.from_pretrained(os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")),local_files_only=True)
        rows=[json.loads(s) for s in (ROOT/"data"/f"{args.suite}.jsonl").read_text(encoding="utf-8").splitlines()]
        turns=[(r,i) for r in rows for i,m in enumerate(r["messages"]) if m["role"]=="assistant"]
        random.Random(1411).shuffle(turns)
        results=[]
        for row,i in turns[:args.limit]:
            prompt=tokenizer.apply_chat_template(row["messages"][:i],tokenize=False,add_generation_prompt=True)
            # Token IDs already contain the template's BOS; string input adds another BOS.
            token_ids=tokenizer.encode(prompt,add_special_tokens=False)
            payload={"prompt":token_ids,"temperature":0,"seed":1411,"n_predict":180,"cache_prompt":False,
                     "stop":["<|im_end|>"]}
            if not args.unconstrained:
                payload["json_schema"]=schema(row)
            request=urllib.request.Request(url+"/completion",data=json.dumps(payload).encode(),headers={"Content-Type":"application/json"})
            started=time.perf_counter()
            with urllib.request.urlopen(request,timeout=120) as response:
                result=json.load(response)
            raw=result["content"].strip()
            try:
                prediction=json.loads(raw)
            except json.JSONDecodeError:
                prediction=None
            valid=False
            error=None
            if prediction is not None:
                check=copy.deepcopy(row)
                check["messages"]=check["messages"][:i]+[{"role":"assistant","content":raw}]
                try:
                    validate_row(check)
                    valid=True
                except Exception as exc:
                    error=type(exc).__name__+": "+str(exc)
            expected=json.loads(row["messages"][i]["content"])
            capability_faithful=None
            facts=row["state"].get("capabilityFacts",{})
            if "extensions" in facts:
                capability_faithful=False
                if prediction and prediction.get("kind")=="reply":
                    import re
                    mentioned=set(re.findall(r"\.[A-Za-z0-9_+]+",prediction.get("text","")))
                    capability_faithful=mentioned==set(facts["extensions"])
            entry={"id":row["id"],"turn":i,"family":row["family"],"expected":expected,"prediction":prediction,
                   "raw":raw,"contract_valid":valid,"error":error,"exact_match":prediction==expected,
                   "format_extensions_exact":capability_faithful,
                   "seconds":time.perf_counter()-started,"timings":result.get("timings",{})}
            results.append(entry)
            print(json.dumps({"completed":len(results),"family":row["family"],"exact_match":entry["exact_match"]}),flush=True)
        tool_results=[r for r in results if r["expected"]["kind"]=="tool"]
        format_results=[r for r in results if r["format_extensions_exact"] is not None]
        report={"model":args.model,"suite":args.suite,"constrained":not args.unconstrained,"constraint_scope":"envelope_and_tool_name; arguments validated after decoding","examples":len(results),
                "valid_json":sum(r["prediction"] is not None for r in results),"contract_valid":sum(r["contract_valid"] for r in results),
                "exact_matches":sum(r["exact_match"] for r in results),"tool_examples":len(tool_results),
                "tool_exact_matches":sum(r["exact_match"] for r in tool_results),"results":results,
                "format_examples":len(format_results),"format_extensions_exact":sum(r["format_extensions_exact"] for r in format_results),
                "note":"Local Windows CPU; synthetic supplied histories; schema constraints cannot guarantee semantic accuracy; no tool execution or phone test."}
        output.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
        print(json.dumps({k:v for k,v in report.items() if k!="results"}),flush=True)
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
        log.close()


if __name__=="__main__":
    main()
