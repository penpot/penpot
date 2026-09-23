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
        self.verification_response = {
            "repository": {
                "issue": {
                    "number": 11235,
                    "state": "OPEN",
                    "closedByPullRequestsReferences": {
                        "nodes": [
                            {
                                "number": 11243,
                                "state": "MERGED",
                                "url": "https://github.com/penpot/penpot/pull/11243",
                            }
                        ]
                    },
                },
                "pullRequest": {
                    "number": 11243,
                    "state": "MERGED",
                    "closingIssuesReferences": {
                        "nodes": [
                            {
                                "number": 11235,
                                "state": "OPEN",
                                "url": "https://github.com/penpot/penpot/issues/11235",
                            }
                        ]
                    },
                },
            }
        }

    @patch.object(gh, "run_gh_graphql")
    def test_link_issue_to_pr_adds_and_verifies_reference(self, run_graphql):
        run_graphql.side_effect = [
            self.target_response,
            self.mutation_response,
            self.verification_response,
        ]

        result = gh.link_issue_to_pr(11235, 11243)

        self.assertTrue(result["linked"])
        self.assertEqual(
            result["issue"]["linked_pull_requests"][0]["number"],
            11243,
        )
        self.assertEqual(
            result["pull_request"]["linked_issues"][0]["number"],
            11235,
        )
        self.assertEqual(run_graphql.call_count, 3)
        self.assertEqual(
            run_graphql.call_args_list[1].args[1],
            {"issueId": "issue-id", "pullRequestIds": ["pr-id"]},
        )

    @patch.object(gh, "run_gh_graphql")
    def test_link_issue_to_pr_fails_when_verification_is_missing(self, run_graphql):
        self.verification_response["repository"]["issue"][
            "closedByPullRequestsReferences"
        ]["nodes"] = []
        run_graphql.side_effect = [
            self.target_response,
            self.mutation_response,
            self.verification_response,
        ]

        with self.assertRaisesRegex(RuntimeError, "are not linked"):
            gh.link_issue_to_pr(11235, 11243)

    @patch.object(gh, "link_issue_to_pr")
    def test_cmd_link_issue_outputs_verified_result(self, link_issue):
        expected = {
            "linked": True,
            "issue": {"number": 11235, "state": "OPEN"},
            "pull_request": {"number": 11243, "state": "MERGED"},
        }
        link_issue.return_value = expected
        args = types.SimpleNamespace(issue_number=11235, pr_number=11243)
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            gh.cmd_link_issue(args)

        self.assertEqual(json.loads(stdout.getvalue()), expected)
        self.assertIn("Verified issue #11235", stderr.getvalue())
        link_issue.assert_called_once_with(11235, 11243)

    @patch.object(gh, "link_issue_to_pr", side_effect=RuntimeError("link missing"))
    def test_cmd_link_issue_fails_when_verification_is_missing(self, _link_issue):
        args = types.SimpleNamespace(issue_number=11235, pr_number=11243)
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_link_issue(args)

        self.assertEqual(error.exception.code, 1)
        self.assertIn("link missing", stderr.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
