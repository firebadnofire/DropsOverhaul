"""Download the exact manifest Minecraft version with Paper's SHA-256 check."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from targets import ROOT, fetch, load

parser = argparse.ArgumentParser()
parser.add_argument('--target', required=True)
args = parser.parse_args()
target = next(t for t in load()['artifacts'] if t['id'] == args.target)
if target['platform'] != 'paper':
    raise SystemExit('Not a Paper target')
build = fetch(f"https://fill.papermc.io/v3/projects/paper/versions/{target['minecraft']}/builds")[0]
download = build['downloads']['server:default']
directory = ROOT / 'build/runtime' / target['id']
directory.mkdir(parents=True, exist_ok=True)
path = directory / 'server.jar'
expected = download['checksums']['sha256']
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
    temporary = path.with_suffix('.download')
    request = urllib.request.Request(download['url'], headers={'User-Agent': 'DropsOverhaul-validation/1.0'})
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
        import shutil
        shutil.copyfileobj(response, output)
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
        raise SystemExit('Paper SHA-256 verification failed; download not installed')
    temporary.replace(path)
(directory / 'download.json').write_text(json.dumps(build, indent=2) + '\n')
print(f"Verified Paper {target['minecraft']} build {build['id']}: {expected}")
