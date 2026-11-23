---
name: update-changelog
description: Update the project CHANGES.md with issues from a given GitHub milestone, with correct categorization and references.
---

# Skill: update-changelog

Use this skill to prepare, refresh, or audit a changelog version.

Read `mem:workflow/updating-changelog` for the rules and follow its **Flow**:
collect, review, write, verify. Start with `collect <VERSION>` and keep its
JSON snapshot in a private temporary file. Reuse it for the review and checks;
collect again if the GitHub data changes.

Keep review read-only. Follow the separate `--apply` branch only when the
workflow calls for it.
