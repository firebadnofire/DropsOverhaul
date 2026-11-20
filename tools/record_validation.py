"""Explicitly record evidence for the exact current artifact, without release promotion."""
import argparse
import json
from pathlib import Path
from check_artifact import inspect
from targets import ROOT, load

parser = argparse.ArgumentParser()
parser.add_argument('--target', required=True)
parser.add_argument('--result', type=Path, required=True)
args = parser.parse_args()
manifest = load()
target = next(t for t in manifest['artifacts'] if t['id'] == args.target)
result = json.loads(args.result.read_text())
artifact = inspect(args.target)
if result['target'] != args.target or result['sha256'] != artifact['sha256']:
    raise SystemExit('Evidence does not match the exact current target/artifact hash')
if result['state'] in ('startup-tested', 'gameplay-tested'):
    if not result.get('startup') or result.get('serverExitCode') != 0:
        raise SystemExit('Runtime claim lacks successful startup and clean shutdown')
    for key in ('platform', 'minecraft', 'serverJava', 'forgeBuild'):
        if result.get(key) != target.get(key):
            raise SystemExit(f'Runtime evidence differs from the manifest {key}; record exploratory results separately')
if result['state'] not in manifest['validationStates']:
    raise SystemExit('Unknown validation state')
if result['state'] == 'gameplay-tested' and not (result['startup'] and result['gameplay'] and result['serverExitCode'] == 0):
    raise SystemExit('Gameplay claim lacks passing runtime evidence')
destination = ROOT / 'validation' / (args.target + '.json')
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps(result, indent=2) + '\n')
target['state'] = result['state']
target['evidence'] = [str(destination.relative_to(ROOT)).replace('\\', '/')]
(ROOT / 'versions.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f"Recorded {args.target}: {result['state']}; release eligibility unchanged")
