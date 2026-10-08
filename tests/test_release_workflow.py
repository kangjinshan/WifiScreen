"""Exercise the publishing code embedded in the workflow, including old-tag retries."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/android.yml"
STEP = WORKFLOW.read_text().split("      - name: Publish GitHub Release\n", 1)[1]
SOURCE = textwrap.dedent(STEP.split("python3 - <<'PY'\n", 1)[1].split("          PY\n", 1)[0])


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.metadata = dict(package="com.kanayama.wifiscreen", versionCode=5,
                             versionName="0.1.4", commit="source-commit",
                             certificateSha256="signing-certificate", size=123, sha256="published-hash")
        self.published = dict(self.metadata)
        self.release = dict(draft=False, html_url="https://example.test/releases/v0.1.4", assets=[
            dict(name="wifiscreen-0.1.4.apk", size=123, state="uploaded", digest="sha256:published-hash"),
            dict(name="SHA256SUMS", size=87, state="uploaded"),
            dict(name="build-info.json", size=335, state="uploaded"),
        ])

    def run_publish(self, existing=True, error=None):
        with tempfile.TemporaryDirectory() as directory, contextlib.chdir(directory):
            Path("dist").mkdir()
            # Identical source/signing can produce different bytes when rebuilt.
            rebuilt = dict(self.metadata, sha256="rebuilt-hash", size=456)
            Path("dist/build-info.json").write_text(json.dumps(rebuilt))
            Path("dist/SHA256SUMS").write_text("rebuilt-hash  wifiscreen-0.1.4.apk\n")
            Path("dist/wifiscreen-0.1.4.apk").write_bytes(b"rebuilt APK")
            responses = [json.dumps(self.release) if existing else "", json.dumps(self.published)]
            with patch.dict(os.environ, RELEASE_TAG="v0.1.4", APP_VERSION="0.1.4",
                            GITHUB_REPOSITORY="example/WifiScreen"), \
                    patch("subprocess.check_output", side_effect=error or responses) as read, \
                    patch("subprocess.check_call") as write, \
                    contextlib.redirect_stdout(io.StringIO()):
                try:
                    exec(compile(SOURCE, str(WORKFLOW), "exec"), {})
                finally:
                    self.read_calls = read.call_args_list
                    self.write_calls = write.call_args_list

    def test_new_release_is_created_with_all_artifacts(self):
        self.run_publish(existing=False)
        self.assertEqual(len(self.write_calls), 1)
        args = self.write_calls[0].args[0]
        self.assertEqual(args[:4], ["gh", "release", "create", "v0.1.4"])
        for name in ("wifiscreen-0.1.4.apk", "SHA256SUMS", "build-info.json"):
            self.assertIn("dist/" + name, args)
        self.assertIn("--verify-tag", args)

    def test_complete_matching_release_succeeds_without_mutation(self):
        self.run_publish()
        self.assertEqual(self.write_calls, [])
        self.assertEqual(len(self.read_calls), 2)

    def test_incomplete_release_fails_without_mutation(self):
        self.release["assets"].pop()
        with self.assertRaisesRegex(SystemExit, "incomplete"):
            self.run_publish()
        self.assertEqual(self.write_calls, [])

    def test_draft_release_fails_without_mutation(self):
        self.release["draft"] = True
        with self.assertRaisesRegex(SystemExit, "incomplete"):
            self.run_publish()
        self.assertEqual(self.write_calls, [])

    def test_different_source_or_identity_fails_without_mutation(self):
        for key in ("commit", "certificateSha256", "package", "versionName", "versionCode"):
            with self.subTest(key=key), patch.dict(self.published, {key: "unexpected"}):
                with self.assertRaisesRegex(SystemExit, key):
                    self.run_publish()
                self.assertEqual(self.write_calls, [])

    def test_inconsistent_apk_metadata_fails_without_mutation(self):
        for key, value in (("size", 999), ("digest", "sha256:unexpected")):
            with self.subTest(key=key), patch.dict(self.release["assets"][0], {key: value}):
                with self.assertRaisesRegex(SystemExit, "does not match"):
                    self.run_publish()
                self.assertEqual(self.write_calls, [])

    def test_api_error_is_not_treated_as_missing_release(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_publish(error=subprocess.CalledProcessError(1, ["gh", "api"]))
        self.assertEqual(self.write_calls, [])


if __name__ == "__main__":
    unittest.main()
