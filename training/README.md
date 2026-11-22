# Fily training (350M)

The minimum needed to rebuild, train, export and check Fily's on-device model, the LFM2.5-350M fine-tune that Xfiles downloads as `Fily-EzFiles-350M-Q8_0.gguf`. Scripts, the tool catalog and small inputs are tracked. The venv, generated data, checkpoints, exports and llama.cpp tools are gitignored and rebuilt locally.

## Setup (Windows, Python 3.13, NVIDIA GPU with CUDA 12.4)

```powershell
py -3.13 -m venv training\.venv
.\training\.venv\Scripts\python.exe -m pip install -r training\requirements-lock.txt
.\training\.venv\Scripts\python.exe training/get_quant_tools.py
```

Base model: download [LiquidAI/LFM2.5-350M](https://huggingface.co/LiquidAI/LFM2.5-350M) to `~\Downloads\LFM2`, or set `FILY_BASE_MODEL` to its folder. The model's licence is the owner's to check before distribution.

## Data

The builders are seeded, so each run recreates the same files. Run them in this order, because each one replays the previous set:

```powershell
.\training\.venv\Scripts\python.exe training/build_dataset.py          # base curriculum -> data/train|validation|test.jsonl
.\training\.venv\Scripts\python.exe training/build_hard_dataset.py     # contrast cases
.\training\.venv\Scripts\python.exe training/build_domain_dataset.py   # file formats from FEATURES.md
.\training\.venv\Scripts\python.exe training/build_edge_curriculum.py  # edge v5 (what the shipped model was trained on)
.\training\.venv\Scripts\python.exe training/build_app_v8.py           # app v8: real IDs, grounded answers, follow-ups
.\training\.venv\Scripts\python.exe training/validate_dataset.py
```

## Train, export, evaluate

`run_five_hour.py` runs training (`train_long.py`), then GGUF export (`export_quantized.py`), quantization calibration, evaluation (`evaluate_gguf.py`) and a benchmark, as one detached job:

```powershell
.\training\.venv\Scripts\python.exe training/run_five_hour.py --data data/app_v8 --output runs/fily-app-v8 --init-adapter runs/fily-edge-v5-5h/checkpoint-003102
.\training\.venv\Scripts\python.exe training/status_five_hour.py
```

After a reboot, resume with `--resume` and the same arguments. `eval_harness.py` scores a GGUF on the phone-style prompts.

Tests: `.\training\.venv\Scripts\python.exe -m unittest discover -s training -p "test_*.py" -v`

## Contract with the app

`tool_catalog.json` must stay identical to `sharedLogic/src/androidHostTest/resources/fily/tool_catalog.json`, and the prompt layout identical to `FilyPrompt.kt`. `FilyPromptContractTest` enforces this. Don't change POLICY, the tool JSON or APP_STATE fields without changing the app in the same release. Screen commands the model wasn't trained on, such as scrolling, are handled by the app's `QuickCommands` before the model.

If you rename the exported file or change its contents, update the name, size and SHA-256 in `sharedLogic/.../fily/ModelDownloads.kt` and the published release asset.
