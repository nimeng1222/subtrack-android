#!/usr/bin/env python3
"""Single device CI entry: build -> install -> grant -> instrument -> validate -> archive (also on failure)."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def inventory():
    """Discover every checked-in @Test method, so omitting a suite/new test fails the gate."""
    tests = set()
    for path in sorted((ROOT / 'app/src/androidTest/java').rglob('*.kt')):
        source = path.read_text()
        methods = re.findall(r'@Test\s+fun\s+([A-Za-z0-9_]+)\s*\(', source)
        if methods:
            package = re.search(r'^package\s+(\S+)', source, re.M).group(1)
            klass = re.search(r'^class\s+(\w+)', source, re.M).group(1)
            tests.update(f'{package}.{klass}#{name}' for name in methods)
    if len(tests) < 17:
        raise RuntimeError(f'Device inventory is incomplete: {len(tests)} (accepted baseline: 17)')
    return tests


def validate(raw, expected):
    records, summaries, errors = [], [], []
    for line in raw.splitlines():
        for prefix, destination in [('INSTRUMENTATION_STATUS: subtrack_test=', records),
                                    ('INSTRUMENTATION_RESULT: subtrack_result=', summaries)]:
            if line.startswith(prefix):
                try:
                    destination.append(json.loads(line[len(prefix):]))
                except (ValueError, TypeError) as exc:
                    errors.append(f'Malformed protocol JSON: {exc}')
    if len(summaries) != 1:
        errors.append(f'Expected one complete runner result, got {len(summaries)}')
    summary = summaries[0] if len(summaries) == 1 else {}
    if summary.get('protocol') != 1 or summary.get('completed') is not True:
        errors.append('Runner did not complete protocol 1')
    ids = [f'{r.get("class")}#{r.get("method")}' for r in records]
    if len(set(ids)) != len(ids):
        errors.append('Duplicate test completion records')
    if set(ids) != expected:
        errors.append(f'Missing: {sorted(expected - set(ids))}; unexpected: {sorted(set(ids) - expected)}')
    if not records or summary.get('tests') != len(expected) or len(records) != len(expected):
        errors.append(f'Wrong/nonpositive count: expected {len(expected)}, summary {summary.get("tests")}, records {len(records)}')
    if summary.get('failures') != 0 or summary.get('ignored') != 0:
        errors.append(f'Runner reported failures/skips: {summary}')
    if any(r.get('outcome') != 'PASS' for r in records):
        errors.append('One or more tests did not pass')
    if not re.search(r'^INSTRUMENTATION_CODE:\s*-1\s*$', raw, re.M):
        errors.append('Missing successful instrumentation completion code')
    return records, summary, errors


def write_junit(output, expected, records, errors):
    by_id = {f'{r.get("class")}#{r.get("method")}': r for r in records}
    suite = ET.Element('testsuite', name='SubTrackDevice', tests=str(len(expected) + bool(errors)))
    failures = 0
    for identifier in sorted(expected):
        klass, name = identifier.split('#', 1)
        record = by_id.get(identifier, {})
        case = ET.SubElement(suite, 'testcase', classname=klass, name=name, time=str(record.get('seconds', 0)))
        if record.get('outcome') != 'PASS':
            failures += 1
            ET.SubElement(case, 'failure', message=record.get('outcome', 'NOT_COMPLETED')).text = record.get('trace', 'Runner did not complete this test')
    if errors:
        failures += 1
        case = ET.SubElement(suite, 'testcase', classname='SubTrackDeviceGate', name='complete_protocol')
        ET.SubElement(case, 'failure', message='Device gate failed').text = '\n'.join(errors)
    suite.set('failures', str(failures)); suite.set('errors', '0'); suite.set('skipped', '0')
    ET.ElementTree(suite).write(output / 'device-junit.xml', encoding='utf-8', xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools/adb'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/device-ci')
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--build-timeout', type=int, default=900)
    parser.add_argument('--gradle-arg', action='append', default=[])
    parser.add_argument('--validate-log', type=Path, help='Validate archived output using exactly the same result checker; no device actions')
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    expected = set()
    errors, phases, records, summary = [], [], [], {}
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])

    def run(phase, command, limit):
        log = output / f'{phase}.log'
        with log.open('w') as stream:
            process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=limit)
            except subprocess.TimeoutExpired:
                import signal
                os.killpg(process.pid, signal.SIGKILL); process.wait()
                phases.append({'phase': phase, 'exit_code': 124, 'timeout': True})
                raise RuntimeError(f'{phase} timed out after {limit}s')
        phases.append({'phase': phase, 'exit_code': code, 'command': command})
        if code:
            raise RuntimeError(f'{phase} failed with exit {code}; see {log}')
        return log.read_text(errors='replace')

    try:
        expected = inventory()
        if args.validate_log:
            raw = args.validate_log.read_text(errors='replace')
            (output / 'device.log').write_text(raw)
        else:
            run('build', [str(ROOT / 'gradlew'), *args.gradle_arg, ':app:assembleDebug', ':app:assembleDebugAndroidTest'], args.build_timeout)
            run('install-app', adb + ['install', '-r', str(ROOT / 'app/build/outputs/apk/debug/app-debug.apk')], 120)
            run('install-tests', adb + ['install', '-r', str(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')], 120)
            run('grant-notifications', adb + ['shell', 'pm', 'grant', 'com.subtrack', 'android.permission.POST_NOTIFICATIONS'], 30)
            raw = run('device', adb + ['shell', 'am', 'instrument', '-w', '-r', 'com.subtrack.test/com.subtrack.Round2Instrumentation'], args.timeout)
        records, summary, validation_errors = validate(raw, expected)
        errors.extend(validation_errors)
    except Exception as exc:
        errors.append(str(exc))
    finally:
        (output / 'device.log').touch(exist_ok=True)  # Empty if a prior phase prevented instrumentation.
        # Keep the device's own diagnostics even when the runner crashes or never finishes.
        # Diagnostics are best effort and must never turn a failed test phase into success.
        if not args.validate_log:
            for name, command in [('logcat', ['logcat', '-d', '-t', '4000']),
                                  ('device-state', ['shell', 'getprop'])]:
                try:
                    with (output / f'{name}.log').open('w') as stream:
                        diagnostic = subprocess.run(adb + command, stdout=stream, stderr=subprocess.STDOUT, timeout=15)
                    phases.append({'phase': name, 'exit_code': diagnostic.returncode, 'diagnostic_only': True})
                except Exception as exc:
                    (output / f'{name}.log').write_text(f'Diagnostic unavailable: {exc}\n')
        write_junit(output, expected, records, errors)
        result = {'ok': not errors, 'expected_tests': sorted(expected), 'summary': summary,
                  'tests': records, 'errors': errors, 'phases': phases}
        (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        for folder in ['reports', 'test-results']:
            source = ROOT / f'app/build/{folder}'
            if source.exists():
                shutil.copytree(source, output / f'build-{folder}', dirs_exist_ok=True)
    print(json.dumps({'ok': not errors, 'completed_tests': len(records), 'errors': errors, 'output': str(output)}, ensure_ascii=False))
    return 0 if not errors else 1


if __name__ == '__main__':
    sys.exit(main())
