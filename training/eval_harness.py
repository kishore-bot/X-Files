"""Standing evaluation for Fily GGUF models: per-metric scores, a regression gate and DPO pairs.

Runs a local llama-server (GPU by default), replays held-out conversations turn by turn with the
same JSON envelope the app enforces, and scores each assistant turn. Tools are never executed.

  python eval_harness.py --model exports/fily.gguf --suite app_v8/test --output runs/eval.json \
      --baseline runs/eval_baseline.json            # exit code 1 when any metric drops

Writes <output> (report), <output stem>.failures.jsonl (prompt / chosen / rejected, ready for DPO).
"""
import argparse
import json
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path

from transformers import AutoTokenizer

from evaluate_gguf import schema

ROOT = Path(__file__).resolve().parent
NEGATIVE = re.compile(r"couldn'?t find|could not find|can'?t find|not (in|mentioned)|no mention|"
                      r"doesn'?t (say|mention)|isn'?t (in|there)|not allowed|turned off", re.I)
NUMBER = re.compile(r"\d[\d,.:/-]*\d|\d")
METRICS = ["valid_json", "kind_correct", "tool_name_correct", "tool_args_exact",
           "reply_grounded", "refusal_correct", "exact_match"]


def find_server(explicit):
    if explicit:
        return explicit
    for candidate in (shutil.which("llama-server"),
                      ROOT / "tools/llama-b12377/bin/llama-server.exe",
                      ROOT / "tools/llama-b12377/bin/llama-server"):
        if candidate and Path(candidate).exists():
            return str(candidate)
    sys.exit("llama-server not found; pass --server")


