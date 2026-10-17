---
name: rewrite-commit-message
description: Rewrite HEAD's commit message (typically a squash that concatenated old messages) into one proper title plus description following Penpot commit conventions, preserving every distinct AI-assisted-by attribution. Amends in place, never pushes.
slash: true
---

# Skill: rewrite-commit-message

HEAD's message is a concatenation of older messages (typical after a
squash): several subjects, bodies and repeated trailers stacked in a
single message. Replace it with one proper message and amend HEAD.

## When to Use

- The user asks to rewrite, retitle or fix the last commit message
- After a squash left HEAD with stacked messages and trailer repeats

## Steps

1. Read the full current message (`git log -1 --format=%B HEAD`) and
   the branch diff (`git diff develop..HEAD --stat`, plus the code
   when the summary needs grounding).
2. Collect every DISTINCT `AI-assisted-by:` value in the current
   message. All of them survive into the new message, one line each:
   attributions are never dropped in a rewrite, even when most of
   the old text is.
3. Draft one message per `mem:workflow/creating-commits` (read it
   end-to-end first): single subject (imperative, capitalized, no
   trailing period, ≤80 chars), blank line, body wrapped at ≤80
   chars covering the whole diff (not the old per-commit slices),
   then the preserved trailers.
4. Write it to a file (a heredoc keeps real newlines; inline `-m`
   with `\n` does not) and `git commit --amend -F <file>`.
5. Run `./scripts/check-commit` and require exit code 0. A non-zero
   exit is a hard blocker: fix the message, amend again.
6. Report the new hash. Never push: the user pushes from their own
   shell. Warn when local history was rewritten over pushed commits
   (the push will need force — their call, not yours).
