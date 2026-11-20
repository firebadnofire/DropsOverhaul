"""Emit an exact, manual-only test inventory from versions.json."""
import argparse
import json
from targets import ROOT, load


def matrix(tier):
    manifest = load()
    artifacts = {t['id']: t for t in manifest['artifacts']}
    rows = []
    for candidate in manifest['candidates']:
        if tier == 1 and candidate['tier'] != 1:
            continue
        target = artifacts.get(candidate.get('artifactId'))
        rows.append(dict(candidate, state=target['state'] if target else candidate['state'],
                         id=target['id'] if target else candidate['platform'] + '-' + candidate['minecraft'],
                         implemented=bool(target), buildJava=target['buildJava'] if target else 21,
                         compilerJava=target.get('compilerJava', target['buildJava']) if target else 21,
                         serverJava=target['serverJava'] if target else 21,
                         directory=target['buildDirectory'] if target else '.',
                         launcher=target.get('buildLauncher', 'gradlew') if target else 'gradlew',
                         task=target['buildTask'] if target else 'build',
                         externalFixture=target.get('externalFixture', False) if target else False))
    return {'include': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tier', type=int, choices=[1, 2], default=1)
    parser.add_argument('--blocked')
    args = parser.parse_args()
    result = matrix(args.tier)
    if args.blocked:
        row = next(t for t in result['include'] if t['id'] == args.blocked)
        destination = ROOT / 'build/runtime' / row['id']
        destination.mkdir(parents=True, exist_ok=True)
        reason = row.get('reason') or 'No implemented artifact is mapped to this exact candidate; compatibility remains unverified'
        result = dict(target=row['id'], state='blocked', startup=False, gameplay=False, stage='selection', reason=reason)
        (destination / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('::warning::BLOCKED ' + row['id'] + ': ' + reason)
    print(json.dumps(result, separators=(',', ':')))
