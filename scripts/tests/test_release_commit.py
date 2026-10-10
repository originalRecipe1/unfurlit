"""Exercise the release workflow's commit selection and publication commands locally."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/release-tag.yml'


def step_script(name):
    step = WORKFLOW.read_text().split(f'      - name: {name}\n', 1)[1]
    step = step.split('\n      - name:', 1)[0]
    return textwrap.dedent(step.split('        run: |\n', 1)[1])


class ReleaseCommitTest(unittest.TestCase):
    def test_manual_release_uses_tested_commit_even_when_main_has_advanced(self):
        tested, current = 'a' * 40, 'b' * 40
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'outputs'
            result = subprocess.run(['bash', '-c', step_script('Select the release commit')],
                                    env={**os.environ, 'EVENT_NAME': 'workflow_dispatch',
                                         'REQUESTED_COMMIT': tested, 'EVENT_COMMIT': current,
                                         'GITHUB_OUTPUT': str(output)}, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(f'sha={tested}\n', output.read_text())

    def test_manual_release_rejects_missing_or_moving_refs(self):
        with tempfile.TemporaryDirectory() as directory:
            for requested in ('', 'main', 'v1.4.1', 'abcdef1', 'A' * 40, 'a' * 40 + '\n'):
                with self.subTest(requested=requested):
                    result = subprocess.run(['bash', '-c', step_script('Select the release commit')],
                                            env={**os.environ, 'EVENT_NAME': 'workflow_dispatch',
                                                 'REQUESTED_COMMIT': requested, 'EVENT_COMMIT': 'b' * 40,
                                                 'GITHUB_OUTPUT': str(Path(directory) / 'outputs')},
                                            capture_output=True)
                    self.assertNotEqual(0, result.returncode)

    def test_automatic_release_uses_event_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'outputs'
            result = subprocess.run(['bash', '-c', step_script('Select the release commit')],
                                    env={**os.environ, 'EVENT_NAME': 'pull_request',
                                         'REQUESTED_COMMIT': '', 'EVENT_COMMIT': 'b' * 40,
                                         'GITHUB_OUTPUT': str(output)}, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(f"sha={'b' * 40}\n", output.read_text())

    def test_only_default_branch_history_can_be_released(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            git('init', '-q', '-b', 'main')
            git('config', 'user.name', 'Workflow test')
            git('config', 'user.email', 'workflow-test@example.invalid')
            git('commit', '-q', '--allow-empty', '-m', 'tested release')
            tested = git('rev-parse', 'HEAD')
            git('commit', '-q', '--allow-empty', '-m', 'later main commit')
            git('update-ref', 'refs/remotes/origin/main', 'HEAD')
            git('checkout', '-q', '--detach', tested)
            env = {**os.environ, 'RELEASE_COMMIT': tested, 'DEFAULT_BRANCH': 'main'}
            result = subprocess.run(['bash', '-c', step_script('Require a commit on the default branch')],
                                    cwd=directory, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            git('commit', '-q', '--allow-empty', '-m', 'unmerged change')
            env['RELEASE_COMMIT'] = git('rev-parse', 'HEAD')
            result = subprocess.run(['bash', '-c', step_script('Require a commit on the default branch')],
                                    cwd=directory, env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)

    def test_publication_targets_selected_commit_not_workflow_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gh = root / 'gh'
            gh.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$CAPTURE_ARGS"\n')
            gh.chmod(0o755)
            capture = root / 'args'
            env = {**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                   'CAPTURE_ARGS': str(capture), 'RELEASE_COMMIT': 'a' * 40,
                   'GITHUB_SHA': 'b' * 40, 'TAG': 'v1.4.1', 'VERSION_NAME': '1.4.1',
                   'VERSION_CODE': '14', 'SIGNED_APK': 'signed.apk',
                   'SIGNED_CHECKSUM': 'signed.apk.sha256', 'R8_MAPPING': 'mapping.txt'}
            result = subprocess.run(['bash', '-c', step_script('Create the tag and GitHub release')],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            args = capture.read_text().splitlines()
            self.assertEqual('a' * 40, args[args.index('--target') + 1])
            self.assertNotIn('b' * 40, args)
