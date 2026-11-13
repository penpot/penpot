# Merging Renovate Pull Requests

## The problem

- Squash-merging a Renovate PR from the GitHub UI adds
  `Co-authored-by: penpot-renovate[bot] <...>` to the landed commit.
  Renovate does not write that line: the branch commit only has
  `Signed-off-by`. GitHub appends the co-author trailer while generating the
  squash message, because the PR author (the GitHub App
  `app/penpot-renovate`) differs from the commit author (the bot user).
- The same generated message keeps Renovate's unwrapped dependency lines.
  Deep paths (`.../Dockerfile.media-processor`) exceed the 80-character body
  rule.
- Both violations fail `.github/workflows/commit-checker.yml` on the push to
  `develop`, so "Check Commit Guidelines" goes red after the merge.
- No Renovate setting prevents this. `renovate.json` and the
  `penpot/renovate-config` preset can write `Signed-off-by`, but nothing can
  remove a trailer GitHub adds at merge time.

## The fix: merge with an explicit message

```bash
python3 scripts/gh.py merge-renovate-pr <PR> --dry-run   # preview only
python3 scripts/gh.py merge-renovate-pr <PR>             # merge
```

- Subject = PR title. Body = branch commit body with any `Co-authored-by`
  line dropped and lines over 80 characters rewrapped (list items keep a
  hanging indent).
- The message is validated with `scripts/check-commit` before the merge; a
  failure aborts without touching GitHub.
- Only PRs opened by `app/penpot-renovate` with exactly one commit are
  accepted, and an `APPROVED` review is required
  (`--no-approval-check` bypasses it).
- After merging, the helper reads the landed commit back and exits 2 if a
  co-author trailer survived, so a silent regression is visible.
- GitHub uses an explicitly supplied message verbatim. That is what removes
  the trailer: merging from the UI, or `gh pr merge` without
  `--subject`/`--body`, regenerates it.

## Manual fallback

- In the GitHub merge box, delete the `Co-authored-by:` line before
  confirming the squash. The message is editable.
- If the trailer ever survives an explicit message, use
  `gh pr merge <PR> --rebase` (the branch commit lands untouched) and fix
  the long dependency lines in `penpot/renovate-config` (`commitBody`).

## Verification

- `python3 scripts/test_gh.py` covers the message builder, the PR guards and
  the post-merge check.
- `python3 scripts/gh.py merge-renovate-pr <PR> --dry-run` works on merged
  PRs, so it can reproduce an old failure read-only.
