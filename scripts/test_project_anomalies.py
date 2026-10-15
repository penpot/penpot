#!/usr/bin/env python3
"""Tests for scripts/project-anomalies.py (pure logic, no network).

Run with:

    python3 scripts/test_project_anomalies.py
"""

import importlib.machinery
import importlib.util
import pathlib
import sys
import unittest


# Loading scripts/project-anomalies.py should not emit scripts/__pycache__/.
sys.dont_write_bytecode = True

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent / "project-anomalies.py"


def load_pa():
    """Load scripts/project-anomalies.py as a module without running its CLI."""
    loader = importlib.machinery.SourceFileLoader("project_anomalies", str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader("project_anomalies", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


pa = load_pa()

M = "2.17.0"


def issue(n, **kw):
    base = {
        "number": n,
        "title": f"issue {n}",
        "state": "CLOSED",
        "milestone": M,
        "labels": [],
        "assignees": ["someone"],
        "closing_prs": [],
        "project_status": "Done",
        "projects": ["Main"],
    }
    base.update(kw)
    return base


def pr(n, **kw):
    base = {
        "number": n,
        "title": f"pr {n}",
        "state": "MERGED",
        "milestone": M,
        "labels": [],
        "assignees": ["someone"],
        "closing_issues": [],
    }
    base.update(kw)
    return base


def run(issues, prs):
    return pa.find_anomalies(
        M, issues, prs,
        {p["number"]: p for p in prs},
        {i["number"]: i for i in issues},
    )


class OpenMergedTests(unittest.TestCase):
    def test_open_issue_with_merged_pr_is_flagged(self):
        out = run(
            [issue(1, state="OPEN", closing_prs=[11])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(len(out["open_merged"]), 1)
        self.assertEqual(out["open_merged"][0]["prs"], [11])

    def test_open_issue_with_unmerged_pr_is_clean(self):
        out = run(
            [issue(1, state="OPEN", closing_prs=[11])],
            [pr(11, state="OPEN", closing_issues=[1])],
        )
        self.assertEqual(out["open_merged"], [])

    def test_closed_issue_with_merged_pr_is_clean(self):
        out = run(
            [issue(1, closing_prs=[11])],
            [pr(11, closing_issues=[1])],
        )
        self.assertTrue(all(v == [] for v in out.values()))


class IssuePrMismatchTests(unittest.TestCase):
    def test_pr_in_other_milestone_is_flagged(self):
        out = run(
            [issue(1, closing_prs=[11])],
            [pr(11, milestone="2.18.0")],
        )
        self.assertEqual(len(out["issue_pr"]), 1)
        self.assertEqual(out["issue_pr"][0]["pr_milestone"], "2.18.0")

    def test_pr_without_milestone_is_flagged(self):
        out = run(
            [issue(1, closing_prs=[11])],
            [pr(11, milestone=None)],
        )
        self.assertEqual(len(out["issue_pr"]), 1)

    def test_unmerged_pr_milestone_is_ignored(self):
        # An OPEN (unmerged) PR has landed nowhere: its milestone is
        # irrelevant, even when it differs.
        out = run(
            [issue(1, closing_prs=[11])],
            [pr(11, state="OPEN", milestone="2.18.0")],
        )
        self.assertEqual(out["issue_pr"], [])

    def test_off_board_issue_flagged_despite_unmerged_pr(self):
        # The project check does not depend on PR merge state.
        out = run(
            [issue(1, projects=["Other"], closing_prs=[11])],
            [pr(11, state="OPEN", milestone="2.18.0")],
        )
        self.assertEqual(len(out["issue_pr"]), 1)
        self.assertIsNone(out["issue_pr"][0]["pr"])

    def test_issue_off_main_board_is_flagged_without_pr(self):
        out = run([issue(1, projects=["Other"], project_status=None)], [])
        self.assertEqual(len(out["issue_pr"]), 1)
        self.assertIsNone(out["issue_pr"][0]["pr"])

    def test_aligned_pair_is_clean(self):
        out = run(
            [issue(1, closing_prs=[11])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(out["issue_pr"], [])
        self.assertEqual(out["pr_issue"], [])


class PrIssueMismatchTests(unittest.TestCase):
    def test_issue_in_other_milestone_is_flagged(self):
        other = issue(2, milestone="2.16.0")
        out = pa.find_anomalies(
            M, [issue(1)], [pr(11, closing_issues=[2])],
            {11: pr(11, closing_issues=[2])}, {1: issue(1), 2: other},
        )
        self.assertEqual(len(out["pr_issue"]), 1)

    def test_issue_without_milestone_is_flagged(self):
        # Unlike the changelog flow, here a milestone-less issue closed by a
        # milestone PR is reported: the pairing needs human judgment.
        other = issue(2, milestone=None, projects=[], project_status=None)
        out = pa.find_anomalies(
            M, [issue(1)], [pr(11, closing_issues=[2])],
            {11: pr(11, closing_issues=[2])}, {1: issue(1), 2: other},
        )
        self.assertEqual(len(out["pr_issue"]), 1)

    def test_issue_off_main_board_is_flagged(self):
        other = issue(2, projects=["Other"], project_status=None)
        out = pa.find_anomalies(
            M, [issue(1)], [pr(11, closing_issues=[2])],
            {11: pr(11, closing_issues=[2])}, {1: issue(1), 2: other},
        )
        self.assertEqual(len(out["pr_issue"]), 1)

    def test_milestone_issue_keeps_milestone_in_record(self):
        # Milestone issues carry no "milestone" key (known by construction);
        # an off-board one must still render its own milestone, never _none_.
        mine = issue(1, projects=["Other"], project_status=None)
        del mine["milestone"]
        out = pa.find_anomalies(
            M, [mine], [pr(11, closing_issues=[1])],
            {11: pr(11, closing_issues=[1])}, {1: mine},
        )
        self.assertEqual(len(out["pr_issue"]), 1)
        self.assertEqual(out["pr_issue"][0]["issue_milestone"], M)
        report = pa.render_report(M, out, 1, 0, 1, 1)
        self.assertNotIn("gh issue edit 1", report)

    def test_unmerged_pr_is_ignored(self):
        # Closed-unmerged (or open) milestone PRs take no part in milestone
        # pairing: only merged PRs landed somewhere.
        other = issue(2, milestone="2.16.0")
        for state in ("OPEN", "CLOSED"):
            out = pa.find_anomalies(
                M, [issue(1)], [pr(11, state=state, closing_issues=[2])],
                {11: pr(11, state=state, closing_issues=[2])},
                {1: issue(1), 2: other},
            )
            self.assertEqual(out["pr_issue"], [], f"state={state}")


class NeedsTriageTests(unittest.TestCase):
    def test_merged_pr_on_triaged_issue_is_clean(self):
        out = run(
            [issue(1, labels=["bug"])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(out["needs_triage"], [])

    def test_merged_pr_on_needs_triage_issue_is_flagged(self):
        out = run(
            [issue(1, labels=["needs triage"])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(len(out["needs_triage"]), 1)

    def test_unmerged_pr_on_needs_triage_issue_is_clean(self):
        out = run(
            [issue(1, labels=["needs triage"])],
            [pr(11, state="OPEN", closing_issues=[1])],
        )
        self.assertEqual(out["needs_triage"], [])


class UnassignedTests(unittest.TestCase):
    def test_assigned_issue_is_clean(self):
        out = run(
            [issue(1, assignees=["owner"])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(out["unassigned"], [])

    def test_unassigned_community_issue_is_clean(self):
        out = run(
            [issue(1, assignees=[], labels=["community contribution"])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(out["unassigned"], [])

    def test_unassigned_community_pr_is_clean(self):
        out = run(
            [issue(1, assignees=[])],
            [pr(11, closing_issues=[1], labels=["community contribution"])],
        )
        self.assertEqual(out["unassigned"], [])

    def test_unassigned_non_community_issue_is_flagged(self):
        out = run(
            [issue(1, assignees=[])],
            [pr(11, closing_issues=[1])],
        )
        self.assertEqual(len(out["unassigned"]), 1)

    def test_unmerged_pr_is_clean(self):
        out = run(
            [issue(1, assignees=[])],
            [pr(11, state="CLOSED", closing_issues=[1])],
        )
        self.assertEqual(out["unassigned"], [])


class RenderTests(unittest.TestCase):
    def test_clean_report_says_so(self):
        out = run([issue(1, closing_prs=[11])], [pr(11, closing_issues=[1])])
        report = pa.render_report(M, out, 1, 0, 1, 1)
        self.assertIn("Total anomalies:** 0", report)
        self.assertIn("✅ No anomalies", report)

    def test_every_number_is_a_clickable_link(self):
        out = run(
            [issue(1, state="OPEN", closing_prs=[11])],
            [pr(11, closing_issues=[1])],
        )
        report = pa.render_report(M, out, 1, 0, 1, 1)
        self.assertIn("[#1](https://github.com/penpot/penpot/issues/1)", report)
        self.assertIn("[#11](https://github.com/penpot/penpot/pull/11)", report)


if __name__ == "__main__":
    unittest.main(verbosity=2)
