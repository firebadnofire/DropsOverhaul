"""Compile-only CI inventory and staging; never starts servers or enables releases."""
import argparse
import json
from pathlib import Path
import re
import shutil
import os
import subprocess
import tomllib
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from check_artifact import inspect
from targets import ROOT, load


def plan(manifest):
    targets = {t['id']: t for t in manifest['artifacts']}
    groups = {}
    for target in targets.values():
        source = target
        seen = set()
        while source.get('ciBinarySource') or source.get('binarySource'):
            if source['id'] in seen:
                raise ValueError('Cyclic binary source: ' + target['id'])
            seen.add(source['id'])
            source = targets[source.get('ciBinarySource') or source['binarySource']]
        if (target['platform'] == 'forge') != (source['platform'] == 'forge'):
            raise ValueError('Cannot merge Bukkit and Forge: ' + target['id'])
        if target['platform'] == 'forge' and target['minecraft'] != source['minecraft'] and not target.get('ciBinarySource'):
            raise ValueError('Forge consolidation must be explicit: ' + target['id'])
        if target.get('ciBinarySource') and target['platform'] != 'forge' and not target.get('ciEvidence'):
            raise ValueError('CI consolidation needs runtime evidence: ' + target['id'])
        group = groups.setdefault(source['id'], dict(
            source=source['id'], targets=[], directory=source['buildDirectory'],
            launcher=source.get('buildLauncher', 'gradlew'), task=source['buildTask'],
            buildJava=source['buildJava'], compilerJava=source.get('compilerJava', source['buildJava']),
            artifact=source.get('ciArtifact', source['artifact']).format(version=manifest['projectVersion'])))
        if not re.fullmatch(r'[A-Za-z0-9_.-]+\.jar', group['artifact']):
            raise ValueError('Invalid distribution filename: ' + group['artifact'])
        group['targets'].append(target['id'])
        if (group['buildJava'], group['compilerJava']) != (target['buildJava'], target.get('compilerJava', target['buildJava'])) and target['platform'] == 'forge':
            raise ValueError('Cannot consolidate different Forge toolchains: ' + target['id'])
    for group in groups.values():
        group['mergeForge'] = targets[group['source']]['platform'] == 'forge' and len(group['targets']) > 1
        group['buildSources'] = group['targets'] if group['mergeForge'] else [group['source']]
    filenames = [g['artifact'] for g in groups.values()]
    if len(filenames) != len(set(filenames)):
        raise ValueError('Distribution filenames collide')
    return {'include': list(groups.values())}


def artifact_path(target, manifest):
    return ROOT / target['artifactDirectory'] / target['artifact'].format(version=manifest['projectVersion'])


def table(manifest):
    lines = ['# Compilation-only CI distributions', '',
             'Generated from `versions.json` with `python tools/distributions.py table`.', '',
             'Each row is one individually downloadable artifact from **Compile Paper and Forge distributions**. No server starts, signing, or releases occur in this workflow.', '',
             'Range filenames show target endpoints, not verification of every intermediate patch or every Paper/Spigot pairing. Exact runtime status remains in [SUPPORT.md](SUPPORT.md) and each artifact includes `validation.json` with its current SHA-256 and matching prior evidence.', '',
             '| JAR | Exact configured targets | Build / compiler Java |', '| --- | --- | --- |']
    for group in plan(manifest)['include']:
        lines.append(f"| `{group['artifact']}` | {', '.join(group['targets'])} | {group['buildJava']} / {group['compilerJava']} |")
    evidence_rows = []
    for target in manifest['artifacts']:
        if target.get('ciEvidence'):
            evidence = json.loads((ROOT / target['ciEvidence']).read_text(encoding='utf-8'))
            detail = evidence.get('error', '') or evidence.get('gameplayBlocked', '') or ('; '.join(w['scope'] for w in evidence.get('clientWarnings', [])))
            evidence_rows.append(f"| {target['id']} | {evidence['state']} | [{target['ciEvidence']}]({target['ciEvidence']}) | {detail} |")
    lines += ['', 'Recorded tests of the consolidated JARs are listed below. These results apply to the SHA-256 in each evidence file; a fresh CI build claims a prior runtime result only when its bytes match. Standalone JAR results in SUPPORT.md do not automatically validate a merged JAR.', '',
              '| Target | Consolidated JAR validation | Evidence | Limitations / failure |', '| --- | --- | --- | --- |'] + evidence_rows
    lines += ['', 'The inventory artifact also lists advertised exact candidates with no implemented build recipe. Those remain unimplemented, including Forge versions for which no native distribution exists.', '',
              'Shared Forge JARs are allowed only when every member recipe compiles and all non-loader-metadata payload entries match byte for byte. Their loader metadata declares exact Minecraft and Forge-build sets. A payload mismatch fails that group and requires separate JARs; other groups continue.', '',
              'The standalone Paper build and existing signed release workflow remain unchanged. CI consolidation affects downloadable build artifacts only.', '']
    return '\n'.join(lines)


