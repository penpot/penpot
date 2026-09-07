---
name: create-commit
description: Stage, review, and commit files following Penpot commit conventions.
---

# Skill: create-commit

Produce a git commit that follows Penpot's commit message conventions. This
skill owns the commit format, staging review, and safety checks — it does not
implement features or push.

## When to Use

- After code changes are complete and files need to be committed
- When delegated by a workflow step (e.g. implement-plan) to handle the commit

## Required Reading

Before drafting any commit, read `mem:workflow/creating-commits` end-to-end. It
is the authoritative source for the commit message format, the emoji menu,
subject/body limits, and the `AI-assisted-by` trailer. Follow it exactly.

## Workflow

1. **Stage the files** specified by the calling context. Do not ask for
   confirmation.
2. Run `git diff --staged` to review the content. If you see secrets (API keys,
   tokens, passwords, private keys, `.env` values), debug prints, or anything
   that does not match the stated intent, **STOP** and tell the user before
   committing.
3. Draft the message following the format in the memory doc, wrapping the body
   at 72 characters per line, and run:
   ```bash
   git commit -m "<subject>" -m "<body>"
   ```
   (or `git commit -F -` if the body has unusual characters).
4. The `AI-assisted-by` trailer value is provided by the calling context — use
   it verbatim.

## Constraints

- Do not push. Pushing is a separate workflow handled by the user.
- Do not run `git reset`, `git checkout`, `git restore`, `git clean`, or `rm`.
- Do not pass `--author`. Author identity comes from the local git config.
- Do not amend a commit you did not create in this session, unless explicitly asked.
- Do not bypass pre-commit hooks (`--no-verify`) unless explicitly asked.
- Do not add untracked files that were not created in this session.
