"""Inspect the actual manifest-named JAR, including bytecode and loader isolation."""
import argparse
import hashlib
import json
import struct
from zipfile import ZipFile
from targets import ROOT, load


def inspect(target_id, artifact_path=None, *, minecraft_versions=None, forge_versions=None):
    manifest = load()
    target = next(t for t in manifest['artifacts'] if t['id'] == target_id)
    path = artifact_path or ROOT / target['artifactDirectory'] / target['artifact'].format(version=manifest['projectVersion'])
    maximum = int(target.get('compilerJava', target['buildJava'])) + 44
    with ZipFile(path) as jar:
        names = jar.namelist()
        for name in names:
            if name.endswith('.class'):
                if name.startswith(('org/bukkit/', 'net/minecraft/', 'net/minecraftforge/')):
                    raise ValueError('Server API bundled in distribution: ' + name)
                bytecode = jar.read(name)
                forbidden = b'org/bukkit/' if target['platform'] == 'forge' else b'net/minecraftforge/'
                if forbidden in bytecode:
                    raise ValueError('Cross-platform API reference in ' + name)
                major = struct.unpack('>H', bytecode[6:8])[0]
                if major > maximum:
                    raise ValueError(f'{name}: bytecode {major} exceeds target {maximum}')
        if target['platform'] in ('paper', 'spigot'):
            metadata = jar.read('plugin.yml').decode()
            if 'name: DropsOverhaul' not in metadata or manifest['projectVersion'] not in metadata:
                raise ValueError('Wrong plugin identity/version')
            if target['minecraft'] == '1.12.2' and 'api-version:' in metadata:
                raise ValueError('Legacy descriptor contains modern API declaration')
            if any(n in names for n in ('mcmod.info', 'META-INF/mods.toml')):
                raise ValueError('Forge metadata in Bukkit distribution')
        elif 'plugin.yml' in names:
            raise ValueError('Bukkit plugin descriptor in native Forge distribution')
        elif target['minecraft'] == '1.12.2':
            metadata = json.loads(jar.read('mcmod.info'))
            if metadata[0]['modid'] != target['expectedId'] or metadata[0]['mcversion'] != target['minecraft']:
                raise ValueError('Wrong legacy Forge metadata')
        else:
            import tomllib
            metadata = tomllib.loads(jar.read('META-INF/mods.toml').decode())
            if metadata['mods'][0]['modId'] != target['expectedId'] or metadata['mods'][0]['version'] != manifest['projectVersion']:
                raise ValueError('Wrong Forge mod identity/version')
            dependencies = {entry['modId']: entry for entry in metadata['dependencies'][target['expectedId']]}
            minecraft_range = ','.join('[' + v + ']' for v in minecraft_versions) if minecraft_versions else '[' + target['minecraft'] + ']'
            if dependencies['minecraft']['versionRange'] != minecraft_range:
                raise ValueError('Forge metadata does not require the exact manifest Minecraft version')
            forge_range = ('[' + target['forgeBuild'] + ']' if target.get('exactForgeBuild') else
                           '[' + target['forgeBuild'] + ',' + str(int(target['forgeBuild'].split('.')[0]) + 1) + ')')
            if forge_versions:
                forge_range = ','.join('[' + v + ']' for v in forge_versions)
            if dependencies['forge']['versionRange'] != forge_range:
                raise ValueError('Forge metadata does not match the manifest Forge build range')
    return {'target': target_id, 'state': 'compiled', 'compiled': True, 'artifact': path.name,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'metadata': 'valid',
            'maximumClassVersion': maximum}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', required=True)
    args = parser.parse_args()
    result = inspect(args.target)
    directory = ROOT / 'build/validation'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / (args.target + '-compiled.json')).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
