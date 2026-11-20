"""Resolve manual CI inputs without treating user input as shell code."""
import json
import os
from pathlib import Path, PureWindowsPath, PurePosixPath
import re
from targets import load


def select(env):
    manifest = load()
    platform = env['SERVER_TYPE']
    if platform not in ('paper', 'spigot', 'forge'):
        raise ValueError('Unsupported server_type')
    version = env.get('MC_VERSION', 'auto')
    if version == 'auto':
        version = manifest['artifacts'][0]['minecraft']
    if not re.fullmatch(r'\d{1,2}\.\d{1,2}(?:\.\d{1,2})?', version):
        raise ValueError('Invalid exact Minecraft version')
    target = next((t for t in manifest['artifacts'] if t['platform'] == platform and t['minecraft'] == version), None)
    candidate = next((t for t in manifest['candidates'] if t['platform'] == platform and t['minecraft'] == version), {})
    task = env.get('BUILD_TASK', 'auto')
    artifact = env.get('ARTIFACT_PATH', '').strip()
    if target:
        task = target['buildTask'] if task == 'auto' else task
        artifact = artifact or str(Path(target['artifactDirectory']) / target['artifact'].format(version=manifest['projectVersion']))
        build_java = target['buildJava']
        server_java = target['serverJava']
        directory = target['buildDirectory']
        identity = target['expectedId']
    else:
        if task == 'auto' or not artifact:
            raise ValueError('Target is planned/unimplemented: provide explicit build_task and artifact_path for an exploratory startup-only test')
        if env.get('REQUESTED_BUILD_JAVA', 'auto') == 'auto' or env.get('REQUESTED_SERVER_JAVA', 'auto') == 'auto':
            raise ValueError('Exploratory targets require explicit build_java and server_java')
        build_java = server_java = None
        directory = '.'
        identity = ''
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9:_-]*', task):
        raise ValueError('build_task must name one Gradle task')
    timeout = int(env.get('STARTUP_TIMEOUT', '600'))
    if not 30 <= timeout <= 2400:
        raise ValueError('startup_timeout must be 30..2400 seconds')
    forge = env.get('FORGE_BUILD', '') or (target or candidate).get('forgeBuild', '')
    if forge and not re.fullmatch(r'\d+(?:\.\d+)*', forge):
        raise ValueError('Invalid Forge build')
    for value in [artifact, directory]:
        if PurePosixPath(value).is_absolute() or PureWindowsPath(value).root or PureWindowsPath(value).drive or '..' in PureWindowsPath(value).parts or '\n' in value or '\r' in value:
            raise ValueError('Paths must stay within the checkout')
    if env.get('EXPECTED_ID') and not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]*', env['EXPECTED_ID']):
        raise ValueError('Invalid plugin/mod identity')
    result = dict(MC_VERSION=version, BUILD_TASK=task, ARTIFACT_PATH=artifact, BUILD_DIRECTORY=directory,
                  EXPECTED_ID=env.get('EXPECTED_ID', '') or identity, FORGE_BUILD=forge,
                  SERVER_REVISION=(target or candidate).get('serverRevision', ''),
                  TARGET_ID=target['id'] if target else '', VALIDATION_MODE=target.get('validationMode', 'gameplay') if target else 'startup-only',
                  EXTERNAL_FIXTURE=str((target or {}).get('externalFixture', False)).lower(),
                  BUILD_LAUNCHER=(target or {}).get('buildLauncher', 'gradlew'),
                  COMPILER_JAVA=str((target or {}).get('compilerJava', build_java or env.get('REQUESTED_BUILD_JAVA'))))
    for key, fallback in [('BUILD_JAVA', build_java), ('SERVER_JAVA', server_java)]:
        requested = env.get('REQUESTED_' + key, 'auto')
        value = str(fallback) if requested == 'auto' else requested
        if value not in ('8', '16', '17', '21', '25'):
            raise ValueError('Unsupported JDK: ' + value)
        result[key] = value
    for value in result.values():
        if '\n' in value or '\r' in value:
            raise ValueError('Multiline input rejected')
    return result


if __name__ == '__main__':
    result = select(os.environ)
    root = Path(__file__).resolve().parents[1]
    spec = dict(id=result['TARGET_ID'] or 'exploratory-' + os.environ['SERVER_TYPE'] + '-' + result['MC_VERSION'],
                platform=os.environ['SERVER_TYPE'], minecraft=result['MC_VERSION'],
                forgeBuild=result['FORGE_BUILD'] or None, expectedId=result['EXPECTED_ID'],
                serverRevision=result['SERVER_REVISION'] or None,
                serverJava=int(result['SERVER_JAVA']))
    (root / 'build').mkdir(exist_ok=True)
    (root / 'build/ci-target.json').write_text(json.dumps(spec, indent=2) + '\n')
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
        for key, value in result.items():
            output.write(f'{key}={value}\n')
    print(json.dumps(result, indent=2))