def start_server(exe, model, ctx, gpu_layers, log):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    flags = 0x08000000 if os.name == "nt" else 0
    server = subprocess.Popen([exe, "-m", str(model), "--host", "127.0.0.1", "--port", str(port),
                               "-c", str(ctx), "-np", "1", "-ngl", str(gpu_layers), "--no-webui"],
                              stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
    url = f"http://127.0.0.1:{port}"
    for _ in range(240):
        if server.poll() is not None:
            raise RuntimeError("llama-server exited; see the .server.log next to the output")
        try:
            with urllib.request.urlopen(url + "/health", timeout=1) as response:
                if response.status == 200:
                    return server, url
        except (OSError, urllib.error.URLError):
            time.sleep(0.5)
    server.kill()
    raise TimeoutError("llama-server did not become ready")


def evidence_of(messages):
    """Text the tools returned earlier in the conversation: what a reply may state as fact."""
    return "\n".join(m["content"] for m in messages if m["role"] == "tool").lower()


def numbers_grounded(text, evidence):
    digits = "".join(ch for ch in evidence if ch.isalnum())
    cleaned = re.sub(r"\bpages?\s+\d+", "", text, flags=re.I)
    return all("".join(filter(str.isdigit, n)) in digits for n in NUMBER.findall(cleaned))


def score(expected, prediction, history):
    s = {m: None for m in METRICS}
    s["valid_json"] = prediction is not None
    s["exact_match"] = prediction == expected
    if prediction is None:
        return s
    s["kind_correct"] = prediction.get("kind") == expected.get("kind")
    if expected.get("kind") == "tool":
        s["tool_name_correct"] = prediction.get("name") == expected.get("name")
        s["tool_args_exact"] = s["tool_name_correct"] and prediction.get("arguments") == expected.get("arguments")
    elif expected.get("kind") == "reply":
        text = prediction.get("text", "") if prediction.get("kind") == "reply" else ""
        evidence = evidence_of(history)
        if evidence:
            s["reply_grounded"] = prediction.get("kind") == "reply" and numbers_grounded(text, evidence)
        if NEGATIVE.search(expected.get("text", "")):
            s["refusal_correct"] = bool(NEGATIVE.search(text))
    return s


def rates(results):
    out = {}
    for metric in METRICS:
        values = [r["scores"][metric] for r in results if r["scores"][metric] is not None]
        out[metric] = {"rate": round(sum(values) / len(values), 4) if values else None, "n": len(values)}
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", required=True)
    p.add_argument("--suite", default="app_v8/test", help="data/<suite>.jsonl")
    p.add_argument("--output", required=True)
    p.add_argument("--limit", type=int, default=200)
    p.add_argument("--ctx", type=int, default=4096, help="match the app's N_CTX")
    p.add_argument("--gpu-layers", type=int, default=99, help="99 = all layers on the GPU; 0 = CPU")
    p.add_argument("--server")
    p.add_argument("--tokenizer", default=os.environ.get("FILY_TOKENIZER", os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2"))))
    p.add_argument("--baseline", help="earlier report; fail when a metric drops more than --max-drop")
    p.add_argument("--max-drop", type=float, default=0.02)
    args = p.parse_args()

    model = (ROOT / args.model).resolve()
    output = (ROOT / args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, local_files_only=True)
    rows = [json.loads(line) for line in (ROOT / "data" / f"{args.suite}.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    turns = [(r, i) for r in rows for i, m in enumerate(r["messages"]) if m["role"] == "assistant"]
    random.Random(1411).shuffle(turns)
    turns = turns[:args.limit]

    log = output.with_suffix(".server.log").open("w", encoding="utf-8")
    server, url = start_server(find_server(args.server), model, args.ctx, args.gpu_layers, log)
    results, failures = [], []
    try:
        for n, (row, i) in enumerate(turns, 1):
            history = row["messages"][:i]
            prompt = tokenizer.apply_chat_template(history, tokenize=False, add_generation_prompt=True)
            payload = {"prompt": tokenizer.encode(prompt, add_special_tokens=False), "temperature": 0, "seed": 1411,
                       "n_predict": 180, "cache_prompt": False, "stop": ["<|im_end|>"], "json_schema": schema(row)}
            request = urllib.request.Request(url + "/completion", data=json.dumps(payload).encode(),
                                             headers={"Content-Type": "application/json"})
            started = time.perf_counter()
            with urllib.request.urlopen(request, timeout=180) as response:
                raw = json.load(response)["content"].strip()
            try:
                prediction = json.loads(raw)
            except json.JSONDecodeError:
                prediction = None
            expected = json.loads(row["messages"][i]["content"])
            scores = score(expected, prediction, history)
            results.append({"id": row["id"], "turn": i, "family": row.get("family", "?"), "scores": scores,
                            "expected": expected, "prediction": prediction, "seconds": round(time.perf_counter() - started, 3)})
            if not scores["exact_match"]:
                failures.append({"id": row["id"], "family": row.get("family", "?"), "prompt": history,
                                 "chosen": row["messages"][i]["content"], "rejected": raw})
            if n % 20 == 0:
                print(json.dumps({"done": n, "of": len(turns)}), flush=True)
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
        log.close()

    by_family = defaultdict(list)
    for r in results:
        by_family[r["family"]].append(r)
    report = {"model": args.model, "suite": args.suite, "examples": len(results), "ctx": args.ctx,
              "gpu_layers": args.gpu_layers, "metrics": rates(results),
              "families": {f: rates(rs) for f, rs in sorted(by_family.items())},
              "mean_seconds": round(sum(r["seconds"] for r in results) / max(len(results), 1), 3),
              "results": results}
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    with output.with_suffix(".failures.jsonl").open("w", encoding="utf-8") as f:
        for item in failures:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    print(json.dumps({"metrics": report["metrics"], "failures": len(failures)}, indent=2))

    if args.baseline:
        base = json.loads(Path(args.baseline).read_text(encoding="utf-8"))["metrics"]
        drops = {m: (base[m]["rate"], report["metrics"][m]["rate"]) for m in METRICS
                 if base.get(m, {}).get("rate") is not None and report["metrics"][m]["rate"] is not None
                 and base[m]["rate"] - report["metrics"][m]["rate"] > args.max_drop}
        if drops:
            print(json.dumps({"regressions": drops}, indent=2))
            sys.exit(1)
        print("No regressions against baseline.")


if __name__ == "__main__":
    main()
