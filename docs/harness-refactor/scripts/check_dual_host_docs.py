#!/usr/bin/env python3
"""Static dual-host documentation checks. Does not contact Mac/Windows or Docker."""
from pathlib import Path
import ast
import json
import re
import tomllib
from datetime import datetime, timezone

D = Path(__file__).resolve().parents[1]
checks = []

def check(name, fn):
    try:
        detail = fn()
        checks.append({"name": name, "status": "PASS", "detail": detail})
    except Exception as exc:
        checks.append({"name": name, "status": "FAIL", "detail": repr(exc)})

def profiles():
    win = tomllib.loads((D/'deploy/opensandbox.windows-wsl.example.toml').read_text())
    linux = tomllib.loads((D/'deploy/opensandbox.linux.example.toml').read_text())
    wsl = (D/'deploy/windows/.wslconfig.example').read_text()
    assert win['server']['host'] == '0.0.0.0' and win['server']['port'] == 8090
    assert win['proxy']['resolve_internal'] is False
    assert linux['proxy']['resolve_internal'] is True
    for p in [win, linux]:
        assert p['server']['api_key'] and p['runtime']['type'] == 'docker'
        assert p['runtime']['execd_image'] == 'opensandbox/execd:release-1.1.0'
        assert p['docker']['no_new_privileges'] is True
    assert 'memory=16GB' in wsl and 'swap=4GB' in wsl
    return 'TOML parsed; deployment choices and WSL INI budget present; not provider/runtime validation'

def docs():
    install = (D/'06_OpenSandbox安装接线与联调.md').read_text()
    blocks = re.findall(r'```(?:bash|sh|powershell)\n(.*?)```', install, re.S)
    assert not any(re.search(r'python.*-m opensandbox_server\.main', b) for b in blocks)
    assert '.venv/bin/opensandbox-server' in install
    for text in ['linux/amd64', 'MAC_PRIVATE_IP', 'WINDOWS_PRIVATE_IP', 'portproxy',
                 'useServerProxy=true', 'proxy.resolve_internal=false', 'host.docker.internal',
                 'GITNOVA_PROBE_CALLBACK_URL', 'Mac合盖', 'Model Proxy']:
        assert text in install, text
    architecture = (D/'01_总体架构与通信契约.md').read_text()
    assert 'I11' in architecture and '各自从自己的cursor' in architecture
    entry = (D/'00_从这里开始.md').read_text()
    assert '环境准备线' in entry and '步骤1—8' in entry and '步骤9' in entry
    return 'CLI command blocks, address matrix, multi-client and early-infrastructure lane checked'

def cases():
    text = (D/'03_验收矩阵与资料依据.md').read_text()
    ids = re.findall(r'^\| ([A-Z]+\d+) \|', text, re.M)
    assert len(ids) == 120 and len(ids) == len(set(ids)), len(ids)
    assert {f'D{i:02}' for i in range(1,17)} <= set(ids)
    return {'cases': len(ids), 'new_dual_host_cases': 16, 'executed_product_cases': 0}

def probe_contract():
    text = (D/'probes/opensandbox/src/main/java/OpenSandboxProbe.java').read_text()
    for part in ['env("OPEN_SANDBOX_DOMAIN")','OPEN_SANDBOX_ARCH','GITNOVA_PROBE_CALLBACK_URL',
                 '.useServerProxy(true)', '.platform(', 'BodyHandlers.ofInputStream()', 'CALLBACK_NETWORK=NOT_RUN']:
        assert part in text, part
    # Unescape the only Java escape in the embedded Python body; no code is executed here.
    source = re.search(r'String program = """\n(.*?)\n""";', text, re.S)[1].replace('\\\\', '\\')
    ast.parse(source)
    return 'Probe inputs/streaming path and embedded Python syntax checked; SDK NOT compiled'

check('DEPLOYMENT_PROFILES', profiles)
check('DUAL_HOST_DOCUMENT_CONTRACT', docs)
check('DUAL_HOST_ACCEPTANCE_COVERAGE', cases)
check('SDK_PROBE_SOURCE_STATIC', probe_contract)
report = {
    'checked_at_utc': datetime.now(timezone.utc).isoformat(), 'checks': checks,
    'overall': 'PASS' if all(x['status']=='PASS' for x in checks) else 'FAIL',
    'scope': 'Static document/config/source consistency only',
    'mac_windows_network': 'NOT_RUN', 'opensandbox_sdk_compile': 'NOT_RUN',
    'opensandbox_live': 'NOT_RUN', 'product_cases_executed': 0,
}
(D/'audit/dual-host-check.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
for item in checks: print(item['status'], item['name'], item['detail'])
raise SystemExit(0 if report['overall']=='PASS' else 1)
