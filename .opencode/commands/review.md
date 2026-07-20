---
description: Review a commit (defaults to the last commit) with the code-review-and-quality skill across all five axes
agent: plan
subtask: true
---

You are performing a code review of a git commit. You MUST conduct it using the **`code-review-and-quality`** skill (the five-axis review: correctness, readability, architecture, security, performance).

The user may specify a commit or revision range as an argument ($ARGUMENTS). If no argument is given, default to reviewing the **last commit** (`HEAD`, i.e. the changes introduced by `HEAD` vs its parent).

Workflow:

1. Determine the target to review:
   - If the user provided a revision/range in $ARGUMENTS, use it.
   - Otherwise, default to the last commit: review `HEAD` (the diff of `HEAD` against `HEAD~1`).
2. Inspect the change with `git show <target>` / `git diff <target>~1 <target>` and `git log -1 --stat <target>` to understand the intent and the files touched.
3. Invoke the **`code-review-and-quality`** skill and review the commit across all five axes. Categorize every finding as Critical / Required / Optional / Nit / FYI, and lead with correctness and security.
4. For each finding, state the axis it belongs to, the severity, and a concrete suggested fix (propose the structural remedy, not just the problem).
5. Conclude with a clear verdict: **Approve** (ready to merge) or **Request changes** (issues that must be addressed), and summarize the highest-leverage items.

Do not modify any code and do not create a commit — this command only reviews.
