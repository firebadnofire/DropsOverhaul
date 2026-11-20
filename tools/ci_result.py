"""Preserve a failed/blocked CI stage when no runtime result could be produced."""
import json
import os
from pathlib import Path

root = Path(__file__).resolve().parents[1]
target = os.environ.get('RESULT_TARGET') or os.environ.get('TARGET_ID')
spec = root / 'build/ci-target.json'
if not target and spec.is_file():
    target = json.loads(spec.read_text())['id']
target = target or 'selection'
destination = root / 'build/runtime' / target
destination.mkdir(parents=True, exist_ok=True)
path = destination / 'result.json'
if not path.is_file():
    toolchain = os.environ.get('TOOLCHAIN_OUTCOME') == 'failure'
    result = dict(target=target, state='blocked' if toolchain else 'failed', startup=False, gameplay=False,
                  stage='toolchain' if toolchain else os.environ.get('FAILED_STAGE', 'build-or-selection'),
                  error='Required toolchain setup failed; inspect setup-java logs' if toolchain else 'CI did not reach a completed runtime probe; inspect the first failed step')
    path.write_text(json.dumps(result, indent=2) + '\n')
print(path.read_text())
