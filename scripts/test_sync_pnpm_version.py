#!/usr/bin/env python3
"""Tests for scripts/sync-pnpm-version.

Run with:

    python3 scripts/test_sync_pnpm_version.py

Covers stamping the `packageManager` field across a package.json tree:
stale values get updated, missing fields get inserted, formatting of each
file is preserved (only the stamped line may change), --check reports
drift without writing, and stamping is idempotent.
"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

# Loading scripts/sync-pnpm-version via subprocess; no bytecode emitted.
sys.dont_write_bytecode = True

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent / "sync-pnpm-version"

FIELD = "pnpm@99.0.0+sha512." + "ab" * 64


def run_script(*args):
    """Run the script, returning the CompletedProcess."""
    return subprocess.run(
        ["bash", str(SCRIPT_PATH), *args],
        capture_output=True,
        text=True,
    )


def make_fixture(root):
    """Build a 3-file tree: stale value, already synced, missing field."""
    stale = root / "stale"
    stale.mkdir()
    (stale / "package.json").write_text(
        '{\n'
        '  "name": "stale",\n'
        '  "version": "1.0.0",\n'
        '  "private": true,\n'
        '  "packageManager": "pnpm@10.0.0+sha512.' + "00" * 64 + '",\n'
        '  "scripts": {\n'
        '    "test": "echo ok"\n'
        '  }\n'
        '}\n'
    )
    synced = root / "synced"
    synced.mkdir()
    # 4-space indent on purpose: the stamp must not reformat the file.
    (synced / "package.json").write_text(
        '{\n'
        '    "name": "synced",\n'
        '    "version": "1.0.0",\n'
        f'    "packageManager": "{FIELD}"\n'
        '}\n'
    )
    missing = root / "missing"
    missing.mkdir()
    (missing / "package.json").write_text(
        '{\n'
        '  "name": "missing",\n'
        '  "version": "1.0.0",\n'
        '  "scripts": {\n'
        '    "test": "echo ok"\n'
        '  }\n'
        '}\n'
    )
    return stale, synced, missing


class SyncPnpmVersionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="sync-pnpm-"))
        self.stale, self.synced, self.missing = make_fixture(self.tmp)
        self.synced_before = (self.synced / "package.json").read_bytes()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def stamp(self, *extra):
        return run_script("--root", str(self.tmp), "--field", FIELD, *extra)

    def read(self, path):
        return (path / "package.json").read_text()

    def test_stamp_updates_stale_inserts_missing_keeps_synced(self):
        proc = self.stamp()
        self.assertEqual(proc.returncode, 0, proc.stderr)

        stale_lines = self.read(self.stale).splitlines()
        self.assertIn(f'  "packageManager": "{FIELD}",', stale_lines)
        data = json.loads(self.read(self.stale))
        self.assertEqual(data["packageManager"], FIELD)
        # Only the packageManager line changed in the stale file.
        self.assertEqual(len(stale_lines), 9)

        # The already-synced file is byte-identical (indent preserved).
        self.assertEqual(
            (self.synced / "package.json").read_bytes(), self.synced_before
        )

        # The missing field was inserted; the file stays valid JSON.
        data = json.loads(self.read(self.missing))
        self.assertEqual(data["packageManager"], FIELD)
        self.assertEqual(data["name"], "missing")

    def test_check_passes_once_synced(self):
        self.assertEqual(self.stamp().returncode, 0)
        proc = self.stamp("--check")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_check_fails_on_drift_without_writing(self):
        self.assertEqual(self.stamp().returncode, 0)
        before = (self.stale / "package.json").read_bytes()
        (self.stale / "package.json").write_text(
            self.read(self.stale).replace(FIELD, "pnpm@1.0.0+sha512." + "ff" * 64)
        )
        proc = self.stamp("--check")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("stale/package.json", proc.stdout + proc.stderr)
        # --check must not write: reverting the tamper restores a pass.
        (self.stale / "package.json").write_bytes(before)
        self.assertEqual(self.stamp("--check").returncode, 0)

    def test_check_reports_missing_field(self):
        proc = self.stamp("--check")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("missing/package.json", proc.stdout + proc.stderr)

    def test_stamp_is_idempotent(self):
        self.assertEqual(self.stamp().returncode, 0)
        first = {
            p: (self.tmp / p / "package.json").read_bytes()
            for p in ("stale", "synced", "missing")
        }
        second = self.stamp()
        self.assertEqual(second.returncode, 0, second.stderr)
        for name, content in first.items():
            self.assertEqual(
                (self.tmp / name / "package.json").read_bytes(), content
            )


if __name__ == "__main__":
    unittest.main()
