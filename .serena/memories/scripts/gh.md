# GitHub helper

Use authenticated `gh` with `python3 scripts/gh.py <subcommand> --help` for current options. Structured results go to stdout; progress and errors go to stderr.

- `issues` lists issues by milestone (exact title) or with no milestone; milestone queries default to closed issues. `issue` and `comments` fetch issue details and comments. `prs` fetches specific PRs or lists a milestone (merged only by default); request all states when auditing unmerged PRs.
- `create-issue` creates the issue and then sets its Issue Type; this is not atomic. For authorization, metadata, and recovery after a partial creation, read `mem:workflow/creating-issues`. `link-issue` sets the Development association, not the PR body; follow `mem:workflow/creating-prs`. The helper trusts the successful mutation because GitHub's link readback can omit mutation-created links.
- `advisories` lists or inspects repo advisories. Patched-version matching compares exact tokens, not prefixes, and does not require publication. The endpoint uses cursor pagination: use `gh api --paginate --slurp`, not numeric pages, which can repeat the same data.
- `issues --compare` can mistake a same-numbered Taiga reference or prose token for a GitHub issue. Use the changelog-specific reconciliation in `mem:scripts/changelog` when reference targets matter.
