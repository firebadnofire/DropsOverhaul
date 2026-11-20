"""Install a real server into a generated, isolated test directory.

Missing distributions are blocked; transport/installer failures are failed.
Neither case is converted to a successful compatibility result.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from targets import fetch, load


class Unavailable(RuntimeError):
    pass


def validate_target(target):
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]*', target['id']) or '..' in target['id']:
        raise ValueError('Unsafe isolated target ID')
    if target['platform'] not in ('paper', 'spigot', 'forge'):
        raise ValueError('Unknown native server platform')
    if not re.fullmatch(r'\d{1,2}\.\d{1,2}(?:\.\d{1,2})?', target['minecraft']):
        raise ValueError('An exact released Minecraft version is required')
    if target.get('forgeBuild') and not re.fullmatch(r'\d+(?:\.\d+)*', target['forgeBuild']):
        raise ValueError('Invalid exact Forge build')
    if target.get('serverRevision') and not re.fullmatch(r'[A-Za-z0-9.]+', target['serverRevision']):
        raise ValueError('Invalid pinned BuildTools revision')
    base = (ROOT / 'build/runtime').resolve()
    directory = base / target['id']
    if not directory.resolve().is_relative_to(base):
        raise ValueError('Resolved server directory escapes the isolated runtime root')


def download(url, path, expected=None):
    request = urllib.request.Request(url, headers={'User-Agent': 'DropsOverhaul-validation/1.0'})
    temporary = path.with_suffix('.download')
    try:
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise Unavailable('No distribution at ' + url) from error
        raise
    actual = hashlib.sha256(temporary.read_bytes()).hexdigest()
    if expected and actual != expected:
        raise RuntimeError('SHA-256 mismatch downloading ' + url)
    temporary.replace(path)
    return actual


def install(target, java):
    validate_target(target)
    directory = ROOT / 'build/runtime' / target['id']
    directory.mkdir(parents=True, exist_ok=True)
    receipt = directory / 'installation.json'
    identity = {key: target.get(key) for key in ('platform', 'minecraft', 'forgeBuild', 'serverRevision')}
    if receipt.is_file():
        previous = json.loads(receipt.read_text())
        if target['platform'] == 'forge' and not target.get('forgeBuild'):
            target['forgeBuild'] = previous['identity'].get('forgeBuild')
            identity['forgeBuild'] = target['forgeBuild']
        if all(previous['identity'].get(key) == value for key, value in identity.items()) and all((directory / name).is_file() for name in previous['launchFiles']):
            print('Reusing matching isolated installation:', directory)
            return
        raise RuntimeError('Conflicting test installation; use a fresh target ID or archive ' + str(directory))
    log_path = directory / 'installer.log'
    result = {'target': target['id'], 'state': 'blocked', 'stage': 'installation', **identity}
    compiled = ROOT / 'build/validation' / (target['id'] + '-compiled.json')
    if compiled.is_file():
        build = json.loads(compiled.read_text())
        result.update(compiled=True, artifact=build['artifact'], sha256=build['sha256'])
    try:
        kind = target['platform']
        version = target['minecraft']
        if kind == 'paper':
            builds = fetch(f'https://fill.papermc.io/v3/projects/paper/versions/{version}/builds')
            if not builds:
                raise Unavailable('No Paper builds for ' + version)
            build = builds[0]
            item = build['downloads']['server:default']
            digest = download(item['url'], directory / 'server.jar', item['checksums']['sha256'])
            (directory / 'download.json').write_text(json.dumps(build, indent=2) + '\n')
            launch_files = ['server.jar']
        elif kind == 'spigot':
            revision = target.get('serverRevision') or version
            try:
                descriptor = fetch(f'https://hub.spigotmc.org/versions/{revision}.json')
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    raise Unavailable('No BuildTools descriptor for exact Minecraft ' + version) from error
                raise
            (directory / 'buildtools-version.json').write_text(json.dumps(descriptor, indent=2) + '\n')
            # Generated BuildTools checkouts are reused sequentially across exact
            # versions. Never point this cache at a maintained source checkout.
            work = ROOT / 'build/installers/spigot-cache'
            work.mkdir(parents=True, exist_ok=True)
            lock = work / '.buildtools.lock'
            try:
                with lock.open('x') as output:
                    output.write(str(os.getpid()))
            except FileExistsError as error:
                raise RuntimeError('BuildTools cache is locked; inspect the owning process before clearing ' + str(lock)) from error
            try:
                digest = download('https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar', work / 'BuildTools.jar')
                env = dict(os.environ, GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='commit.gpgsign', GIT_CONFIG_VALUE_0='false')
                with log_path.open('w') as log:
                    subprocess.run([java, '-jar', 'BuildTools.jar', '--rev', revision, '--compile', 'SPIGOT'],
                                   cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=3600)
                info = json.loads((work / 'BuildData/info.json').read_text())
                # BuildData uses this explicit tooling suffix for the official
                # unobfuscated 1.21.11 source variant, not a different patch.
                actual = info['minecraftVersion']
                if actual == version + '_unobfuscated':
                    actual = version
                if actual != version:
                    raise Unavailable(f"BuildTools revision {revision} resolves to Minecraft {info['minecraftVersion']}, not {version}; pin an exact historical serverRevision")
                shutil.copy2(work / f'spigot-{version}.jar', directory / 'server.jar')
            finally:
                lock.unlink()
            launch_files = ['server.jar']
        elif kind == 'forge':
            forge = target.get('forgeBuild')
            if not forge:
                promos = fetch('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json')['promos']
                forge = promos.get(version + '-latest') or promos.get(version + '-recommended')
            if not forge:
                raise Unavailable('No exact Forge build advertised for ' + version + '; provide forge_build if Maven lists one')
            target['forgeBuild'] = forge
            identity['forgeBuild'] = forge
            result['forgeBuild'] = forge
            coordinate = version + '-' + forge
            digest = download(f'https://maven.minecraftforge.net/net/minecraftforge/forge/{coordinate}/forge-{coordinate}-installer.jar', directory / 'forge-installer.jar')
            with log_path.open('w') as log:
                subprocess.run([java, '-jar', 'forge-installer.jar', '--installServer'], cwd=directory,
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
            argsfile = f"libraries/net/minecraftforge/forge/{coordinate}/{'win' if os.name == 'nt' else 'unix'}_args.txt"
            launch_files = [argsfile] if (directory / argsfile).is_file() else [f'forge-{coordinate}.jar']
        else:
            raise ValueError('Unsupported native platform: ' + kind)
        if not all((directory / name).is_file() for name in launch_files):
            raise RuntimeError('Installer exited without producing the expected launch files')
        receipt.write_text(json.dumps({'identity': identity, 'launchFiles': launch_files, 'downloadSha256': digest}, indent=2) + '\n')
        print('Installed:', identity)
    except Exception as error:
        result.update(state='blocked' if isinstance(error, Unavailable) else 'failed', error=str(error), log=str(log_path.relative_to(ROOT)))
        (directory / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--target')
    parser.add_argument('--spec', type=Path)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    target = json.loads(args.spec.read_text()) if args.spec else next(t for t in load()['artifacts'] if t['id'] == args.target)
    install(target, args.java)
    if args.spec:
        args.spec.write_text(json.dumps(target, indent=2) + '\n')
