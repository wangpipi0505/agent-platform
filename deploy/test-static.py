"""Runnable negative release checks and Java HTTP-probe behavior; no containers/cluster."""
from pathlib import Path
import http.server
import os
import re
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parent.parent
JAVA = Path(os.environ.get('AGENT_JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot')) / 'bin'


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == '/ready' else 503)
        self.end_headers()

    def log_message(self, *_args):
        pass


def main():
    (ROOT / '.local').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='deployment-check-', dir=ROOT / '.local') as name:
        temporary = Path(name)
        subprocess.run([str(JAVA / 'javac.exe'), '--release', '21', '-d', str(temporary), str(ROOT / 'deploy/HealthProbe.java')], check=True)
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            for path, expected in [('/ready', 0), ('/unready', 1)]:
                result = subprocess.run([str(JAVA / 'java.exe'), '-cp', str(temporary), 'HealthProbe', f'http://127.0.0.1:{server.server_port}{path}'], capture_output=True)
                assert result.returncode == expected, 'HTTP probe must distinguish 200 from 503'
        finally:
            server.shutdown()
            server.server_close()
        # These are deliberately fake test inputs and contain no actual credentials.
        runtime = temporary / 'runtime.env'; runtime.write_text('', encoding='utf-8')
        ops = temporary / 'ops.env'; ops.write_text('', encoding='utf-8')
        required = set(re.findall(r'\$\{([A-Z_]+):\?', (ROOT / 'deploy/compose.yaml').read_text(encoding='utf-8')))
        values = {key: 'isolated-test-only' for key in required}
        values.update(AGENT_RUNTIME_ENV_FILE=runtime.as_posix(), AGENT_OPS_ENV_FILE=ops.as_posix(), AGENT_DB_JDBC_BASE='jdbc:postgresql://invalid.invalid/platform', AGENT_RELEASE_GATE='BLOCKED_IAM_SCOPE_ACCEPTANCE')
        for mode in ['mutable-image', 'blocked-iam']:
            for key in values:
                if key.endswith('_IMAGE'):
                    values[key] = 'registry.invalid/test:latest' if mode == 'mutable-image' else 'registry.invalid/test@sha256:' + '1' * 64
            env = temporary / 'release.env'
            env.write_text('\n'.join(f'{key}={value}' for key, value in values.items()), encoding='utf-8')
            result = subprocess.run(['pwsh', '-NoProfile', '-File', str(ROOT / 'deploy/check-release.ps1'), '-EnvFile', str(env)], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
            expected = 'Explicit non-placeholder image digest' if mode == 'mutable-image' else 'IAM/service scope acceptance gate is blocked'
            assert result.returncode != 0 and expected in result.stdout + result.stderr, f'{mode} must fail before deployment'
    print('PASS: HTTP readiness 200/503 behavior; mutable-image release rejected; IAM-blocked release rejected. No daemon/cluster accessed.')


if __name__ == '__main__':
    main()
