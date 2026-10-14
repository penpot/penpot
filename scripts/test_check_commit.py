#!/usr/bin/env python3
"""Tests for scripts/check-commit.

Run with:

    python3 scripts/test_check_commit.py

Covers the body line-wrapping validator added to enforce the commit body
wrap rule documented in .serena/memories/workflow/creating-commits.md.
"""

import importlib.machinery
import importlib.util
import pathlib
import sys
import unittest

# Loading scripts/check-commit would otherwise emit scripts/__pycache__/.
sys.dont_write_bytecode = True

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent / "check-commit"


def load_check_commit():
    """Load the extensionless scripts/check-commit as a module."""
    loader = importlib.machinery.SourceFileLoader("check_commit", str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader("check_commit", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


check_commit = load_check_commit()


class BodyLineLengthTests(unittest.TestCase):
    def assert_ok(self, message):
        ok, error = check_commit.check_body_line_length(message)
        self.assertTrue(ok, error)
        self.assertIsNone(error)

    def assert_fail(self, message):
        ok, error = check_commit.check_body_line_length(message)
        self.assertFalse(ok)
        self.assertIsNotNone(error)
        return error

    def test_wrapped_body_passes(self):
        message = (
            ":bug: Fix crash when opening the file menu\n"
            "\n"
            "The menu reused a stale reference after the file was\n"
            "closed, which raised an exception on reopen.\n"
        )
        self.assert_ok(message)

    def test_line_at_limit_passes(self):
        line = "x " * 40  # 80 chars, breakable
        self.assertEqual(len(line), 80)
        self.assert_ok(":bug: Fix crash\n\n" + line + "\n")

    def test_line_one_over_limit_fails(self):
        line = "x " * 40 + "x"  # 81 chars, breakable
        self.assertEqual(len(line), 81)
        error = self.assert_fail(":bug: Fix crash\n\n" + line + "\n")
        self.assertIn("80", error)

    def test_long_body_line_fails(self):
        long_line = "word " * 20  # 100 chars, breakable
        error = self.assert_fail(":bug: Fix crash\n\n" + long_line + "\n")
        self.assertIn("80", error)
        self.assertIn("line 3", error)

    def test_subject_is_not_checked(self):
        # The subject has its own length rule; the body validator ignores it.
        subject = ":bug: " + "S" * 100
        self.assert_ok(subject + "\n")

    def test_url_line_passes(self):
        line = (
            "See https://github.com/penpot/penpot/issues/1234"
            "/comments/very/long/fragment"
        )
        self.assert_ok(":books: Update docs\n\n" + line + "\n")

    def test_trailer_passes(self):
        line = "Signed-off-by: Someone With A Long Name <someone@example.com>"
        self.assert_ok(":bug: Fix crash\n\nBody.\n\n" + line + "\n")

    def test_unbreakable_token_passes(self):
        line = "a" * 100  # no whitespace to wrap at
        self.assert_ok(":bug: Fix crash\n\n" + line + "\n")

    def test_blank_lines_are_ignored(self):
        self.assert_ok(":bug: Fix crash\n\n\n\n")

    def test_multiple_offenders_reported(self):
        error = self.assert_fail(
            ":bug: Fix crash\n\n"
            + ("word " * 20)
            + "\n"
            + ("other " * 20)
            + "\n"
        )
        self.assertIn("line 3", error)
        self.assertIn("line 4", error)


class ForbiddenTrailerTests(unittest.TestCase):
    def assert_ok(self, message):
        ok, error = check_commit.check_no_co_authored_by(message)
        self.assertTrue(ok, error)
        self.assertIsNone(error)

    def assert_fail(self, message):
        ok, error = check_commit.check_no_co_authored_by(message)
        self.assertFalse(ok)
        self.assertIsNotNone(error)
        return error

    def test_body_without_trailer_passes(self):
        self.assert_ok(":bug: Fix crash\n\nBody.\n\nSigned-off-by: Someone <someone@example.com>\n")

    def test_co_authored_by_fails(self):
        error = self.assert_fail(":bug: Fix crash\n\nBody.\n\nCo-authored-by: Someone <someone@example.com>\n")
        self.assertIn("Co-authored-by", error)

    def test_co_authored_by_case_insensitive_fails(self):
        self.assert_fail(":bug: Fix crash\n\nBody.\n\nco-authored-by: Someone <someone@example.com>\n")

    def test_co_authored_by_in_body_fails(self):
        self.assert_fail(":bug: Fix crash\n\nCo-authored-by: Someone <someone@example.com>\nBody.\n")

    def test_similar_trailer_passes(self):
        self.assert_ok(":bug: Fix crash\n\nBody.\n\nCo-developed-by: Someone <someone@example.com>\n")


class SubjectRulesRegressionTests(unittest.TestCase):
    """Guard the pre-existing validators against accidental breakage."""

    def test_valid_subject_passes_regex(self):
        ok, error = check_commit.check_regex(":bug: Fix crash on startup")
        self.assertTrue(ok, error)

    def test_missing_emoji_fails_regex(self):
        ok, _ = check_commit.check_regex("Fix crash on startup")
        self.assertFalse(ok)

    def test_trailing_dot_fails(self):
        ok, _ = check_commit.check_subject_no_trailing_dot(":bug: Fix crash.")
        self.assertFalse(ok)

    def test_subject_at_80_chars_passes(self):
        # ":bug: " is 6 chars, so 74 chars of text reach exactly 80.
        ok, error = check_commit.check_subject_length(":bug: " + "S" * 74)
        self.assertTrue(ok, error)

    def test_subject_over_80_chars_fails(self):
        ok, error = check_commit.check_subject_length(":bug: " + "S" * 75)
        self.assertFalse(ok)
        self.assertIn("80", error)

    def test_merge_subject_skips_length_check(self):
        # Git generates Merge subjects from branch names; the limit is skipped.
        subject = "Merge branch 'some-very-long-branch-name-that-keeps-going' into develop with extra text here"
        self.assertGreater(len(subject), 80)
        ok, error = check_commit.check_subject_length(subject)
        self.assertTrue(ok, error)

    def test_revert_subject_skips_length_check(self):
        subject = 'Revert ":bug: Fix some earlier commit with a fairly long subject line already here" extra'
        self.assertGreater(len(subject), 80)
        ok, error = check_commit.check_subject_length(subject)
        self.assertTrue(ok, error)

    def test_reapply_subject_skips_length_check(self):
        subject = 'Reapply ":bug: Fix some earlier commit with a fairly long subject line already here" extra'
        self.assertGreater(len(subject), 80)
        ok, error = check_commit.check_subject_length(subject)
        self.assertTrue(ok, error)


if __name__ == "__main__":
    unittest.main(verbosity=2)
