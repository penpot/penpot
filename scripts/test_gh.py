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


class CreateIssueTests(unittest.TestCase):
    def setUp(self):
        self.url = "https://github.com/penpot/penpot/issues/13202"
        self.create_response = self.url + "\n"
        self.node_response = {"repository": {"issue": {"id": "issue-node-id"}}}
        self.mutation_response = {
            "updateIssue": {
                "issue": {"number": 13202, "issueType": {"name": "Task"}}
            }
        }

    @patch.object(gh, "run_gh_graphql")
    @patch.object(gh, "run_gh_command")
    def test_create_issue_sets_type_and_returns_result(self, run_command, run_graphql):
        run_command.return_value = self.create_response
        run_graphql.side_effect = [self.node_response, self.mutation_response]

        result = gh.create_issue("A title", "/tmp/body.md", "task")

        self.assertEqual(
            result,
            {
                "number": 13202,
                "url": self.url,
                "title": "A title",
                "issue_type": "task",
            },
        )
        self.assertEqual(
            run_command.call_args.args[0],
            [
                "gh", "issue", "create",
                "--repo", "penpot/penpot",
                "--title", "A title",
                "--project", "Main",
                "--body-file", "/tmp/body.md",
            ],
        )
        self.assertEqual(run_graphql.call_count, 2)
        self.assertEqual(
            run_graphql.call_args_list[1].args[1],
            {
                "issueId": "issue-node-id",
                "issueTypeId": "IT_kwDOAcyBPM4AX5NY",
            },
        )

    @patch.object(gh, "run_gh_graphql")
    @patch.object(gh, "run_gh_command")
    def test_create_issue_passes_labels(self, run_command, run_graphql):
        run_command.return_value = self.create_response
        run_graphql.side_effect = [
            self.node_response,
            {"updateIssue": {"issue": {"number": 13202, "issueType": {"name": "Bug"}}}},
        ]

        gh.create_issue("T", "/tmp/body.md", "bug", labels="breaking change")

        self.assertIn("breaking change", run_command.call_args.args[0])
        self.assertIn(
            "IT_kwDOAcyBPM4AX5Nb",
            run_graphql.call_args_list[1].args[1].values(),
        )

    @patch.object(gh, "run_gh_graphql")
    @patch.object(gh, "run_gh_command")
    def test_create_issue_accepts_mixed_case_type(self, run_command, run_graphql):
        run_command.return_value = self.create_response
        run_graphql.side_effect = [self.node_response, self.mutation_response]

        result = gh.create_issue("T", "/tmp/body.md", "Task")

        self.assertEqual(result["issue_type"], "task")
        self.assertEqual(
            run_graphql.call_args_list[1].args[1]["issueTypeId"],
            "IT_kwDOAcyBPM4AX5NY",
        )

    @patch.object(gh, "run_gh_command")
    def test_create_issue_rejects_unknown_type(self, run_command):
        with self.assertRaisesRegex(ValueError, "unknown issue type"):
            gh.create_issue("T", "/tmp/body.md", "nope")
        run_command.assert_not_called()

    @patch.object(gh, "run_gh_command", return_value="created")
    def test_create_issue_fails_when_url_has_no_number(self, _run_command):
        with self.assertRaisesRegex(RuntimeError, "could not read issue number"):
            gh.create_issue("T", "/tmp/body.md", "task")

    @patch.object(gh, "run_gh_graphql")
    @patch.object(gh, "run_gh_command")
    def test_create_issue_reports_when_type_was_not_set(self, run_command, run_graphql):
        run_command.return_value = self.create_response
        run_graphql.side_effect = [
            self.node_response,
            {"updateIssue": {"issue": {"number": 13202, "issueType": None}}},
        ]

        with self.assertRaisesRegex(RuntimeError, "set it manually"):
            gh.create_issue("T", "/tmp/body.md", "task")

    @patch.object(gh, "run_gh_command", side_effect=gh.GhCommandFailed("boom"))
    def test_create_issue_propagates_gh_failure(self, _run_command):
        with self.assertRaises(gh.GhCommandFailed):
            gh.create_issue("T", "/tmp/body.md", "task")

    @patch.object(gh, "create_issue")
    def test_cmd_create_issue_outputs_result(self, create_issue):
        expected = {
            "number": 13202,
            "url": self.url,
            "title": "T",
            "issue_type": "task",
        }
        create_issue.return_value = expected
        args = types.SimpleNamespace(
            type="task", title="T", body_file="/tmp/body.md", label=None
        )
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            gh.cmd_create_issue(args)

        self.assertEqual(json.loads(stdout.getvalue()), expected)
        self.assertIn("Created issue #13202", stderr.getvalue())
        create_issue.assert_called_once_with("T", "/tmp/body.md", "task", labels=None)

    @patch.object(gh, "create_issue", side_effect=gh.GhCommandFailed("boom"))
    def test_cmd_create_issue_exits_on_error(self, _create_issue):
        args = types.SimpleNamespace(
            type="task", title="T", body_file="/tmp/body.md", label=None
        )
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_create_issue(args)

        self.assertEqual(error.exception.code, 1)
        self.assertIn("boom", stderr.getvalue())


