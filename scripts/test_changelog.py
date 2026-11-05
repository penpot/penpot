#!/usr/bin/env python3
"""Tests for scripts/changelog.py.

Run with:

    python3 scripts/test_changelog.py
"""

import importlib.machinery
import importlib.util
import pathlib
import sys
import unittest


# Loading scripts/changelog.py should not emit scripts/__pycache__/.
sys.dont_write_bytecode = True

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent / "changelog.py"


def load_changelog():
    """Load scripts/changelog.py as a module without running its CLI."""
    loader = importlib.machinery.SourceFileLoader("changelog_helper", str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader("changelog_helper", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


changelog = load_changelog()


def bug(number, body="", parent=None, labels=()):
    return {
        "number": number,
        "title": f"Bug {number}",
        "state": "CLOSED",
        "issue_type": "Bug",
        "labels": list(labels),
        "body": body,
        "parent": parent,
    }


class OnlyUnreleasedEnvsTests(unittest.TestCase):
    def check(self, text):
        return changelog.only_unreleased_envs(text, "2.19.0")

    def test_dev_environments(self):
        for text in ["HOURLY", "https://hourly.penpot.dev/", "PRE & hourly",
                     "staging (current)", "develop (2.19.0 unreleased)",
                     "2.19.0-RC1 (develop)", "2.19.0"]:
            with self.subTest(text=text):
                self.assertTrue(self.check(text))

    def test_production_hints(self):
        for text in ["PRO", "pre/pro/hourly", "develop / staging / main",
                     "2.18.0-RC5", "2.18.1, 2.19.0-RC1", "self-hosted: 2.17.2",
                     "design.penpot.app and self-host"]:
            with self.subTest(text=text):
                self.assertFalse(self.check(text))

    def test_no_signal(self):
        for text in ["", "_No response_", "-", "development build"]:
            with self.subTest(text=text):
                self.assertFalse(self.check(text))


class FindDevOnlyBugsTests(unittest.TestCase):
    def test_splits_auto_and_suggested(self):
        issues = [
            bug(1, parent={"number": 10, "issue_type": "Feature", "milestone": "2.19.0"}),
            bug(2, parent={"number": 11, "issue_type": "Enhancement", "milestone": "2.19.0"}),
            bug(3, parent={"number": 12, "issue_type": "EPIC", "milestone": "2.19.0"}),
            bug(4, parent={"number": 13, "issue_type": "Feature", "milestone": "2.18.0"}),
            bug(5, body="### Affected versions\n\nhourly\n"),
            bug(6, body="### Affected versions\n\n2.18.2\n"),
            bug(7, parent={"number": 10, "issue_type": "Feature", "milestone": "2.19.0"},
                labels=["no changelog"]),
            {**bug(8, parent={"number": 10, "issue_type": "Feature", "milestone": "2.19.0"}),
             "issue_type": "Enhancement"},
            bug(9, body="### Affected versions\n\n2.18.2\n", labels=["regression"]),
        ]
        auto, suggested = changelog.find_dev_only_bugs("2.19.0", issues)
        self.assertEqual([e["issue"] for e in auto], [1, 2])
        self.assertEqual([e["issue"] for e in suggested], [3, 5, 9])


if __name__ == "__main__":
    unittest.main()