def payload(path):
    with ZipFile(path) as jar:
        if len(jar.namelist()) != len(set(jar.namelist())):
            raise ValueError('Duplicate ZIP entries: ' + str(path))
        return {name: jar.read(name) for name in jar.namelist() if name != 'META-INF/mods.toml'}


def merge_forge(group, targets, manifest, destination):
    source_path = artifact_path(targets[group['source']], manifest)
    expected_payload = payload(source_path)
    for target_id in group['targets']:
        member = artifact_path(targets[target_id], manifest)
        inspect(target_id, member)
        if payload(member) != expected_payload:
            raise ValueError('Native payload differs; split this Forge CI group: ' + target_id)
    ordered = lambda values: sorted(set(values), key=lambda v: tuple(map(int, v.split('.'))))
    versions = ordered(targets[t]['minecraft'] for t in group['targets'])
    forge_versions = ordered(targets[t]['forgeBuild'] for t in group['targets'])
    with ZipFile(source_path) as jar:
        text = jar.read('META-INF/mods.toml').decode('utf-8')
    dependencies = {d['modId']: d for d in tomllib.loads(text)['dependencies'][targets[group['source']]['expectedId']]}
    for key, values in [('minecraft', versions), ('forge', forge_versions)]:
        old = 'versionRange="' + dependencies[key]['versionRange'] + '"'
        new = 'versionRange="' + ','.join('[' + v + ']' for v in values) + '"'
        if text.count(old) != 1:
            raise ValueError('Cannot update native dependency metadata: ' + key)
        text = text.replace(old, new)
    entries = dict(expected_payload, **{'META-INF/mods.toml': text.encode('utf-8')})
    with ZipFile(destination, 'w', compression=ZIP_DEFLATED) as jar:
        for name, data in sorted(entries.items()):
            entry = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            jar.writestr(entry, data)
    return versions, forge_versions


