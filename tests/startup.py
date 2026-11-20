"""Startup-only probe for explicit exploratory artifacts; never claims gameplay."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
from install_server import validate_target
from server_process import stop_server

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--spec', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    target = json.loads(args.spec.read_text())
    validate_target(target)
    server = ROOT / 'build/runtime' / target['id']
    kind = target['platform']
    destination = server / ('mods' if kind == 'forge' else 'plugins')
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.artifact, destination / args.artifact.name)
    (server / 'eula.txt').write_text('eula=true\n')
    (server / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25575\nonline-mode=false\nwhite-list=true\nmax-players=1\nview-distance=2\nenable-rcon=false\nuse-native-transport=false\n')
    command = [args.java, '-Xms256M', '-Xmx2048M']
    if kind == 'forge':
        coordinate = target['minecraft'] + '-' + target['forgeBuild']
        argfile = server / f"libraries/net/minecraftforge/forge/{coordinate}/{'win' if os.name == 'nt' else 'unix'}_args.txt"
        command += ['@' + str(argfile.relative_to(server)), 'nogui'] if argfile.is_file() else ['-jar', f'forge-{coordinate}.jar', 'nogui']
    else:
        command += ['-jar', 'server.jar', 'nogui']
    result = dict(target=target['id'], state='compiled', startup=False, gameplay=False, networkTransport='java-nio',
                  sha256=hashlib.sha256(args.artifact.read_bytes()).hexdigest(), stage='startup-only')
    lines = []
    with (server / 'console.log').open('w', encoding='utf-8') as log:
        proc = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding='utf-8', errors='replace')
        def collect():
            for line in proc.stdout:
                lines.append(line)
                log.write(line)
                log.flush()
        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        try:
            deadline = time.monotonic() + args.timeout
            while not any('Done (' in line for line in lines):
                if proc.poll() is not None: raise RuntimeError('Server exited before Done; see console.log')
                if time.monotonic() > deadline: raise RuntimeError('Server startup timeout; see console.log')
                time.sleep(1)
            detected = {match[1] for line in lines if (match := re.search(r'Starting minecraft server version ([0-9.]+)', line))}
            if detected != {target['minecraft']}:
                raise RuntimeError(f"Actual server version {sorted(detected)} differs from requested {target['minecraft']}")
            before = len(lines)
            proc.stdin.write('forge mods\ndropsoverhaul list\n' if kind == 'forge' else 'plugins\ndropsoverhaul list\n')
            proc.stdin.flush()
            time.sleep(5)
            token = r'(?<![\w.-])' + re.escape(target['expectedId']) + r'(?![\w.-])'
            probe = re.sub(r'\x1b\[[0-9;]*m|§[0-9a-fk-or]', '', ''.join(lines[before:]), flags=re.I)
            if kind == 'forge':
                legacy = re.findall(r'Attempting connection with missing mods \[([^\]]+)\] at SERVER', ''.join(lines))
                loaded = bool(re.search(token, probe, re.I)) and not re.search(r'Unknown.*command', probe, re.I)
                loaded = loaded or any(target['expectedId'].lower() in [x.strip().lower() for x in row.split(',')] for row in legacy)
            else:
                listed = re.search(r'Plugins?\s*\(\d+\)\s*:\s*([^\r\n]+)', probe, re.I)
                loaded = bool(listed and re.search(token, listed[1], re.I))
            if target['expectedId'].lower() == 'dropsoverhaul':
                # Paper's newer plugin-list formatting differs from legacy Bukkit.
                # Our final enable marker plus an executed configuration command
                # prove this artifact reached its registered native implementation.
                enabled = 'DropsOverhaul has been enabled!' in ''.join(lines)
                configured = 'Drops Overhaul-affected blocks list' in probe
                loaded = enabled and configured
            if not loaded: raise RuntimeError('Expected plugin/mod ID was not proven loaded by native probe; see console.log')
            result.update(state='startup-tested', startup=True)
        except Exception as error:
            result.update(state='failed', error=str(error))
        finally:
            stop_server(proc, args.java, server, result)
            reader.join(timeout=5)
            (server / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if result['state'] != 'startup-tested': raise SystemExit(1)


if __name__ == '__main__': main()
