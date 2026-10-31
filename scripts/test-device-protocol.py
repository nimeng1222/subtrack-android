#!/usr/bin/env python3
"""Host checks for the exact validator used by device-ci.py; no Android mocking can make CI green."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('device-ci.py'))
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)

class ProtocolTests(unittest.TestCase):
    expected = {'sample.Case#test_one'}
    def log(self, outcome='PASS', tests=1, failures=0, completed=True):
        case = {'class': 'sample.Case', 'method': 'test_one', 'outcome': outcome}
        summary = {'protocol': 1, 'completed': completed, 'tests': tests, 'failures': failures, 'ignored': 0}
        return ('INSTRUMENTATION_STATUS: subtrack_test=' + json.dumps(case) + '\n'
                'INSTRUMENTATION_RESULT: subtrack_result=' + json.dumps(summary) + '\nINSTRUMENTATION_CODE: -1\n')
    def test_complete_pass(self): self.assertEqual([], gate.validate(self.log(), self.expected)[2])
    def test_false_adb_success_cannot_hide_assertion(self): self.assertTrue(gate.validate(self.log('FAIL', failures=1), self.expected)[2])
    def test_zero_is_failure(self): self.assertTrue(gate.validate(self.log(tests=0), self.expected)[2])
    def test_crash_is_failure(self): self.assertTrue(gate.validate('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n', self.expected)[2])
    def test_incomplete_is_failure(self): self.assertTrue(gate.validate(self.log().split('INSTRUMENTATION_RESULT')[0], self.expected)[2])
    def test_missing_new_test_is_failure(self): self.assertTrue(gate.validate(self.log(), self.expected | {'sample.Case#test_two'})[2])
    def test_duplicate_is_failure(self): self.assertTrue(gate.validate(self.log() + self.log(), self.expected)[2])
    def test_pretty_stream_is_not_a_result(self): self.assertTrue(gate.validate("PASS test_one\nTests: 1, failures: 0, ignored: 0\n", self.expected)[2])
    def test_skip_is_failure(self): self.assertTrue(gate.validate(self.log('SKIP'), self.expected)[2])

if __name__ == '__main__': unittest.main()