def stage(source_id):
    manifest = load()
    group = next(g for g in plan(manifest)['include'] if g['source'] == source_id)
    targets = {t['id']: t for t in manifest['artifacts']}
    source = targets[source_id]
    artifact = artifact_path(source, manifest)
    destination = ROOT / 'build/ci-distributions' / source_id
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / group['artifact']
    pending = destination / (group['artifact'] + '.pending')
    inspect(source_id)
    versions = forge_versions = None
    if group['mergeForge']:
        versions, forge_versions = merge_forge(group, targets, manifest, pending)
    else:
        shutil.copyfile(artifact, pending)
    inspected = inspect(source_id, pending, minecraft_versions=versions, forge_versions=forge_versions)
    coverage = []
    for target_id in group['targets']:
        target = targets[target_id]
        inspect(target_id, pending, minecraft_versions=versions, forge_versions=forge_versions)
        paths = [target['ciEvidence']] if target.get('ciEvidence') else target.get('evidence', [])
        previous = json.loads((ROOT / paths[0]).read_text(encoding='utf-8')) if paths else {}
        if target.get('ciBinarySource') and target['platform'] != 'forge':
            source_evidence = json.loads((ROOT / source['evidence'][0]).read_text(encoding='utf-8'))
            if (previous.get('target') != target_id or previous.get('state') != 'gameplay-tested'
                    or not previous.get('startup') or not previous.get('gameplay')
                    or previous.get('serverExitCode') != 0
                    or previous.get('minecraft') != target['minecraft']
                    or previous.get('platform') != target['platform']
                    or previous.get('serverJava') != target['serverJava']
                    or previous.get('sha256') != source_evidence.get('sha256')
                    or source_evidence.get('state') != 'gameplay-tested'):
                raise ValueError('Consolidation lacks successful native runtime evidence: ' + target_id)
        matches = previous.get('sha256') == inspected['sha256']
        if matches and (previous.get('startup') or previous.get('gameplay')):
            expected = dict(target=target_id, platform=target['platform'], minecraft=target['minecraft'],
                            serverJava=target['serverJava'])
            if target['platform'] == 'forge':
                expected['forgeBuild'] = target['forgeBuild']
            if any(previous.get(key) != value for key, value in expected.items()):
                raise ValueError('Runtime evidence belongs to another target: ' + target_id)
            if previous.get('state') in ('startup-tested', 'gameplay-tested') and previous.get('serverExitCode') != 0:
                raise ValueError('Runtime evidence lacks clean shutdown: ' + target_id)
            if previous.get('state') == 'gameplay-tested' and not (previous.get('startup') and previous.get('gameplay')):
                raise ValueError('Runtime evidence lacks gameplay assertions: ' + target_id)
        coverage.append(dict(target=target_id, platform=target['platform'], minecraft=target['minecraft'],
                             state=previous.get('state', 'compiled') if matches else 'compiled',
                             priorEvidence=paths, priorEvidenceMatchesArtifact=matches,
                             startup=bool(matches and previous.get('startup')),
                             gameplay=bool(matches and previous.get('gameplay')),
                             validationError=previous.get('error') if matches else None,
                             limitations=target.get('limitations', [])))
    result = dict(artifact=group['artifact'], sha256=inspected['sha256'], state='compiled',
                  source=source_id, buildJava=group['buildJava'], compilerJava=group['compilerJava'],
                  runtimeTestsExecuted=False, releaseEligible=False, coverage=coverage,
                  note='Compilation only. Filename range endpoints do not verify intermediate patches or every platform/version pairing. Consult exact coverage and prior evidence matches.')
    report = destination / 'validation.json.pending'
    report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    pending.replace(output)
    report.replace(destination / 'validation.json')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['plan', 'stage', 'build', 'failure', 'table'])
    parser.add_argument('--source')
    parser.add_argument('--blocked', action='store_true')
    args = parser.parse_args()
    manifest = load()
    if args.action == 'table':
        text = table(manifest)
        (ROOT / 'CI-DISTRIBUTIONS.md').write_text(text, encoding='utf-8')
        print(text, end='')
        return
    if args.action == 'failure':
        if not args.source:
            parser.error('failure requires --source')
        group = next(g for g in plan(manifest)['include'] if g['source'] == args.source)
        directory = ROOT / 'build/ci-distributions' / args.source
        directory.mkdir(parents=True, exist_ok=True)
        output = dict(artifact=group['artifact'], state='blocked' if args.blocked else 'failed',
                      targets=group['targets'], runtimeTestsExecuted=False, releaseEligible=False,
                      stage='toolchain' if args.blocked else 'compilation-or-validation',
                      reason='Required JDK setup failed; inspect setup-java logs.' if args.blocked else
                             'Compilation or artifact validation failed; inspect the failed step and compile.log.')
        (directory / 'validation.json').write_text(json.dumps(output, indent=2) + '\n', encoding='utf-8')
    elif args.action in ('stage', 'build'):
        if not args.source:
            parser.error(args.action + ' requires --source')
        if args.action == 'build':
            group = next(g for g in plan(manifest)['include'] if g['source'] == args.source)
            targets = {t['id']: t for t in manifest['artifacts']}
            for target_id in group['buildSources']:
                target = targets[target_id]
                launcher = ROOT / target.get('buildLauncher', 'gradlew')
                command = [str(launcher) + '.bat'] if os.name == 'nt' else ['bash', str(launcher)]
                print('Compiling ' + target_id, flush=True)
                subprocess.run(command + ['-p', str(ROOT / target['buildDirectory']), target['buildTask'],
                                          '-PtargetId=' + target_id, '--no-daemon'], cwd=ROOT, check=True)
        output = stage(args.source)
    else:
        output = plan(manifest)
        inventory = dict(output, unimplemented=[c for c in manifest['candidates'] if not c.get('artifactId')],
                         note='Every implemented recipe is compiled; unmapped advertised candidates remain unimplemented. No runtime compatibility or release eligibility is inferred.')
        directory = ROOT / 'build'
        directory.mkdir(exist_ok=True)
        (directory / 'ci-distribution-inventory.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(output, separators=(',', ':')))


if __name__ == '__main__':
    main()