class CreatePrBaseDetectionTests(unittest.TestCase):
    @staticmethod
    def fake_git(mapping):
        def run(*args):
            return mapping.get(args, "")
        return run

    def test_normalize_branch_ref(self):
        cases = [
            ("remotes/origin/staging~2", "staging"),
            ("develop^2~1", "develop"),
            ("tags/v2.0", "v2.0"),
            ("refs/heads/topic~1", "topic"),
            ("heads/topic", "topic"),
        ]
        for raw, expected in cases:
            with self.subTest(raw=raw):
                self.assertEqual(gh.normalize_branch_ref(raw), expected)

    @patch.object(gh, "run_git")
    def test_detects_normalized_base_and_validates_it(self, run_git):
        calls = []

        def fake(*args):
            calls.append(args)
            if args == ("rev-parse", "--abbrev-ref", "HEAD"):
                return "topic"
            if args == ("name-rev", "--name-only", "HEAD"):
                return "remotes/origin/staging~2"
            return ""
        run_git.side_effect = fake

        self.assertEqual(gh.detect_target_branch(), "staging")
        self.assertIn(
            ("show-ref", "--verify", "--quiet", "refs/heads/staging"), calls
        )

    @patch.object(gh, "run_git")
    def test_rejects_detected_base_without_local_branch(self, run_git):
        def fake(*args):
            if args == ("rev-parse", "--abbrev-ref", "HEAD"):
                return "topic"
            if args == ("name-rev", "--name-only", "HEAD"):
                return "tags/v2.0"
            if args[:1] == ("show-ref",):
                raise gh.GhCommandFailed("not found")
            return ""
        run_git.side_effect = fake

        with self.assertRaisesRegex(RuntimeError, "pass --base"):
            gh.detect_target_branch()

    @patch.object(gh, "run_git")
    def test_detects_base_from_name_rev(self, run_git):
        run_git.side_effect = self.fake_git({
            ("rev-parse", "--abbrev-ref", "HEAD"): "issue-13202",
            ("name-rev", "--name-only", "HEAD"): "staging~3",
        })

        self.assertEqual(gh.detect_target_branch(), "staging")

    @patch.object(gh, "run_git")
    def test_falls_back_to_graph_walk(self, run_git):
        def fake(*args):
            if args == ("rev-parse", "--abbrev-ref", "HEAD"):
                return "issue-13202"
            if args == ("name-rev", "--name-only", "HEAD"):
                return "issue-13202"
            if args == ("rev-list", "HEAD"):
                return "a\nb\nc\n"
            if args[:2] == ("branch", "--points-at"):
                return "staging\n" if args[2] == "b" else ""
            return ""
        run_git.side_effect = fake

        self.assertEqual(gh.detect_target_branch(), "staging")

    @patch.object(gh, "run_git")
    def test_tolerates_name_rev_failure(self, run_git):
        def fake(*args):
            if args == ("name-rev", "--name-only", "HEAD"):
                raise gh.GhCommandFailed("boom")
            if args == ("rev-parse", "--abbrev-ref", "HEAD"):
                return "topic"
            if args == ("rev-list", "HEAD"):
                return "a\n"
            if args[:2] == ("branch", "--points-at"):
                return "staging\n"
            return ""
        run_git.side_effect = fake

        self.assertEqual(gh.detect_target_branch(), "staging")

    @patch.object(gh, "run_git")
    def test_raises_when_no_other_branch(self, run_git):
        run_git.side_effect = self.fake_git({
            ("rev-parse", "--abbrev-ref", "HEAD"): "topic",
            ("name-rev", "--name-only", "HEAD"): "",
            ("rev-list", "HEAD"): "a\n",
        })

        with self.assertRaisesRegex(RuntimeError, "unable to determine target branch"):
            gh.detect_target_branch()


