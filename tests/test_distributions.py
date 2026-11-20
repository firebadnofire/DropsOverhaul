"""Guard CI consolidation and native loader/version boundaries."""
from copy import deepcopy
import sys
import json
import hashlib
from pathlib import Path
import unittest
from unittest.mock import patch
from tempfile import TemporaryDirectory
from zipfile import ZipFile
import tomllib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from distributions import plan, merge_forge, collect
from targets import load


class DistributionPlanTests(unittest.TestCase):
    def setUp(self):
        self.manifest = deepcopy(load())
        self.targets = {t['id']: t for t in self.manifest['artifacts']}

    def test_every_recipe_appears_once_and_bukkit_is_shared(self):
        groups = plan(self.manifest)['include']
        covered = [target for group in groups for target in group['targets']]
        self.assertCountEqual(covered, self.targets)
        self.assertEqual(len(covered), len(set(covered)))
        shared = next(g for g in groups if g['source'] == 'spigot-1.19')
        self.assertIn('paper-1.21.11', shared['targets'])
        self.assertIn('spigot-26.3', shared['targets'])
        self.assertNotIn('spigot-1.12.2', shared['targets'])
        self.assertEqual(len(groups), 9)
        native = next(g for g in groups if g['source'] == 'forge-1.19')
        self.assertCountEqual(native['buildSources'], ['forge-1.19', 'forge-1.19.1', 'forge-1.19.2', 'forge-1.19.3', 'forge-1.19.4'])

    def test_cycles_fail_instead_of_hanging(self):
        self.targets['spigot-1.19']['binarySource'] = 'spigot-1.19.4'
        with self.assertRaisesRegex(ValueError, 'Cyclic'):
            plan(self.manifest)

    def test_forge_is_never_combined_with_bukkit(self):
        self.targets['forge-1.12.2']['binarySource'] = 'spigot-1.19'
        with self.assertRaisesRegex(ValueError, 'Bukkit and Forge'):
            plan(self.manifest)

    def test_forge_versions_are_not_inferred_compatible(self):
        self.targets['forge-1.19.4'].pop('ciBinarySource', None)
        self.targets['forge-1.19.4']['binarySource'] = 'forge-1.19'
        with self.assertRaisesRegex(ValueError, 'explicit'):
            plan(self.manifest)

    def test_cross_platform_consolidation_requires_evidence(self):
        del self.targets['paper-1.21.11']['ciEvidence']
        with self.assertRaisesRegex(ValueError, 'runtime evidence'):
            plan(self.manifest)

    def test_native_merge_uses_exact_version_unions_and_is_repeatable(self):
        self.check_native_merge(False)

    def test_native_merge_rejects_changed_payload(self):
        self.check_native_merge(True)

    def check_native_merge(self, changed):
        group = dict(source='forge-1.19', targets=['forge-1.19', 'forge-1.19.4'])
        metadata = ('[[dependencies.dropsoverhaul]]\nmodId="forge"\nversionRange="[41.1.0,42)"\n'
                    '[[dependencies.dropsoverhaul]]\nmodId="minecraft"\nversionRange="[1.19]"\n')
        with TemporaryDirectory() as directory:
            paths = {target: Path(directory) / (target + '.jar') for target in group['targets']}
            for target, path in paths.items():
                with ZipFile(path, 'w') as jar:
                    jar.writestr('META-INF/mods.toml', metadata)
                    jar.writestr('native.class', b'changed' if changed and target == 'forge-1.19.4' else b'same')
            output = Path(directory) / 'merged.jar'
            with patch('distributions.artifact_path', side_effect=lambda t, m: paths[t['id']]), patch('distributions.inspect'):
                if changed:
                    output.write_bytes(b'previous successful distribution')
                    with self.assertRaisesRegex(ValueError, 'Native payload differs'):
                        merge_forge(group, self.targets, self.manifest, output)
                    self.assertEqual(output.read_bytes(), b'previous successful distribution')
                else:
                    merge_forge(group, self.targets, self.manifest, output)
                    first = output.read_bytes()
                    merge_forge(group, self.targets, self.manifest, output)
                    self.assertEqual(first, output.read_bytes())
                    with ZipFile(output) as jar:
                        data = tomllib.loads(jar.read('META-INF/mods.toml').decode())
                    dependencies = {d['modId']: d['versionRange'] for d in data['dependencies']['dropsoverhaul']}
                    self.assertEqual(dependencies['minecraft'], '[1.19],[1.19.4]')
                    self.assertEqual(dependencies['forge'], '[41.1.0],[45.4.0]')



class ReleaseCollectionTests(unittest.TestCase):
    def fixture(self, incoming):
        manifest = deepcopy(load())
        group = plan(manifest)['include'][0]
        folder = incoming / group['artifact']
        folder.mkdir(parents=True)
        artifact = folder / group['artifact']
        artifact.write_bytes(b'compiled artifact fixture')
        report = dict(artifact=group['artifact'], source=group['source'], state='compiled',
                      sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                      coverage=[dict(target=t, state='compiled') for t in group['targets']])
        (folder / 'validation.json').write_text(json.dumps(report))
        return manifest, artifact

    def test_partial_build_collects_only_successes_and_reports_failures(self):
        with TemporaryDirectory() as directory:
            incoming = Path(directory) / 'incoming'
            manifest, artifact = self.fixture(incoming)
            failed = plan(manifest)['include'][1]
            diagnostic = incoming / ('compile-log-' + failed['source'])
            diagnostic.mkdir()
            (diagnostic / 'validation.json').write_text(json.dumps(dict(state='failed', reason='Compiler failed')))
            destination = Path(directory) / 'dist'
            summary = collect(manifest, incoming, destination)
            self.assertEqual(summary['artifacts'], [artifact.name])
            self.assertEqual(len(summary['unavailable']), 8)
            self.assertEqual(summary['unavailable'][0]['state'], 'failed')
            self.assertEqual(summary['unavailable'][0]['reason'], 'Compiler failed')
            self.assertEqual((destination / artifact.name).read_bytes(), artifact.read_bytes())
            self.assertTrue((destination / (artifact.name + '.validation.json')).is_file())
            self.assertTrue((destination / 'DISTRIBUTIONS.json').is_file())

    def test_tampered_download_fails_before_staging(self):
        with TemporaryDirectory() as directory:
            manifest, artifact = self.fixture(Path(directory) / 'incoming')
            artifact.write_bytes(b'tampered')
            destination = Path(directory) / 'dist'
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                collect(manifest, Path(directory) / 'incoming', destination)
            self.assertFalse(destination.exists())

    def test_no_successful_outputs_cannot_release(self):
        with TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'No successfully compiled'):
                collect(load(), Path(directory) / 'incoming', Path(directory) / 'dist')

    def test_development_policy_must_be_explicit(self):
        manifest = deepcopy(load())
        manifest.pop('developmentReleasePolicy')
        with TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'does not authorize'):
                collect(manifest, Path(directory), Path(directory) / 'dist')


if __name__ == '__main__':
    unittest.main()
