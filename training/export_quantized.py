"""Merge a local Fily adapter and export real llama.cpp GGUF quantizations."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

from pathlib import Path

from run_five_hour import job_lock

ROOT = Path(__file__).resolve().parent
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HOME"] = str(ROOT/"cache")


def run(command, log):
    with log.open("w",encoding="utf-8") as out:
        result = subprocess.run(command, stdout=out, stderr=subprocess.STDOUT, creationflags=0x08000000 if os.name == "nt" else 0)
    if result.returncode:
        print("\n".join(log.read_text(encoding="utf-8",errors="replace").splitlines()[-15:]))
        raise RuntimeError(f"Export tool failed; see {log}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")))
    parser.add_argument("--adapter",default="runs/fily-domain-v3")
    parser.add_argument("--output",default="exports/fily-domain-v3")
    args=parser.parse_args()
    import torch
    torch.set_num_threads(4)
    dest=(ROOT/args.output).resolve()
    if not dest.is_relative_to(ROOT):
        parser.error("The export directory must be inside the training workspace.")
    dest.mkdir(parents=True, exist_ok=True)
    lock = dest / ".export.lock"
    with job_lock(lock):
        publish_export(args, dest)


def publish_export(args, dest):
    if (dest/"manifest.json").exists():
        raise FileExistsError("Choose a new export folder; finished exports are preserved.")
    with tempfile.TemporaryDirectory(prefix=".export-", dir=dest) as workspace:
        staging = Path(workspace)
        artifacts = export_models(args, staging, dest)
        for artifact in artifacts:
            name = artifact["file"]
            (staging / name).replace(dest / name)
    capability_source=ROOT/"data/app_capabilities.json"
    if capability_source.exists():
        shutil.copy2(capability_source,dest/"app_capabilities.json")
    shutil.copy2(Path(args.base)/"LICENSE",dest/"LICENSE")
    manifest={"adapter":args.adapter,"base":args.base,"llama_cpp":"b12377","artifacts":artifacts,
              "capability_file":"app_capabilities.json",
              "scope":"English EzFiles/file assistant; external retrieval/actions and speech adapters required",
              "phone_benchmark":False,"production_acceptance":False}
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=dest, delete=False) as output:
        pending_manifest = Path(output.name)
        json.dump(manifest, output, indent=2)
        output.write("\n")
    try:
        pending_manifest.replace(dest / "manifest.json")
    finally:
        pending_manifest.unlink(missing_ok=True)


def export_models(args, staging, logs):
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    merged = staging / "merged-hf"
    base = AutoModelForCausalLM.from_pretrained(
        args.base, local_files_only=True, dtype=torch.float32, attn_implementation="eager"
    )
    model = PeftModel.from_pretrained(base, ROOT / args.adapter).merge_and_unload(safe_merge=True)
    if not all(torch.isfinite(parameter).all().item() for parameter in model.parameters()):
        raise ValueError("The merged model contains non-finite weights.")
    model.to(torch.float16).save_pretrained(merged, safe_serialization=True)
    AutoTokenizer.from_pretrained(args.base, local_files_only=True).save_pretrained(merged)
    shutil.copy2(Path(args.base) / "LICENSE", merged / "LICENSE")
    del model, base

    tools = ROOT / "tools/llama-b12377"
    f16 = staging / "Fily-EzFiles-350M-F16.gguf"
    converter = tools / "source/llama.cpp-b12377/convert_hf_to_gguf.py"
    run(
        [sys.executable, str(converter), str(merged), "--outtype", "f16",
         "--outfile", str(f16), "--model-name", "Fily EzFiles 350M"],
        logs / "conversion.log",
    )
    artifacts = []
    for quant in ("F16", "Q8_0", "Q4_K_M", "Q4_0"):
        path = staging / f"Fily-EzFiles-350M-{quant}.gguf"
        if quant != "F16":
            run(
                [str(tools / "bin/llama-quantize.exe"), str(f16), str(path), quant, "4"],
                logs / f"quantize-{quant}.log",
            )
        run(
            [str(tools / "bin/llama-bench.exe"), "-m", str(path), "-p", "8", "-n", "1",
             "-r", "1", "-t", "4", "-ngl", "0"],
            logs / f"validate-{quant}.log",
        )
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        artifacts.append({"quantization": quant, "file": path.name, "bytes": path.stat().st_size, "sha256": digest})
        print(json.dumps(artifacts[-1]), flush=True)
    return artifacts


if __name__=="__main__":
    main()