class CreatePrTests(unittest.TestCase):
    def setUp(self):
        self.url = "https://github.com/penpot/penpot/pull/13204"
        self.create_response = self.url + "\n"

    def args(self, **overrides):
        values = dict(
            title="A title",
            body_file="/tmp/pr-body.md",
            base=None,
            head=None,
            issue=None,
            project="Main",
            draft=False,
        )
        values.update(overrides)
        return types.SimpleNamespace(**values)

    @patch.object(gh, "link_issue_to_pr")
    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "run_git")
    @patch.object(gh, "detect_target_branch")
    def test_create_pr_detects_base_and_links_issue(
        self, detect, run_git, run_command, link_issue
    ):
        detect.return_value = "staging"
        run_git.return_value = "issue-13202"
        run_command.return_value = self.create_response

        with contextlib.redirect_stderr(io.StringIO()):
            result = gh.create_pr(
                "A title", "/tmp/pr-body.md", issue_number=13202
            )

        self.assertEqual(result, {
            "number": 13204,
            "url": self.url,
            "title": "A title",
            "base": "staging",
            "head": "issue-13202",
            "linked_issue": 13202,
        })
        self.assertEqual(run_command.call_args.args[0], [
            "gh", "pr", "create",
            "--repo", "penpot/penpot",
            "--base", "staging",
            "--head", "issue-13202",
            "--title", "A title",
            "--project", "Main",
            "--body-file", "/tmp/pr-body.md",
        ])
        link_issue.assert_called_once_with(13202, 13204)
        detect.assert_called_once_with()

    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "run_git")
    @patch.object(gh, "detect_target_branch")
    def test_create_pr_uses_explicit_base(self, detect, run_git, run_command):
        run_git.return_value = "topic"
        run_command.return_value = self.create_response

        with contextlib.redirect_stderr(io.StringIO()):
            result = gh.create_pr("T", "/tmp/pr-body.md", base="release/1.0")

        self.assertEqual(result["base"], "release/1.0")
        detect.assert_not_called()
        cmd = run_command.call_args.args[0]
        self.assertEqual(cmd[cmd.index("--base") + 1], "release/1.0")

    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_create_pr_passes_head_and_draft(self, _detect, run_command):
        run_command.return_value = self.create_response

        with contextlib.redirect_stderr(io.StringIO()):
            result = gh.create_pr(
                "T", "/tmp/pr-body.md", head="custom", draft=True
            )

        cmd = run_command.call_args.args[0]
        self.assertEqual(cmd[cmd.index("--head") + 1], "custom")
        self.assertIn("--draft", cmd)
        self.assertIsNone(result["linked_issue"])

    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "detect_target_branch")
    def test_create_pr_rejects_empty_title(self, _detect, run_command):
        with self.assertRaisesRegex(ValueError, "title must not be empty"):
            gh.create_pr("   ", "/tmp/pr-body.md")
        run_command.assert_not_called()

    @patch.object(gh, "run_gh_command", return_value="created")
    @patch.object(gh, "run_git", return_value="topic")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_create_pr_fails_when_url_has_no_number(
        self, _detect, _run_git, _run_command
    ):
        with self.assertRaisesRegex(RuntimeError, "could not read PR number"):
            gh.create_pr("T", "/tmp/pr-body.md")

    @patch.object(gh, "run_gh_command", side_effect=gh.GhCommandFailed("boom"))
    @patch.object(gh, "run_git", return_value="topic")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_create_pr_propagates_gh_failure(
        self, _detect, _run_git, _run_command
    ):
        with self.assertRaises(gh.GhCommandFailed):
            gh.create_pr("T", "/tmp/pr-body.md")

    @patch.object(gh, "link_issue_to_pr")
    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "run_git", return_value="topic")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_create_pr_prints_url_once_on_success(
        self, _detect, _run_git, run_command, _link_issue
    ):
        run_command.return_value = self.create_response
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            gh.create_pr("T", "/tmp/pr-body.md", issue_number=13202)

        self.assertEqual(stderr.getvalue().count("Created PR #13204"), 1)

    @patch.object(gh, "link_issue_to_pr", side_effect=RuntimeError("link missing"))
    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "run_git", return_value="topic")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_cmd_create_pr_keeps_url_when_link_raises_runtime_error(
        self, _detect, _run_git, run_command, _link_issue
    ):
        run_command.return_value = self.create_response
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_create_pr(self.args(issue=13202))

        self.assertEqual(error.exception.code, 1)
        self.assertIn("Created PR #13204", stderr.getvalue())
        self.assertIn(self.url, stderr.getvalue())

    @patch.object(gh, "link_issue_to_pr", side_effect=SystemExit(1))
    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "run_git", return_value="topic")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_cmd_create_pr_keeps_url_when_link_exits(
        self, _detect, _run_git, run_command, _link_issue
    ):
        run_command.return_value = self.create_response
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_create_pr(self.args(issue=13202))

        self.assertEqual(error.exception.code, 1)
        self.assertIn("Created PR #13204", stderr.getvalue())
        self.assertIn("issue linking failed", stderr.getvalue())
        self.assertIn(self.url, stderr.getvalue())
        self.assertNotIn("ERROR: 1", stderr.getvalue())

    @patch.object(gh, "create_pr")
    def test_cmd_create_pr_outputs_result(self, create_pr):
        expected = {
            "number": 13204,
            "url": self.url,
            "title": "T",
            "base": "staging",
            "head": "topic",
            "linked_issue": None,
        }
        create_pr.return_value = expected
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            gh.cmd_create_pr(self.args())

        self.assertEqual(json.loads(stdout.getvalue()), expected)
        self.assertIn("Creating pull request...", stderr.getvalue())
        create_pr.assert_called_once_with(
            "A title", "/tmp/pr-body.md",
            base=None, head=None, issue_number=None,
            project="Main", draft=False,
        )

    @patch.object(gh, "create_pr")
    def test_cmd_create_pr_forwards_project(self, create_pr):
        create_pr.return_value = {"number": 13204, "url": self.url}

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(
            io.StringIO()
        ):
            gh.cmd_create_pr(self.args(project="Other"))

        self.assertEqual(create_pr.call_args.kwargs["project"], "Other")

    @patch.object(gh, "run_gh_command")
    @patch.object(gh, "run_git", return_value="topic")
    @patch.object(gh, "detect_target_branch", return_value="staging")
    def test_create_pr_uses_explicit_project(self, _detect, _run_git, run_command):
        run_command.return_value = self.create_response

        with contextlib.redirect_stderr(io.StringIO()):
            gh.create_pr("T", "/tmp/pr-body.md", project="Other")

        cmd = run_command.call_args.args[0]
        self.assertEqual(cmd[cmd.index("--project") + 1], "Other")

    @patch.object(gh, "create_pr", side_effect=gh.GhCommandFailed("boom"))
    def test_cmd_create_pr_exits_on_error(self, _create_pr):
        stderr = io.StringIO()

        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                gh.cmd_create_pr(self.args())

        self.assertEqual(error.exception.code, 1)
        self.assertIn("boom", stderr.getvalue())


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

    @patch.object(gh, "run_gh_command")
    def test_fetch_advisories_uses_cursor_pagination(self, run_gh_command):
        first_page = [{"ghsa_id": "GHSA-first"}]
        second_page = [{"ghsa_id": "GHSA-second"}]
        run_gh_command.return_value = json.dumps([first_page, second_page])

        self.assertEqual(gh.fetch_advisories(), first_page + second_page)

        command = run_gh_command.call_args.args[0]
        self.assertIn("--paginate", command)
        self.assertIn("--slurp", command)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
