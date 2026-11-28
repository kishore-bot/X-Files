"""Print local job state without loading a model or touching app files."""
import json
from pathlib import Path

import sys
root=Path(__file__).resolve().parent/'runs'/(sys.argv[1] if len(sys.argv)>1 else 'fily-app-v8')
pipeline=json.loads((root/'pipeline.json').read_text()) if (root/'pipeline.json').exists() else {}
for name in ['pipeline.json','progress.json','failure.json']:
    if name=='failure.json' and pipeline.get('status')!='failed':continue
    path=root/name
    if path.exists():
        value=json.loads(path.read_text(encoding='utf-8'))
        keys=['status','pid','step','loss','elapsed_seconds','remaining_seconds','eta_utc','selected_adapter','export','error']
        print(name+': '+json.dumps({k:value[k] for k in keys if k in value},indent=2))
