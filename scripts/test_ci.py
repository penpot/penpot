#!/usr/bin/env python3
"""Tests for scripts/ci.

Run with:

    python3 scripts/test_ci.py

Covers the wiring of the orchestrator around the `build` task and the
`media-processor` module: the flag set, one build command per module, the
help text, the dry-run mode (which must execute and write nothing), and the
default task list staying free of build.
"""

import pathlib
import re
import subprocess
import sys
import unittest

sys.dont_write_bytecode = True

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
CI = REPO_ROOT / "scripts" / "ci"
LOG_DIR = REPO_ROOT / ".ci-logs"

ANSI = re.compile(r"\x1b\[[0-9;]*m")

# Modules with a production build. `common` has no build of its own: its
# compilation happens in its test task and in the modules consuming it.
BUILD_MODULES = (
    "frontend",
    "backend",
    "render-wasm",
    "exporter",
    "mcp",
    "plugins",
    "library",
    "media-processor",
)

ALL_MODULES = BUILD_MODULES + ("common",)


def run_ci(*args, cwd=REPO_ROOT):
    """Run scripts/ci and return (exit_code, output without ANSI escapes)."""
    proc = subprocess.run(
        [str(CI), *args],
        cwd=cwd,
        capture_output=True,
        text=True,
    )
    return proc.returncode, ANSI.sub("", proc.stdout + proc.stderr)


def log_snapshot():
    """Names and mtimes of every entry under .ci-logs, or None when absent."""
    if not LOG_DIR.exists():
        return None
    return sorted((p.name, p.stat().st_mtime_ns) for p in LOG_DIR.iterdir())


class BuildTaskTests(unittest.TestCase):
    def test_build_all_has_one_command_per_module_without_common(self):
        code, out = run_ci("--build", "--dry-run", "--all")

        self.assertEqual(code, 0, out)
        self.assertEqual(out.count("build: would run"), len(BUILD_MODULES), out)
        self.assertIn("build: not defined for common, skipping", out)
        for module in BUILD_MODULES:
            self.assertIn(f" in {module}/", out)

    def test_build_is_not_part_of_the_default_tasks(self):
        code, out = run_ci("--dry-run", "--all")

        self.assertEqual(code, 0, out)
        self.assertNotIn("build:", out)
        for task in ("lint", "test", "fmt"):
            self.assertIn(f"{task}: would run", out)

    def test_build_flag_runs_build_only(self):
        code, out = run_ci("--build", "--dry-run", "frontend")

        self.assertEqual(code, 0, out)
        self.assertIn("build: would run", out)
        for task in ("lint", "test", "fmt"):
            self.assertNotIn(f"{task}:", out)

    def test_no_build_flag_is_accepted(self):
        code, out = run_ci("--no-build", "--dry-run", "frontend")

        self.assertEqual(code, 0, out)
        self.assertIn("lint: would run", out)
        self.assertNotIn("build:", out)

    def test_dry_run_executes_nothing(self):
        before = log_snapshot()

        run_ci("--build", "--dry-run", "mcp")

        self.assertEqual(before, log_snapshot())


class MediaProcessorModuleTests(unittest.TestCase):
    def test_module_is_accepted_and_lint_is_skipped(self):
        code, out = run_ci("--dry-run", "media-processor")

        self.assertEqual(code, 0, out)
        self.assertIn(
            "lint: not defined for media-processor, skipping",
            out,
        )
        for task in ("test", "fmt"):
            self.assertIn(f"{task}: would run", out)

    def test_module_is_included_in_all(self):
        code, out = run_ci("--dry-run", "--all")

        self.assertEqual(code, 0, out)
        self.assertIn("media-processor", out)
        self.assertIn("media-processor:test", out)

    def test_build_runs_types_check_before_esbuild(self):
        code, out = run_ci("--build", "--dry-run", "media-processor")

        self.assertEqual(code, 0, out)
        self.assertIn(
            "pnpm run types:check && pnpm run build",
            out,
        )


class HelpTests(unittest.TestCase):
    def help_options(self, out):
        """Option tokens from the OPTIONS section, e.g. '--build'."""
        options = []
        for line in out.splitlines():
            stripped = line.strip()
            if stripped.startswith("--"):
                options.append(stripped.split()[0])
        return options

    def test_help_documents_the_build_flags(self):
        code, out = run_ci("--help")

        self.assertEqual(code, 0, out)
        options = self.help_options(out)
        self.assertIn("--build", options)
        self.assertIn("--no-build", options)

    def test_help_lists_every_module(self):
        code, out = run_ci("--help")

        self.assertEqual(code, 0, out)
        for module in ALL_MODULES:
            self.assertIn(module, out)


if __name__ == "__main__":
    unittest.main()
