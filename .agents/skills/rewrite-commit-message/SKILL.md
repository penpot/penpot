---
name: rewrite-commit-message
description: Rewrite HEAD's commit message (typically a squash that concatenated old messages) into one proper title plus description following Penpot commit conventions, preserving every distinct AI-assisted-by attribution. Amends in place, never pushes.
slash: true
---

# Skill: rewrite-commit-message

Rewrite HEAD's title and description from its own diff. This also handles a
squash message with several subjects, bodies and repeated trailers.

## When to Use

- The user asks to rewrite, retitle or fix the last commit message
- After a squash left HEAD with stacked messages and trailer repeats

## Steps

1. Check `git diff --cached` first. If any changes are staged, stop and ask
   the user to handle them; an amend must never include those changes. Read
   the full current message (`git log -1 --format=%B HEAD`) and HEAD's own
   diff (`git show --format= --stat HEAD` and the patch). Respect the project
   rule on amending pushed commits.
2. Collect every DISTINCT `AI-assisted-by:` value in the current
   message. All of them survive into the new message, one line each:
   attributions are never dropped in a rewrite, even when most of
   the old text is.
3. Draft one message per `mem:workflow/creating-commits` (read it
   end-to-end first): single subject (imperative, capitalized, no
   trailing period, ≤80 chars), blank line, body wrapped at ≤80
   chars covering HEAD's diff (not the old per-commit slices),
   then the preserved trailers.
4. Write it to a file (a heredoc keeps real newlines; inline `-m`
   with `\n` does not) and `git commit --amend -F <file>`.
5. Run `./scripts/check-commit` and require exit code 0. A non-zero
   exit is a hard blocker: fix the message, amend again.
6. Report the new hash. Never push: the user pushes from their own
   shell. Warn when local history was rewritten over pushed commits
   (the push will need force — their call, not yours).
