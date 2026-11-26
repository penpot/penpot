# Creating Issues

Create or edit issues only when authorized. Follow `AGENTS.md` and use authenticated `gh` for `penpot/penpot`.

## Title Derivation

- Derive the title from the source material, not from a copied or auto-generated title. Use plain text, no emoji or prefixes. Name the affected UI or module, and cover both issues if two related symptoms matter.
- Bug: present-tense symptom, not an imperative. Example: `Canvas renders glitches when zooming quickly`, not `Fix canvas glitches`.
- Feature or enhancement: imperative and user-facing. Example: `Add dash and gap controls to stroke options`.

## Issue Body Template

- Bug: `### Description`, `### Steps to reproduce`, `### Expected behavior`, `### Affected versions`. Describe actual behavior in the description; use numbered steps.
- Feature or enhancement: `### Description`, `### Use case`, `### Affected versions`. Explain what the user can do and why it helps. Don't invent details or versions.
- Keep each paragraph or list item on one line, as for PR bodies. Put the body in a private temporary file to avoid shell quoting and remove your temporary files afterward.

## Metadata

- Project: `Main` (project 8). Use the current or next planned milestone only when clear; issues from PRs inherit the PR milestone, or none. Assign a known milestone on the existing issue with `gh issue edit --milestone`; the creation helper has no milestone option.
- Labels: `community contribution` for non-core contributions; `breaking change` when compatibility breaks. Don't copy workflow labels (e.g. `team-qa`, `backport candidate`) or use `bug`/`enhancement` labels to encode the Issue Type.
- Issue Type: `Bug` for broken behavior, `Enhancement` for improvements and ordinary feature requests, `Feature` for feature-level work, and `EPIC` for an umbrella tracking issue. Use `Question` or `Docs` when appropriate and `Task` for other work. Parentage does not decide type: fixing a bug under an epic is still a Bug.

## Breaking changes

- Add the `breaking change` label and post a separate comment starting with `## Breaking change note`, not a section in the issue body. Reuse the user-facing note from the PR when available.
- Explain to self-hosters what changes, what they must do, and what breaks if they do nothing. Link the details on `staging` or `main`, not a feature branch; don't paste the runbook or add issue/PR links the generator supplies. Use one line per paragraph or bullet. See `mem:workflow/updating-changelog` for how the note becomes a generated entry.

## Create, recover and verify

1. After authorization, run `python3 scripts/gh.py create-issue --type <type> --title "<title>" --body-file <private-file>` with any intended labels. The helper adds the issue to `Main` and assigns its type. See `mem:scripts/gh` for CLI discovery.
2. Record the returned number and URL. If the command fails or the result is ambiguous, check whether the issue already exists before retrying. Use the number or URL from the error if present; otherwise search recent issues by the derived title and body. If creation succeeded but type assignment did not, use `ISSUE_TYPE_IDS`, `GQL_ISSUE_NODE_ID_QUERY` and `GQL_UPDATE_ISSUE_TYPE` in `scripts/gh.py` to assign the type to that existing issue. Never create a second one to repair type or linkage.
3. Apply any separate, authorized edits using the existing number. Verify the title, labels, milestone and `Main` membership with `gh issue view <NUMBER> --repo penpot/penpot --json title,labels,milestone,projectItems`; verify Issue Type on the issue or via GraphQL `repository.issue.issueType.name`. Return the verified URL and disclose any incomplete follow-up.

## Adding an Issue as a Sub-issue

- Create and verify it first. Read its REST database `id` (not the issue number or GraphQL node ID) with `gh api repos/penpot/penpot/issues/<NUMBER> --jq .id`.
- Add it to the umbrella issue with `gh api --method POST repos/penpot/penpot/issues/<PARENT>/sub_issues -F sub_issue_id=<DATABASE_ID>`. Use typed `-F`, not `-f`, which sends a string. An issue has one parent; verify both the child's `/parent` and the parent's `/sub_issues`.

## Creating Issues from PRs

1. Read the PR body, author, labels and milestone. Create one issue for what the user gains or what was broken; leave implementation details in the PR. Apply the shared creation flow, copy the PR milestone when set and post a breaking-change note when needed.
2. Try assigning the PR author when appropriate. `gh issue edit` can still return success when GitHub refuses an outside assignee; verify via `gh issue view` and skip an ineligible assignment.
3. For authorized PR edits, put `Closes #<NUMBER>` in the PR body for context and make the explicit Development association using `python3 scripts/gh.py link-issue <NUMBER> <PR>`. Do not treat the body text as proof of the association. Editing an already-merged PR can close the issue; see `mem:workflow/creating-prs` before editing. Drop `no issue required` from the PR once the issue exists.

## Creating Issues from Draft Body

- Read the draft, not its old title. If a vague report gives no observable behavior or use case, ask for enough detail before creating. Pick the body template and metadata above, then use the shared creation flow.

## Retitling an Existing Issue

- Fetch the existing body, derive the title from its content, and edit only `--title`. Read the title back. Do not change body, metadata or relationships as part of a retitle.
