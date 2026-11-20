"""Stop only the test server process and preserve shutdown failure diagnostics."""
import os
from pathlib import Path
import subprocess
import time


def stop_server(proc, java, server, result, timeout=45):
    def fail(message):
        previous = result.get('error')
        result.update(state='failed', error=(previous + '; ' + message) if previous else message)

    started = time.monotonic()
    if proc.poll() is None:
        try:
            proc.stdin.write('stop\n')
            proc.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            result['stopRequestError'] = str(error)
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            result['shutdownTimedOut'] = True
            fail(f'Server did not stop cleanly within {timeout} seconds')
            dump = server / 'shutdown-threads.txt'
            jcmd = Path(java).with_name('jcmd.exe' if os.name == 'nt' else 'jcmd')
            try:
                with dump.open('w') as output:
                    subprocess.run([str(jcmd), str(proc.pid), 'Thread.print'], stdout=output,
                                   stderr=subprocess.STDOUT, check=True, timeout=10)
                result['shutdownThreadDump'] = dump.name
            except (OSError, subprocess.SubprocessError) as error:
                result['shutdownDiagnosticError'] = str(error)
            proc.kill()
            proc.wait()
            result['forcedTermination'] = True
    result['shutdownDurationSeconds'] = round(time.monotonic() - started, 3)
    result['serverExitCode'] = proc.returncode
    if proc.returncode != 0 and not result.get('forcedTermination'):
        fail(f'Server exited with status {proc.returncode}')
