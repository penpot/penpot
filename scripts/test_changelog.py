#!/usr/bin/env python3
"""Tests for scripts/changelog.py.

Run with:

    python3 scripts/test_changelog.py
"""

import argparse
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch


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


ch = load_changelog()

CHANGES = """# CHANGELOG

## 2.19.0 (Unreleased)

### :boom: Breaking changes & Deprecations

- Old entry.

### :rocket: Epics and highlights

- Add highlights [#1](https://github.com/penpot/penpot/issues/1)
"""


class ExtractBreakingNoteTests(unittest.TestCase):
    def test_uses_the_whole_comment_after_the_marker(self):
        body = (
            "## Breaking change note\n\n"
            "First paragraph.\n\n"
            "- bullet one\n"
            "- bullet two\n\n"
            "```sql\nSELECT 1;\n```\n"
        )
        self.assertEqual(
            ch.extract_breaking_note(body),
            "First paragraph.\n\n- bullet one\n- bullet two\n\n"
            "```sql\nSELECT 1;\n```",
        )

    def test_accepts_any_heading_level_for_the_marker(self):
        self.assertEqual(
            ch.extract_breaking_note("# breaking change note\n\nText."), "Text."
        )
        self.assertEqual(
            ch.extract_breaking_note("### Breaking Change Note\n\nText."), "Text."
        )

    def test_returns_none_without_marker(self):
        self.assertIsNone(ch.extract_breaking_note("### Description\n\ntext\n"))
        self.assertIsNone(ch.extract_breaking_note("## Breaking change notes\n\ntext"))
        self.assertIsNone(ch.extract_breaking_note(None))
        self.assertIsNone(ch.extract_breaking_note(""))

    def test_returns_none_for_a_marker_without_content(self):
        self.assertIsNone(ch.extract_breaking_note("## Breaking change note\n"))


class DemoteHeadingsTests(unittest.TestCase):
    def test_demotes_headings_below_the_entry_level(self):
        markdown = "# One\n## Two\n### Three\n#### Four\n##### Five\n"
        self.assertEqual(
            ch.demote_headings(markdown),
            "##### One\n##### Two\n##### Three\n##### Four\n##### Five",
        )

    def test_keeps_headings_inside_code_fences(self):
        markdown = (
            "```bash\n# not a heading\n```\n"
            "## Real heading\n"
            "~~~\n### not a heading\n~~~\n"
        )
        self.assertEqual(
            ch.demote_headings(markdown),
            "```bash\n# not a heading\n```\n"
            "##### Real heading\n"
            "~~~\n### not a heading\n~~~",
        )


class StripTitlePrefixTests(unittest.TestCase):
    def test_strips_shortcodes(self):
        self.assertEqual(ch.strip_title_prefix(":boom: Enable it"), "Enable it")
        self.assertEqual(ch.strip_title_prefix(":bug: :tada: Fix it"), "Fix it")
        self.assertEqual(ch.strip_title_prefix("Plain title"), "Plain title")


class IssueExcludedTests(unittest.TestCase):
    def issue(self, **overrides):
        base = {
            "state": "CLOSED",
            "issue_type": "Enhancement",
            "project_status": "In progress",
            "labels": [],
        }
        base.update(overrides)
        return base

    def test_keeps_a_valid_closed_issue(self):
        self.assertFalse(ch.issue_excluded(self.issue()))

    def test_excludes_open_task_rejected_and_labelled(self):
        self.assertTrue(ch.issue_excluded(None))
        self.assertTrue(ch.issue_excluded(self.issue(state="OPEN")))
        self.assertTrue(ch.issue_excluded(self.issue(issue_type="Task")))
        self.assertTrue(ch.issue_excluded(self.issue(project_status="Rejected")))
        self.assertTrue(ch.issue_excluded(self.issue(labels=["release blocker"])))
        self.assertTrue(ch.issue_excluded(self.issue(labels=["no changelog"])))


