#!/usr/bin/env python3
"""Tests for scripts/gh.py.

Run with:

    python3 scripts/test_gh.py
"""

import contextlib
import importlib.machinery
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


# Loading scripts/gh.py should not emit scripts/__pycache__/.
sys.dont_write_bytecode = True

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent / "gh.py"


def load_gh():
    """Load scripts/gh.py as a module without running its CLI."""
    loader = importlib.machinery.SourceFileLoader("gh_helper", str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader("gh_helper", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


gh = load_gh()


class LinkIssueTests(unittest.TestCase):
    def setUp(self):
        self.target_response = {
            "repository": {
                "issue": {"id": "issue-id", "number": 11235},
                "pullRequest": {"id": "pr-id", "number": 11243},
            }
        }
        self.mutation_response = {
            "addCloseIssueReferences": {
                "issue": {"id": "issue-id", "number": 11235}
            }
        }

    @patch.object(gh, "run_gh_graphql")
    def test_link_issue_to_pr_adds_reference(self, run_graphql):
        run_graphql.side_effect = [
            self.target_response,
            self.mutation_response,
        ]

        result = gh.link_issue_to_pr(11235, 11243)

        self.assertTrue(result["linked"])
        self.assertEqual(result["issue"]["number"], 11235)
        self.assertEqual(result["pull_request"]["number"], 11243)
        self.assertEqual(run_graphql.call_count, 2)
        self.assertEqual(
            run_graphql.call_args_list[1].args[1],
            {"issueId": "issue-id", "pullRequestIds": ["pr-id"]},
        )

    @patch.object(gh, "run_gh_graphql")
    def test_link_issue_to_pr_fails_when_mutation_did_not_link(self, run_graphql):
        run_graphql.side_effect = [
            self.target_response,
            {"addCloseIssueReferences": {"issue": {"id": "issue-id", "number": 999}}},
        ]

        with self.assertRaisesRegex(RuntimeError, "did not link issue #11235"):
            gh.link_issue_to_pr(11235, 11243)

    @patch.object(gh, "link_issue_to_pr")
    def test_cmd_link_issue_outputs_result(self, link_issue):
        expected = {
            "linked": True,
            "issue": {"number": 11235},
            "pull_request": {"number": 11243},
        }
        link_issue.return_value = expected
        args = types.SimpleNamespace(issue_number=11235, pr_number=11243)
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            gh.cmd_link_issue(args)

        self.assertEqual(json.loads(stdout.getvalue()), expected)
        self.assertIn("Linked issue #11235", stderr.getvalue())
        link_issue.assert_called_once_with(11235, 11243)

    @patch.object(gh, "link_issue_to_pr", side_effect=RuntimeError("link missing"))
    def test_cmd_link_issue_exits_on_error(self, _link_issue):
        args = types.SimpleNamespace(issue_number=11235, pr_number=11243)
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_link_issue(args)

        self.assertEqual(error.exception.code, 1)
        self.assertIn("link missing", stderr.getvalue())


class CommentsTests(unittest.TestCase):
    def test_paginates_until_a_short_page(self):
        page_one = [
            {"id": i, "user": {"login": "u"}, "created_at": "t", "body": f"b{i}"}
            for i in range(100)
        ]
        page_two = [{"id": 100, "user": None, "created_at": "t", "body": "last"}]
        calls = []

        def fake_rest(path):
            calls.append(path)
            return page_one if len(calls) == 1 else page_two

        with patch.object(gh, "run_gh_rest", side_effect=fake_rest):
            comments = gh.fetch_issue_comments(7)

        self.assertEqual(len(comments), 101)
        self.assertEqual(
            comments[0],
            {"issue": 7, "id": 0, "author": "u", "created_at": "t", "body": "b0"},
        )
        self.assertIsNone(comments[-1]["author"])
        self.assertIn("page=1", calls[0])
        self.assertIn("page=2", calls[1])

    @patch.object(gh, "fetch_issue_comments")
    def test_cmd_comments_outputs_every_issue(self, fetch_comments):
        fetch_comments.side_effect = lambda num: [
            {"issue": num, "id": num, "author": "u", "created_at": "t", "body": "x"}
        ]
        args = types.SimpleNamespace(numbers=[1, 2], file=None, stdin=False)
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(io.StringIO()):
            gh.cmd_comments(args)

        self.assertEqual([c["issue"] for c in json.loads(stdout.getvalue())], [1, 2])

    def test_cmd_comments_requires_numbers(self):
        args = types.SimpleNamespace(numbers=[], file=None, stdin=False)
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_comments(args)
        self.assertEqual(error.exception.code, 1)


class AdvisoriesTests(unittest.TestCase):
    def advisory(self, patched, ghsa="GHSA-aaaa-bbbb-cccc"):
        return {
            "ghsa_id": ghsa,
            "severity": "medium",
            "state": "draft",
            "summary": "Something",
            "html_url": f"https://github.com/penpot/penpot/security/advisories/{ghsa}",
            "vulnerabilities": [
                {
                    "package": {"name": "penpot"},
                    "vulnerable_version_range": "<2.19.1",
                    "patched_versions": patched,
                }
            ],
        }

    def test_fixed_in_version_matches_exact_tokens(self):
        self.assertTrue(gh.advisory_fixed_in_version(self.advisory("2.19.1"), "2.19.1"))
        self.assertFalse(
            gh.advisory_fixed_in_version(self.advisory("2.19.10"), "2.19.1")
        )
        self.assertFalse(gh.advisory_fixed_in_version(self.advisory(""), "2.19.1"))
        self.assertFalse(
            gh.advisory_fixed_in_version({"vulnerabilities": []}, "2.19.1")
        )

    def test_fixed_in_version_accepts_several_tokens(self):
        self.assertTrue(
            gh.advisory_fixed_in_version(self.advisory("2.19.0, 2.19.1"), "2.19.1")
        )
        self.assertTrue(
            gh.advisory_fixed_in_version(self.advisory(" 2.19.1 "), "2.19.1")
        )

    @patch.object(gh, "fetch_advisories")
    def test_cmd_advisories_filters_by_patched_version(self, fetch_advisories):
        matching = self.advisory("2.19.1", "GHSA-j4vc-cmf6-jhr7")
        other = self.advisory("2.19.0", "GHSA-4mmc-j8rh-74pf")
        fetch_advisories.return_value = [matching, other]
        args = types.SimpleNamespace(
            ghsa_id=None,
            severity=None,
            state=None,
            patched_version="2.19.1",
        )
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(
            io.StringIO()
        ):
            gh.cmd_advisories(args)

        results = json.loads(stdout.getvalue())
        self.assertEqual([r["ghsa_id"] for r in results], ["GHSA-j4vc-cmf6-jhr7"])
        # The list output keeps the summary schema: raw vulnerabilities stay
        # internal to the filter.
        self.assertNotIn("vulnerabilities", results[0])
        self.assertEqual(
            results[0]["url"],
            "https://github.com/penpot/penpot/security/advisories/GHSA-j4vc-cmf6-jhr7",
        )


class MergeRenovatePrTests(unittest.TestCase):
    PR_TITLE = ":arrow_up: Update dhi.io/node image to v24.22.3"
    MERGE_OID = "c19f7a6e5d0d6108347d52f0372800bb41b06abe"

    # Same shape as a real Renovate branch commit: the third line is 85
    # characters long and the last one is the trailer GitHub duplicates.
    COMMIT_BODY = (
        "- dhi.io/node 24.22.3@ (docker/devenv/Dockerfile)\n"
        "- dhi.io/node 24.22.3@ (.nvmrc)\n"
        "- dhi.io/node 24.22.3-debian13-dev@aee95f1 "
        "(docker/images/Dockerfile.media-processor)\n"
        "\n"
        "Signed-off-by: penpot-renovate[bot] "
        "<338182062+penpot-renovate[bot]@users.noreply.github.com>\n"
        "Co-authored-by: penpot-renovate[bot] "
        "<338182062+penpot-renovate[bot]@users.noreply.github.com>\n"
    )

    def renovate_pr(self, **overrides):
        pr = {
            "number": 13031,
            "title": self.PR_TITLE,
            "state": "OPEN",
            "isDraft": False,
            "author": {"login": "app/penpot-renovate", "is_bot": True},
            "mergeable": "MERGEABLE",
            "reviewDecision": "APPROVED",
            "commits": [
                {
                    "oid": "0a36c25513cc11dc071f61381ff936ca4a0dabe0",
                    "messageHeadline": self.PR_TITLE,
                    "messageBody": self.COMMIT_BODY,
                }
            ],
        }
        pr.update(overrides)
        return pr

    def args(self, **overrides):
        values = {"number": 13031, "dry_run": False, "no_approval_check": False}
        values.update(overrides)
        return types.SimpleNamespace(**values)

    # ── message building ────────────────────────────────────

    def test_strip_co_authored_by_removes_the_bot_line(self):
        cleaned = gh.strip_co_authored_by(self.COMMIT_BODY)

        self.assertNotIn("Co-authored-by", cleaned)
        self.assertIn("Signed-off-by:", cleaned)

    def test_strip_co_authored_by_is_case_insensitive(self):
        cleaned = gh.strip_co_authored_by(
            "Body.\n\nco-authored-by: Someone <someone@example.com>\n"
        )

        self.assertEqual(cleaned, "Body.")

    def test_wrap_line_keeps_the_list_marker_with_hanging_indent(self):
        long_line = (
            "- dhi.io/node 24.22.3-debian13-dev@aee95f1 "
            "(docker/images/Dockerfile.media-processor)"
        )
        self.assertGreater(len(long_line), gh.MAX_BODY_LINE)

        wrapped = gh.wrap_line(long_line)

        self.assertGreater(len(wrapped), 1)
        for line in wrapped:
            self.assertLessEqual(len(line), gh.MAX_BODY_LINE)
        self.assertTrue(wrapped[0].startswith("- dhi.io/node "))
        self.assertTrue(all(line.startswith("  ") for line in wrapped[1:]))

    def test_wrap_line_leaves_trailers_and_urls_alone(self):
        trailer = (
            "Signed-off-by: penpot-renovate[bot] "
            "<338182062+penpot-renovate[bot]@users.noreply.github.com>"
        )
        url = (
            "See https://example.com/a/very/long/path/that/cannot/be/wrapped "
            "for details"
        )

        self.assertEqual(gh.wrap_line(trailer), [trailer])
        self.assertEqual(gh.wrap_line(url), [url])

    def test_wrap_line_leaves_a_long_token_alone(self):
        token = "x" * 100

        self.assertEqual(gh.wrap_line(token), [token])

    def test_wrap_commit_body_is_idempotent(self):
        once = gh.wrap_commit_body(self.COMMIT_BODY)
        twice = gh.wrap_commit_body(once)

        self.assertEqual(once, twice)

    def test_build_merge_message_uses_the_pr_title_and_cleans_the_body(self):
        subject, body = gh.build_merge_message(
            self.PR_TITLE, f"{self.PR_TITLE}\n\n{self.COMMIT_BODY}"
        )

        self.assertEqual(subject, self.PR_TITLE)
        self.assertNotIn("Co-authored-by", body)
        self.assertTrue(body.endswith(">"))
        for line in body.splitlines():
            if line.startswith("Signed-off-by:"):
                continue
            self.assertLessEqual(len(line), gh.MAX_BODY_LINE)

    def test_build_merge_message_is_idempotent(self):
        subject, body = gh.build_merge_message(
            self.PR_TITLE, f"{self.PR_TITLE}\n\n{self.COMMIT_BODY}"
        )
        message = gh.compose_commit_message(subject, body)

        subject_again, body_again = gh.build_merge_message(
            self.PR_TITLE, message
        )

        self.assertEqual(body, body_again)

    def test_compose_commit_message_keeps_a_blank_line(self):
        self.assertEqual(
            gh.compose_commit_message(":arrow_up: Update x", "Body.\n"),
            ":arrow_up: Update x\n\nBody.",
        )
        self.assertEqual(
            gh.compose_commit_message(":arrow_up: Update x", ""),
            ":arrow_up: Update x",
        )

    def test_generated_message_passes_check_commit(self):
        subject, body = gh.build_merge_message(
            self.PR_TITLE, f"{self.PR_TITLE}\n\n{self.COMMIT_BODY}"
        )
        message = gh.compose_commit_message(subject, body)

        with tempfile.TemporaryDirectory() as tmpdir:
            path = pathlib.Path(tmpdir) / "message.txt"
            path.write_text(message + "\n", encoding="utf-8")
            ok, output = gh.validate_commit_message_file(str(path))

        self.assertTrue(ok, output)

    # ── PR validation ───────────────────────────────────────

    def test_renovate_pr_problems_accepts_an_open_approved_pr(self):
        self.assertEqual(
            gh.renovate_pr_problems(
                self.renovate_pr(), dry_run=False, require_approval=True
            ),
            [],
        )

    def test_renovate_pr_problems_rejects_other_authors(self):
        problems = gh.renovate_pr_problems(
            self.renovate_pr(author={"login": "someone"}),
            dry_run=False,
            require_approval=False,
        )

        self.assertTrue(any("not opened by Renovate" in p for p in problems))

    def test_renovate_pr_problems_rejects_multiple_commits(self):
        pr = self.renovate_pr(commits=[{}, {}])

        problems = gh.renovate_pr_problems(
            pr, dry_run=False, require_approval=False
        )

        self.assertTrue(any("expected exactly 1" in p for p in problems))

    def test_renovate_pr_problems_requires_approval_unless_bypassed(self):
        pr = self.renovate_pr(reviewDecision="REVIEW_REQUIRED")

        self.assertTrue(
            any(
                "no approving review" in p
                for p in gh.renovate_pr_problems(
                    pr, dry_run=False, require_approval=True
                )
            )
        )
        self.assertEqual(
            gh.renovate_pr_problems(pr, dry_run=False, require_approval=False),
            [],
        )

    def test_renovate_pr_problems_skips_state_checks_on_dry_run(self):
        pr = self.renovate_pr(state="MERGED", reviewDecision="", isDraft=True)

        self.assertEqual(
            gh.renovate_pr_problems(pr, dry_run=True, require_approval=True),
            [],
        )

    # ── command flow ────────────────────────────────────────

    @patch.object(gh, "validate_commit_message_file", return_value=(True, ""))
    @patch.object(gh, "fetch_pull_request")
    def test_cmd_dry_run_prints_message_and_does_not_merge(
        self, fetch_pr, _validate
    ):
        fetch_pr.return_value = self.renovate_pr(state="MERGED")
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(
            stderr
        ):
            with patch.object(gh, "run_gh_command") as run_gh:
                gh.cmd_merge_renovate_pr(self.args(dry_run=True))

        self.assertIn(self.PR_TITLE, stdout.getvalue())
        self.assertIn("Signed-off-by:", stdout.getvalue())
        self.assertNotIn("Co-authored-by", stdout.getvalue())
        self.assertIn("gh pr merge 13031", stderr.getvalue())
        run_gh.assert_not_called()

    @patch.object(
        gh, "validate_commit_message_file", return_value=(False, "boom")
    )
    @patch.object(gh, "fetch_pull_request")
    def test_cmd_aborts_when_check_commit_fails(self, fetch_pr, _validate):
        fetch_pr.return_value = self.renovate_pr()
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_merge_renovate_pr(self.args())

        self.assertEqual(error.exception.code, 1)
        self.assertIn("boom", stderr.getvalue())

    @patch.object(gh, "run_gh_rest")
    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "validate_commit_message_file", return_value=(True, ""))
    @patch.object(gh, "fetch_pull_request")
    def test_cmd_merges_with_an_explicit_subject_and_body(
        self, fetch_pr, _validate, run_gh, run_rest
    ):
        fetch_pr.return_value = self.renovate_pr()
        run_gh.side_effect = [
            "",
            json.dumps({"mergeCommit": {"oid": self.MERGE_OID}}),
        ]
        run_rest.return_value = {
            "commit": {
                "message": gh.compose_commit_message(
                    self.PR_TITLE,
                    gh.build_merge_message(
                        self.PR_TITLE, f"{self.PR_TITLE}\n\n{self.COMMIT_BODY}"
                    )[1],
                )
            }
        }
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(
            stderr
        ):
            gh.cmd_merge_renovate_pr(self.args())

        merge_cmd = run_gh.call_args_list[0].args[0]
        self.assertEqual(merge_cmd[:4], ["gh", "pr", "merge", "13031"])
        self.assertIn("--squash", merge_cmd)
        self.assertIn("--subject", merge_cmd)
        self.assertIn(self.PR_TITLE, merge_cmd)
        self.assertIn("--body-file", merge_cmd)
        run_rest.assert_called_once_with(
            f"repos/penpot/penpot/commits/{self.MERGE_OID}"
        )

    @patch.object(gh, "run_gh_rest")
    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "validate_commit_message_file", return_value=(True, ""))
    @patch.object(gh, "fetch_pull_request")
    def test_cmd_warns_when_the_trailer_survives_the_merge(
        self, fetch_pr, _validate, run_gh, run_rest
    ):
        fetch_pr.return_value = self.renovate_pr()
        run_gh.side_effect = [
            "",
            json.dumps({"mergeCommit": {"oid": self.MERGE_OID}}),
        ]
        run_rest.return_value = {
            "commit": {
                "message": (
                    f"{self.PR_TITLE}\n\nBody.\n\n"
                    "Co-authored-by: penpot-renovate[bot] <bot@example.com>"
                )
            }
        }
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(
            stderr
        ):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_merge_renovate_pr(self.args())

        self.assertEqual(error.exception.code, 2)
        self.assertIn(
            "still contains a Co-authored-by trailer", stderr.getvalue()
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
