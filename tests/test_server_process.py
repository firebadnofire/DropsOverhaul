"""Exercise clean exit and a real JVM's lingering non-daemon executor.

Run with: python tests/test_server_process.py --java <JDK java executable>
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
from server_process import stop_server

parser = argparse.ArgumentParser()
parser.add_argument('--java', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / 'build/harness-tests'
root.mkdir(parents=True, exist_ok=True)
source = '''import java.io.*;
import java.util.concurrent.*;
public class ShutdownFixture {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {}, 0, 1, TimeUnit.SECONDS);
        }
        System.out.println("READY");
        System.out.flush();
        new BufferedReader(new InputStreamReader(System.in)).readLine();
    }
}
'''
with tempfile.TemporaryDirectory(dir=root) as directory:
    directory = Path(directory)
    assert directory.resolve().is_relative_to(root.resolve())
    java_source = directory / 'ShutdownFixture.java'
    java_source.write_text(source)
    javac = args.java.with_name('javac.exe' if args.java.suffix == '.exe' else 'javac')
    subprocess.run([str(javac), str(java_source)], check=True)
    for lingering in (False, True):
        proc = subprocess.Popen([str(args.java), '-cp', str(directory), 'ShutdownFixture'] + (['linger'] if lingering else []),
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            assert proc.stdout.readline().strip() == 'READY'
            result = {'state': 'failed', 'error': 'Original gameplay failure'} if lingering else {'state': 'startup-tested'}
            stop_server(proc, args.java, directory, result, timeout=1 if lingering else 10)
            if lingering:
                assert result['state'] == 'failed' and result['shutdownTimedOut'] and result['forcedTermination']
                assert result['error'].startswith('Original gameplay failure; Server did not stop cleanly')
                assert 'ThreadPoolExecutor' in (directory / result['shutdownThreadDump']).read_text()
            else:
                assert result['state'] == 'startup-tested' and result['serverExitCode'] == 0
                assert not result.get('forcedTermination')
            print('PASS:', 'lingering executor diagnostics' if lingering else 'clean JVM shutdown')
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()