class RenderBoomEntryTests(unittest.TestCase):
    def test_entry_is_a_heading_plus_note_plus_references(self):
        issue = {"number": 7, "title": ":boom: Drop the flag", "labels": []}
        self.assertEqual(
            ch.render_boom_entry(issue, "One.\n\nTwo.", [8, 9]),
            "#### Drop the flag\n\nOne.\n\nTwo.\n\n"
            "[#7](https://github.com/penpot/penpot/issues/7)"
            " (PR: [#8](https://github.com/penpot/penpot/pull/8),"
            " [#9](https://github.com/penpot/penpot/pull/9))",
        )

    def test_entry_without_a_note_keeps_the_heading(self):
        issue = {"number": 7, "title": "Drop the flag", "labels": []}
        self.assertEqual(
            ch.render_boom_entry(issue, None, []),
            "#### Drop the flag\n\n[#7](https://github.com/penpot/penpot/issues/7)",
        )

    def test_community_attribution_goes_before_the_issue_link(self):
        issue = {"number": 7, "title": "Drop the flag", "labels": []}
        entry = ch.render_boom_entry(issue, "Text.", [8], "someone")
        self.assertEqual(
            entry,
            "#### Drop the flag\n\nText.\n\n(by @someone)"
            " [#7](https://github.com/penpot/penpot/issues/7)"
            " (PR: [#8](https://github.com/penpot/penpot/pull/8))",
        )


class ReplaceBoomSectionTests(unittest.TestCase):
    def test_replaces_the_existing_subsection(self):
        entry = "#### New entry\n\nSome text.\n\n[#9](https://example.test/9)"
        updated = ch.replace_boom_section(CHANGES, "2.19.0", [entry])
        self.assertIn(
            "### :boom: Breaking changes & Deprecations\n\n"
            f"{entry}\n\n### :rocket:",
            updated,
        )
        self.assertNotIn("Old entry.", updated)

    def test_replacement_is_idempotent(self):
        entry = "#### New entry\n\nSome text.\n\n[#9](https://example.test/9)"
        once = ch.replace_boom_section(CHANGES, "2.19.0", [entry])
        self.assertEqual(once, ch.replace_boom_section(once, "2.19.0", [entry]))

    def test_empty_section_keeps_the_heading(self):
        updated = ch.replace_boom_section(CHANGES, "2.19.0", [])
        self.assertIn(
            "### :boom: Breaking changes & Deprecations\n\n### :rocket:", updated
        )

    def test_inserts_the_subsection_when_missing(self):
        without = CHANGES.replace(
            "### :boom: Breaking changes & Deprecations\n\n- Old entry.\n\n", ""
        )
        updated = ch.replace_boom_section(without, "2.19.0", ["#### New entry"])
        self.assertIn(
            "## 2.19.0 (Unreleased)\n\n"
            "### :boom: Breaking changes & Deprecations\n\n#### New entry\n\n"
            "### :rocket:",
            updated,
        )

    def test_unknown_version_raises(self):
        with self.assertRaises(ValueError):
            ch.replace_boom_section(CHANGES, "9.9.9", ["#### Entry"])


ISSUES = [
    {
        "number": 21,
        "title": ":boom: New default",
        "state": "CLOSED",
        "issue_type": "Enhancement",
        "labels": ["breaking change"],
        "closing_prs": [31],
        "project_status": "Done",
    },
    {
        "number": 22,
        "title": "No note",
        "state": "CLOSED",
        "issue_type": "Enhancement",
        "labels": ["breaking change"],
        "closing_prs": [],
        "project_status": "Done",
    },
    {
        "number": 23,
        "title": "Still open",
        "state": "OPEN",
        "issue_type": "Enhancement",
        "labels": ["breaking change"],
        "closing_prs": [],
        "project_status": "In progress",
    },
    {
        "number": 24,
        "title": "Not breaking",
        "state": "CLOSED",
        "issue_type": "Bug",
        "labels": [],
        "closing_prs": [],
        "project_status": "Done",
    },
]

COMMENTS = [
    {"issue": 21, "body": "## Breaking change note\n\nDeploy the new service.\n"},
    {"issue": 22, "body": "## Description\n\nNo marker here.\n"},
    {"issue": 21, "body": "## Breaking change note\n\nDeploy the new service.\n"},
]

PRS = [{"number": 31, "author": "someone", "labels": [], "state": "MERGED"}]


class CmdBoomTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.issues = ISSUES
        self.comments = COMMENTS
        self.prs = PRS

        def fake_run_gh_py(*args, input_text=None):
            self.calls.append(args)
            if args[0] == "issues":
                return json.dumps(self.issues)
            if args[0] == "comments":
                return json.dumps(self.comments)
            if args[0] == "prs":
                return json.dumps(self.prs)
            raise AssertionError(f"unexpected gh.py call: {args}")

        patcher = patch.object(ch, "run_gh_py", side_effect=fake_run_gh_py)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_boom(self, **kwargs):
        args = argparse.Namespace(
            milestone="2.19.0",
            changes=CHANGES if "changes" not in kwargs else kwargs["changes"],
            write=kwargs.get("write", False),
        )
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            ch.cmd_boom(args)
        return out.getvalue(), err.getvalue()

    def test_renders_only_closed_breaking_issues(self):
        out, err = self.run_boom()
        expected = (
            "### :boom: Breaking changes & Deprecations\n\n"
            "#### New default\n\n"
            "Deploy the new service.\n\n"
            "[#21](https://github.com/penpot/penpot/issues/21)"
            " (PR: [#31](https://github.com/penpot/penpot/pull/31))\n\n"
            "#### No note\n\n"
            "[#22](https://github.com/penpot/penpot/issues/22)\n"
        )
        self.assertEqual(out, expected)
        self.assertIn("issue #22 has no", err)
        self.assertIn("using the last one", err)

    def test_unmerged_closing_pr_is_not_referenced(self):
        self.prs = [{"number": 31, "author": "someone", "state": "CLOSED"}]
        out, _ = self.run_boom()
        self.assertNotIn("pull/31", out)
        self.assertIn("[#21](https://github.com/penpot/penpot/issues/21)", out)

    def test_no_labelled_issue_clears_the_section_on_write(self):
        self.issues = [ISSUES[3]]
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CHANGES.md"
            path.write_text(CHANGES)
            out, err = self.run_boom(changes=str(path), write=True)
            updated = path.read_text()
        self.assertEqual(out, "### :boom: Breaking changes & Deprecations\n")
        self.assertNotIn("Old entry.", updated)
        self.assertIn(":boom: section updated", err)

    def test_writes_the_section_in_place(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CHANGES.md"
            path.write_text(CHANGES)
            out, err = self.run_boom(changes=str(path), write=True)
            updated = path.read_text()
        self.assertIn("#### New default", updated)
        self.assertNotIn("Old entry.", updated)
        self.assertIn(":boom: section updated", err)
        self.assertIn("Deploy the new service.", out)


class ReportTests(unittest.TestCase):
    """Focused check of the `:boom:` sync anomaly (type G) in the report."""

    def test_reports_breaking_issue_missing_from_boom(self):
        issues = [
            {
                "number": 1,
                "title": "Unlabelled entry",
                "state": "CLOSED",
                "issue_type": "Enhancement",
                "labels": [],
                "closing_prs": [],
                "project_status": "Done",
            },
            {
                "number": 2,
                "title": "Labelled, not listed",
                "state": "CLOSED",
                "issue_type": "Enhancement",
                "labels": ["breaking change"],
                "closing_prs": [],
                "project_status": "Done",
            },
        ]
        changes = (
            "# CHANGELOG\n\n## 2.19.0 (Unreleased)\n\n"
            "### :boom: Breaking changes & Deprecations\n\n"
            "#### Something changed\n\n"
            "[#1](https://github.com/penpot/penpot/issues/1)\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            changes_path = pathlib.Path(tmp) / "CHANGES.md"
            output_path = pathlib.Path(tmp) / "ISSUES.md"
            changes_path.write_text(changes)
            args = argparse.Namespace(
                milestone="2.19.0",
                changes=str(changes_path),
                output=str(output_path),
            )
            with patch.object(ch, "run_gh_py", return_value=json.dumps(issues)), \
                    patch.object(ch, "fetch_milestone_prs", return_value=[]), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                ch.cmd_report(args)
            report = output_path.read_text()

        self.assertIn("**:boom: out of sync with breaking change label:** 1", report)
        self.assertIn("### :boom: out of sync with breaking change label", report)
        self.assertIn("issues/2", report)
        self.assertIn("**:boom: entry without breaking change label:** 1", report)


ADVISORY = {
    "ghsa_id": "GHSA-j4vc-cmf6-jhr7",
    "url": "https://github.com/penpot/penpot/security/advisories/GHSA-j4vc-cmf6-jhr7",
    "summary": (
        "Missing authorization in mark-all-threads-as-read lets a user update "
        "inaccessible threads"
    ),
}

SECURITY_CHANGES = """# CHANGELOG

## 2.19.1

### :bug: Bugs fixed

- Fix something [#1](https://github.com/penpot/penpot/issues/1)
"""

SECURITY_CHANGES_WITH_ROCKET = """# CHANGELOG

## 2.19.1

### :rocket: Epics and highlights

- Add highlights [#1](https://github.com/penpot/penpot/issues/1)

### :bug: Bugs fixed

- Fix something [#2](https://github.com/penpot/penpot/issues/2)
"""

SECURITY_CHANGES_WITH_BOOM = """# CHANGELOG

## 2.19.1

### :boom: Breaking changes & Deprecations

#### Old entry

[#1](https://github.com/penpot/penpot/issues/1)

### :bug: Bugs fixed

- Fix something [#2](https://github.com/penpot/penpot/issues/2)
"""


class ImperativeDescriptionTests(unittest.TestCase):
    def test_prefixes_fix_and_decapitalizes(self):
        self.assertEqual(
            ch.imperative_description("Missing authorization in foo"),
            "Fix missing authorization in foo",
        )

    def test_keeps_acronyms(self):
        self.assertEqual(
            ch.imperative_description("SVG sanitizer escapes nothing"),
            "Fix SVG sanitizer escapes nothing",
        )
        self.assertEqual(
            ch.imperative_description("CVE-2026-105694: sanitizer escapes nothing"),
            "Fix CVE-2026-105694: sanitizer escapes nothing",
        )

    def test_strips_emoji_prefix_and_handles_empty(self):
        self.assertEqual(
            ch.imperative_description(":bug: Missing check"), "Fix missing check"
        )
        self.assertEqual(ch.imperative_description(""), "")


class RenderSecurityEntryTests(unittest.TestCase):
    def test_entry_has_description_advisory_and_prs(self):
        self.assertEqual(
            ch.render_security_entry(ADVISORY, [8, 9]),
            "- Fix missing authorization in mark-all-threads-as-read lets a user "
            "update inaccessible threads "
            "(https://github.com/penpot/penpot/security/advisories/GHSA-j4vc-cmf6-jhr7)"
            " (PR: [#8](https://github.com/penpot/penpot/pull/8),"
            " [#9](https://github.com/penpot/penpot/pull/9))",
        )

    def test_entry_without_prs_links_only_the_advisory(self):
        entry = ch.render_security_entry(ADVISORY, [])
        self.assertNotIn("PR:", entry)
        self.assertIn("security/advisories/GHSA-j4vc-cmf6-jhr7", entry)

    def test_entry_builds_the_url_when_the_advisory_lacks_it(self):
        advisory = dict(ADVISORY)
        del advisory["url"]
        self.assertIn(
            "(https://github.com/penpot/penpot/security/advisories/GHSA-j4vc-cmf6-jhr7)",
            ch.render_security_entry(advisory, []),
        )


class FetchSecurityPrsTests(unittest.TestCase):
    def test_maps_merged_pr_pt_prs_by_ghsa(self):
        prs = [
            {
                "number": 10,
                "state": "MERGED",
                "labels": ["internal", "pr pt"],
                "body": "Relates to [GHSA-4mmc-j8rh-74pf]"
                "(https://github.com/penpot/penpot/security/advisories/GHSA-4mmc-j8rh-74pf)",
            },
            {
                "number": 11,
                "state": "CLOSED",
                "labels": ["pr pt"],
                "body": "Closes GHSA-j4vc-cmf6-jhr7",
            },
            {
                "number": 12,
                "state": "MERGED",
                "labels": ["bug"],
                "body": "GHSA-qwhj-rchj-58mg",
            },
            {
                "number": 13,
                "state": "MERGED",
                "labels": ["pr pt"],
                "body": "No advisory here",
            },
        ]
        with patch.object(ch, "fetch_milestone_prs", return_value=prs):
            result = ch.fetch_security_prs("2.19.1")
        self.assertEqual(result, {"GHSA-4MMC-J8RH-74PF": [10]})


class ReplaceSecuritySectionTests(unittest.TestCase):
    ENTRY = (
        "- Fix missing check (https://example.test/ghsa)"
        " (PR: [#8](https://example.test/pr/8))"
    )

    def test_inserts_after_rocket(self):
        updated = ch.replace_security_section(
            SECURITY_CHANGES_WITH_ROCKET, "2.19.1", [self.ENTRY]
        )
        self.assertIn(
            "### :rocket: Epics and highlights\n\n"
            "- Add highlights [#1](https://github.com/penpot/penpot/issues/1)\n\n"
            "### :lock: Security fixes\n\n"
            f"{self.ENTRY}\n\n"
            "### :bug: Bugs fixed",
            updated,
        )

    def test_inserts_after_boom_when_there_is_no_rocket(self):
        updated = ch.replace_security_section(
            SECURITY_CHANGES_WITH_BOOM, "2.19.1", [self.ENTRY]
        )
        self.assertIn(
            "#### Old entry\n\n[#1](https://github.com/penpot/penpot/issues/1)\n\n"
            "### :lock: Security fixes\n\n"
            f"{self.ENTRY}\n\n"
            "### :bug: Bugs fixed",
            updated,
        )

    def test_inserts_after_the_header_when_no_anchor_exists(self):
        updated = ch.replace_security_section(
            SECURITY_CHANGES, "2.19.1", [self.ENTRY]
        )
        self.assertIn(
            "## 2.19.1\n\n"
            "### :lock: Security fixes\n\n"
            f"{self.ENTRY}\n\n"
            "### :bug: Bugs fixed",
            updated,
        )

    def test_replacement_is_idempotent(self):
        once = ch.replace_security_section(
            SECURITY_CHANGES, "2.19.1", [self.ENTRY]
        )
        twice = ch.replace_security_section(once, "2.19.1", [self.ENTRY])
        self.assertEqual(once, twice)
        self.assertEqual(once.count("### :lock: Security fixes"), 1)

    def test_empty_entries_remove_the_subsection(self):
        once = ch.replace_security_section(
            SECURITY_CHANGES, "2.19.1", [self.ENTRY]
        )
        removed = ch.replace_security_section(once, "2.19.1", [])
        self.assertNotIn(":lock:", removed)
        self.assertEqual(removed, SECURITY_CHANGES)

    def test_empty_entries_leave_a_section_without_heading_untouched(self):
        self.assertEqual(
            ch.replace_security_section(SECURITY_CHANGES, "2.19.1", []),
            SECURITY_CHANGES,
        )

    def test_unknown_version_raises(self):
        with self.assertRaises(ValueError):
            ch.replace_security_section(SECURITY_CHANGES, "9.9.9", [self.ENTRY])


class CmdSecurityTests(unittest.TestCase):
    ADVISORIES = [ADVISORY]
    PRS = [
        {
            "number": 8,
            "state": "MERGED",
            "labels": ["pr pt"],
            "body": "Closes GHSA-j4vc-cmf6-jhr7",
        }
    ]

    def setUp(self):
        self.calls = []

        def fake_run_gh_py(*args, input_text=None):
            self.calls.append(args)
            if args[0] == "advisories":
                return json.dumps(self.ADVISORIES)
            if args[0] == "prs":
                return json.dumps(self.PRS)
            raise AssertionError(f"unexpected gh.py call: {args}")

        patcher = patch.object(ch, "run_gh_py", side_effect=fake_run_gh_py)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_security(self, changes=SECURITY_CHANGES, write=False):
        args = argparse.Namespace(milestone="2.19.1", changes=changes, write=write)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            ch.cmd_security(args)
        return out.getvalue(), err.getvalue()

    def test_prints_the_section_with_the_linked_pr(self):
        out, _ = self.run_security()
        self.assertIn("### :lock: Security fixes", out)
        self.assertIn("security/advisories/GHSA-j4vc-cmf6-jhr7", out)
        self.assertIn("(PR: [#8](https://github.com/penpot/penpot/pull/8))", out)
        self.assertEqual(
            self.calls[0], ("advisories", "--patched-version", "2.19.1")
        )

    def test_no_advisories_prints_no_section(self):
        self.ADVISORIES = []
        self.PRS = []
        out, err = self.run_security()
        self.assertEqual(out, "")
        self.assertIn("No advisories", err)

    def test_writes_the_section_in_place(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CHANGES.md"
            path.write_text(SECURITY_CHANGES)
            _, err = self.run_security(changes=str(path), write=True)
            updated = path.read_text()
        self.assertIn("### :lock: Security fixes", updated)
        self.assertIn("(PR: [#8](https://github.com/penpot/penpot/pull/8))", updated)
        self.assertIn(":lock: section updated", err)

    def test_no_advisories_leaves_the_file_untouched(self):
        self.ADVISORIES = []
        self.PRS = []
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "CHANGES.md"
            path.write_text(SECURITY_CHANGES)
            _, err = self.run_security(changes=str(path), write=True)
            updated = path.read_text()
        self.assertEqual(updated, SECURITY_CHANGES)
        self.assertIn(":lock: section already up to date", err)

    def test_warns_when_a_pr_references_an_unmatched_advisory(self):
        self.ADVISORIES = []
        _, err = self.run_security()
        self.assertIn("GHSA-J4VC-CMF6-JHR7", err)
        self.assertIn("#8", err)
        self.assertIn("will not be listed", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
