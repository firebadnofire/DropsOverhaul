"""Manifest-backed target selection, discovery and compatibility documentation.

Discovery never promotes support or modifies the manifest. The output is a
candidate test inventory, not a list of compatible versions.
"""
import argparse
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def load():
    manifest = json.loads((ROOT / 'versions.json').read_text())
    ids = set()
    for target in manifest['artifacts']:
        if target['id'] in ids:
            raise ValueError('Duplicate target: ' + target['id'])
        ids.add(target['id'])
        if target.get('validationMode', 'gameplay') not in ('gameplay', 'startup-only'):
            raise ValueError('Unknown validation mode: ' + target['id'])
        if target.get('validationMode') == 'startup-only' and not target.get('gameplayBlocker'):
            raise ValueError('Startup-only target needs an explicit gameplay blocker: ' + target['id'])
        if target['state'] not in manifest['validationStates']:
            raise ValueError('Unknown validation state: ' + target['state'])
        if target.get('minecraftMappings', 'official') not in ('official', 'none'):
            raise ValueError('Unknown Minecraft mappings layout: ' + target['id'])
        if target.get('forgeGradle') and not re.fullmatch(r'\d+\.\d+\.\d+', target['forgeGradle']):
            raise ValueError('ForgeGradle must be an exact version: ' + target['id'])
        if target.get('forgeEventBus') == 7 and target.get('overflowApi') not in ('legacy', 'prediction'):
            raise ValueError('Unknown native overflow API: ' + target['id'])
        if target['releaseEligible'] and target['id'] != 'paper-1.21.11' and target['state'] != 'gameplay-tested':
            raise ValueError('Unvalidated release target: ' + target['id'])
        if Path(target['artifact']).name != target['artifact']:
            raise ValueError('Artifact must be a basename')
    artifacts = {t['id']: t for t in manifest['artifacts']}
    exact = set()
    for row in manifest['candidates']:
        key = (row['platform'], row['minecraft'])
        if key in exact:
            raise ValueError('Duplicate exact candidate: ' + str(key))
        exact.add(key)
        if row.get('artifactId'):
            target = artifacts.get(row['artifactId'])
            if target is None or (target['platform'], target['minecraft']) != key:
                raise ValueError('Candidate does not map to an exact artifact: ' + str(key))
        elif row.get('state') not in manifest['validationStates']:
            raise ValueError('Planned candidate needs an explicit validation state: ' + str(key))
    return manifest


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'DropsOverhaul-compatibility-audit/1.0'})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def discover(manifest):
    mojang_url = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
    forge_url = 'https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json'
    versions = fetch(mojang_url)['versions']
    promos = fetch(forge_url)['promos']
    maven_url = 'https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml'
    request = urllib.request.Request(maven_url, headers={'User-Agent': 'DropsOverhaul-validation/1.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        forge_versions = [node.text for node in ET.fromstring(response.read()).findall('./versioning/versions/version')]
    groups = manifest['versionGroups']
    releases = sorted({v['id'] for v in versions if v['type'] == 'release' and
                       any(v['id'] == g or v['id'].startswith(g + '.') for g in groups)},
                      key=lambda v: tuple(map(int, v.split('.'))))
    tier1 = set(manifest['tier1Boundaries'])
    for group in groups:
        matches = [v for v in releases if v == group or v.startswith(group + '.')]
        if matches:
            tier1.update((matches[0], matches[-1]))
    releases = sorted(set(releases) | {'1.12.2'}, key=lambda v: tuple(map(int, v.split('.'))))
    rows = []
    for version in releases:
        # Spigot's authoritative BuildTools version descriptor may be absent.
        url = f'https://hub.spigotmc.org/versions/{version}.json'
        try:
            descriptor = fetch(url)
            spigot = {'available': True, 'descriptor': url, 'javaVersions': descriptor.get('javaVersions')}
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            spigot = {'available': False, 'reason': 'BuildTools descriptor returned HTTP 404'}
        forge = promos.get(version + '-recommended') or promos.get(version + '-latest')
        available = [v[len(version) + 1:] for v in forge_versions if v.startswith(version + '-')]
        if not forge and available:
            forge = available[-1]
        rows.append({'minecraft': version, 'tier': 1 if version in tier1 else 2,
                     'spigot': spigot, 'forge': {'build': forge, 'available': bool(available),
                                              'reason': None if available else 'No exact Minecraft version in Forge Maven metadata'},
                     'state': 'untested'})
    return {'sources': [mojang_url, forge_url, maven_url], 'warning': 'Availability is not compatibility; all candidates are untested.',
            'targets': rows}


def table(manifest):
    lines = ['# Exact target validation', '', 'Generated by `python tools/targets.py table --output SUPPORT.md` from `versions.json`.',
             'A gameplay-tested state covers the scenarios in the linked evidence for that exact JAR SHA-256. It does not establish compatibility with other patches or arbitrary plugins/mods.', '',
             '| Target | Artifact | Forge build | Build / compiler / server Java | Validation | Compiled / startup / gameplay | Release eligible |',
             '| --- | --- | --- | --- | --- | --- | --- |']
    for t in manifest['artifacts']:
        evidence = json.loads((ROOT / t['evidence'][0]).read_text()) if t.get('evidence') else {}
        state = f"[{t['state']}]({t['evidence'][0]})" if t.get('evidence') else t['state']
        lines.append(f"| {t['id']} | `{t['artifact'].format(version=manifest['projectVersion'])}` | {t.get('forgeBuild', '—')} | "
                     f"{t['buildJava']} / {t.get('compilerJava', t['buildJava'])} / {t['serverJava']} | {state} | "
                     f"{evidence.get('compiled', bool(evidence.get('sha256')))} / {evidence.get('startup', False)} / {evidence.get('gameplay', False)} | {t['releaseEligible']} |")
    lines += ['', '## Build commands', '', '| Target | Command (set JAVA_HOME to build Java first) |', '| --- | --- |']
    for t in manifest['artifacts']:
        lines.append(f"| {t['id']} | `bash {t.get('buildLauncher', 'gradlew')} -p {t['buildDirectory']} {t['buildTask']} -PtargetId={t['id']} --no-daemon` |")
    lines += ['', '## Known limitations', '']
    for t in manifest['artifacts']:
        for limitation in t.get('limitations', []):
            lines.append(f"- **{t['id']}**: {limitation}")
    lines += ['', '## Exact candidate inventory', '', 'Tier 1 selects group boundaries and API/Java boundaries. Tier 2 includes every exact candidate and runs only when requested. An available installer is not proof of support.', '',
              '| Platform | Minecraft | Tier | Artifact mapping | Validation |', '| --- | --- | --- | --- | --- |']
    artifacts = {t['id']: t for t in manifest['artifacts']}
    for c in manifest['candidates']:
        t = artifacts.get(c.get('artifactId'))
        state = t['state'] if t else c['state']
        reason = '' if t or not c.get('reason') else ': ' + c['reason']
        lines.append(f"| {c['platform']} | {c['minecraft']} | {c['tier']} | {c.get('artifactId') or 'planned; none'} | {state}{reason} |")
    for t in manifest['planned']:
        lines.append(f"| {t['platform']} | {t['minecraft']} | — | planned; none | {t['state']} |")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['validate', 'select', 'discover', 'table'])
    parser.add_argument('--platform')
    parser.add_argument('--minecraft')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    manifest = load()
    if args.action == 'validate':
        output = 'Manifest valid\n'
    elif args.action == 'table':
        output = table(manifest)
    elif args.action == 'discover':
        output = json.dumps(discover(manifest), indent=2) + '\n'
    else:
        matches = [t for t in manifest['artifacts'] if t['platform'] == args.platform and t['minecraft'] == args.minecraft]
        if len(matches) != 1:
            raise SystemExit('No implemented manifest target for this exact platform/version; supply an explicit artifact and build task for exploratory testing')
        output = json.dumps(matches[0], indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding='utf-8')
    else:
        print(output, end='')


if __name__ == '__main__':
    main()
