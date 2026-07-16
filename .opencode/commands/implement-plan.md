---
description: Execute a ready plan end-to-end — create a GitHub issue, branch issue-NNNN, implement the plan, then commit via the commiter subagent
agent: build
---

# Implement Plan

This command is run once a plan is ready (for example, from plan mode). Execute
the plan already prepared in the current session context — it does not take
extra arguments. Follow these steps in order.

## 1. Create the issue

Use the **`create-issue`** skill, following the *Creating Issues from Draft Body*
flow in `mem:workflow/creating-issues`. Derive the issue title and body from the
plan. Capture the new issue's number — call it **NNNN** (needed for the branch
name and the commit reference).

## 2. Create the branch

Create and switch to a branch named after the issue:

```
git checkout -b issue-NNNN
```

(Replace NNNN with the issue number from step 1.)

## 3. Execute the plan

Implement the prepared plan from the session context. Work methodically, keeping
changes focused on what the issue requires. Do not commit — the commit happens in
step 4.

## 4. Commit with the commiter subagent

After the implementation is complete, delegate the commit to the **`commiter`**
subagent. Give it a brief summary of what was implemented and why, the issue
reference (`issue-NNNN`), and the model name you are running as so it sets the
`AI-assisted-by` trailer correctly. The subagent owns the commit format and
conventions.

Do not push. Pushing is handled separately by the user.
