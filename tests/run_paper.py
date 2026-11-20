"""Boot an isolated Paper test server and exercise it through a real client.

Requires a downloaded server.jar (verified separately) and compiled fixtures.
All generated state stays under build/runtime. Never uses a production server.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import threading
import time
from datetime import datetime, timezone
from server_process import stop_server

ROOT = Path(__file__).resolve().parents[1]


def prepare_native(server, proc, scenario, target):
    text = (ROOT / 'src/main/resources/config.yml').read_text()
    text = text.replace('Full-Inventory-Delete: true', 'Full-Inventory-Delete: ' + ('true' if scenario == 'full-delete' else 'false'))
    text = text.replace('    - "DIRT"', '    - "DIRT"\n    - "DIAMOND_ORE"')
    (server / 'config/DropsOverhaul/config.yml').write_text(text)
    rule = 'minecraft:block_drops' if tuple(map(int, target['minecraft'].split('.'))) >= (1, 21, 11) else 'doTileDrops'
    commands = ['forceload add -3 -3 3 4', 'dofixturemode ' + scenario, 'dropsoverhaul reload', 'kill @e[type=minecraft:item]',
                'gamerule ' + rule + ' ' + ('false' if scenario == 'gamerule' else 'true'),
                'fill -3 99 -3 3 99 4 minecraft:bedrock', 'fill -3 100 -3 3 104 4 minecraft:air',
                'tp DOTester 0.5 100 0.5', 'gamemode ' + ('creative' if scenario == 'creative' else 'survival') + ' DOTester',
                'clear DOTester', 'item replace entity DOTester hotbar.0 with minecraft:diamond_pickaxe']
    if scenario.startswith('full') or scenario == 'partial':
        for slot in [f'hotbar.{i}' for i in range(1, 9)] + [f'inventory.{i}' for i in range(27)]:
            commands.append(f'item replace entity DOTester {slot} with minecraft:bedrock 64')
        if scenario == 'partial':
            commands.append('item replace entity DOTester hotbar.1 with minecraft:cobblestone 63')
    if scenario in ('silk', 'fortune'):
        commands.append('enchant DOTester minecraft:' + ('silk_touch 1' if scenario == 'silk' else 'fortune 3'))
    block = 'gold_block' if scenario == 'unselected' else 'diamond_ore' if scenario == 'fortune' else 'stone'
    commands += [f'setblock 0 100 3 minecraft:{block}', 'tellraw DOTester ' + json.dumps({'text': 'DO_TEST_READY ' + scenario})]
    proc.stdin.write('\n'.join(commands) + '\n')
    proc.stdin.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', required=True)
    parser.add_argument('--target', default='paper-1.21.11')
    parser.add_argument('--artifact', type=Path)
    parser.add_argument('--forge-build')
    parser.add_argument('--startup-timeout', type=int, default=300)
    args = parser.parse_args()
    java_version = subprocess.run([args.java, '-version'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, check=True).stdout.strip()
    match = re.search(r'version "(?:1\.)?(\d+)', java_version)
    if not match:
        raise SystemExit('Cannot determine server Java version: ' + java_version)
    actual_java = int(match[1])
    manifest = json.loads((ROOT / 'versions.json').read_text())
    target = next(t for t in manifest['artifacts'] if t['id'] == args.target)
    if args.forge_build:
        target = dict(target, forgeBuild=args.forge_build)
    server = ROOT / 'build/runtime' / args.target
    server.mkdir(parents=True, exist_ok=True)
    # Snapshot inputs before startup, so concurrent source edits cannot change
    # the test executed halfway through a long server launch.
    inputs = server / 'run-inputs'
    inputs.mkdir(exist_ok=True)
    script = ROOT / ('tests/native-client.cjs' if target.get('externalFixture') else 'tests/client.cjs')
    client_script = inputs / 'client.cjs'
    shutil.copy2(script, client_script)
    shutil.copy2(Path(__file__), inputs / 'runner.py')
    shutil.copy2(Path(__file__).with_name('server_process.py'), inputs / 'server_process.py')
    shutil.copy2(ROOT / 'tests/protocol-errors.cjs', inputs / 'protocol-errors.cjs')
    launcher = 'server.jar'
    launch_args = None
    if target['platform'] == 'forge':
        argsfile = server / f"libraries/net/minecraftforge/forge/{target['minecraft']}-{target['forgeBuild']}/{'win' if os.name == 'nt' else 'unix'}_args.txt"
        if argsfile.is_file():
            launch_args = ['@' + str(argsfile.relative_to(server)), 'nogui']
        else:
            matches = list(server.glob(f"forge-{target['minecraft']}-{target['forgeBuild']}.jar"))
            if len(matches) != 1:
                raise SystemExit('Install the exact manifest Forge server first')
            launcher = matches[0].name
    if launch_args is None and not (server / launcher).is_file():
        raise SystemExit(f'Download and checksum-verify the matching server.jar into {server}')
    artifact = args.artifact.resolve() if args.artifact else ROOT / target['artifactDirectory'] / target['artifact'].format(version=manifest['projectVersion'])
    plugins = server / ('mods' if target['platform'] == 'forge' else 'plugins')
    plugins.mkdir(exist_ok=True)
    shutil.copy2(artifact, plugins / artifact.name)
    if not target.get('externalFixture') or target.get('nativeTestHooks'):
        shutil.copy2(ROOT / target['buildDirectory'] / 'build/test-fixtures/DropsOverhaul-runtime-tests.jar', plugins)
    # Fixed UUID for the isolated offline test client.
    import uuid
    raw = bytearray(hashlib.md5(b'OfflinePlayer:DOTester').digest())
    raw[6] = (raw[6] & 15) | 48
    raw[8] = (raw[8] & 63) | 128
    (server / 'ops.json').write_text(json.dumps([{'uuid': str(uuid.UUID(bytes=bytes(raw))), 'name': 'DOTester', 'level': 4, 'bypassesPlayerLimit': True}]))
    (server / 'eula.txt').write_text('eula=true\n')
    (server / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=25575\nonline-mode=false\nenforce-secure-profile=false\n'
        'max-players=1\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nuse-native-transport=false\n'
        'generate-structures=false\nlevel-type=' + ('DEFAULT' if target['minecraft'] == '1.12.2' else 'minecraft:normal') + '\nenable-rcon=false\n')
    lines = []
    result = {'target': args.target, 'artifact': artifact.name,
              'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
              'platform': target['platform'], 'minecraft': target['minecraft'],
              'forgeBuild': target.get('forgeBuild'), 'serverJava': actual_java, 'javaVersion': java_version,
              'requiredServerJava': target['serverJava'], 'networkTransport': 'java-nio',
              'validationMode': target.get('validationMode', 'gameplay'),
              'clientScriptSha256': hashlib.sha256(client_script.read_bytes()).hexdigest(),
              'runnerSha256': hashlib.sha256((inputs / 'runner.py').read_bytes()).hexdigest(),
              'shutdownHelperSha256': hashlib.sha256((inputs / 'server_process.py').read_bytes()).hexdigest(),
              'protocolHelperSha256': hashlib.sha256((inputs / 'protocol-errors.cjs').read_bytes()).hexdigest(),
              'testedAtUTC': datetime.now(timezone.utc).isoformat(),
              'state': 'compiled', 'compiled': True, 'startup': False, 'gameplay': False}
    log = (server / 'console.log').open('w', encoding='utf-8')
    proc = subprocess.Popen([args.java, '-Xms256M', '-Xmx2048M'] + (launch_args or ['-jar', launcher, 'nogui']),
                            cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding='utf-8', errors='replace')

    def collect():
        for line in proc.stdout:
            lines.append(line)
            log.write(line)
            log.flush()

    reader = threading.Thread(target=collect, daemon=True)
    reader.start()
    try:
        deadline = time.monotonic() + args.startup_timeout
        while not any('Done (' in line for line in lines):
            if proc.poll() is not None:
                raise RuntimeError('Server exited before startup; see console.log')
            if time.monotonic() > deadline:
                raise RuntimeError('Server startup timed out; see console.log')
            time.sleep(1)
        if not any('DropsOverhaul has been enabled!' in line for line in lines):
            raise RuntimeError('Server started but DropsOverhaul did not enable')
        result['serverVersionLines'] = [line.strip() for line in lines if 'Starting minecraft server version' in line or 'This server is running' in line]
        detected = {match[1] for line in lines if (match := re.search(r'Starting minecraft server version ([0-9.]+)', line))}
        if detected != {target['minecraft']}:
            raise RuntimeError(f"Server version {sorted(detected)} does not match exact requested Minecraft {target['minecraft']}")
        result.update(state='startup-tested', startup=True)
        proc.stdin.write('difficulty peaceful\ndropsoverhaul list\n')
        proc.stdin.flush()
        probe_deadline = time.monotonic() + 10
        while not any('Drops Overhaul-affected blocks list' in line for line in lines):
            if proc.poll() is not None or time.monotonic() > probe_deadline:
                raise RuntimeError('DropsOverhaul configuration command did not execute; see console.log')
            time.sleep(0.1)
        result['configurationProbe'] = True
        if target.get('validationMode', 'gameplay') == 'startup-only':
            result['gameplayBlocked'] = target['gameplayBlocker']
            print('STARTUP ONLY; gameplay BLOCKED: ' + target['gameplayBlocker'])
        else:
            env = dict(os.environ, MC_TEST_VERSION=target['minecraft'], MC_TEST_PORT='25575',
                       DO_CLIENT_DEPS=str(ROOT / 'build/test-client/node_modules'))
            client_lines = []
            client = subprocess.Popen(['node', str(client_script)], cwd=ROOT, env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
            def collect_client():
                try:
                    for line in client.stdout:
                        client_lines.append(line)
                        if line.startswith('{"prepare":'):
                            prepare_native(server, proc, json.loads(line)['prepare'], target)
                        elif line.startswith('{"permissions":'):
                            action = json.loads(line)['permissions']
                            if action not in ('op', 'deop'):
                                raise ValueError('Unknown permission fixture action')
                            proc.stdin.write(action + ' DOTester\ntellraw DOTester ' + json.dumps({'text': 'DO_PERMISSION_STATUS ' + action}) + '\n')
                            proc.stdin.flush()
                except Exception as error:
                    client_lines.append('Scene preparation failed: ' + repr(error) + '\n')
                    client.kill()
            client_reader = threading.Thread(target=collect_client, daemon=True)
            client_reader.start()
            try:
                client.wait(timeout=200)
            except subprocess.TimeoutExpired:
                client.kill()
                client.wait()
                raise RuntimeError('Client test timed out')
            finally:
                client_reader.join(timeout=5)
                (server / 'client.log').write_text(''.join(client_lines), encoding='utf-8')
            if client.returncode or not any('DO_GAMEPLAY_PASS' in line for line in client_lines):
                raise RuntimeError('Gameplay test failed; see client.log')
            result['clientWarnings'] = [json.loads(line.strip().removeprefix('DO_CLIENT_WARNING '))
                                        for line in client_lines if line.startswith('DO_CLIENT_WARNING ')]
            result.update(state='gameplay-tested', gameplay=True,
                          scenarios=[line.strip() for line in (client_lines if target.get('externalFixture') else lines) if 'DO_TEST_PASS' in line])
    except Exception as error:
        result.update(state='failed', error=str(error))
        raise
    finally:
        stop_server(proc, args.java, server, result)
        reader.join(timeout=5)
        log.close()
        (server / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    expected_state = 'startup-tested' if target.get('validationMode') == 'startup-only' else 'gameplay-tested'
    if result['state'] != expected_state or proc.returncode != 0:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
