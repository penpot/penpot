# Updating the changelog

> Note about this memory: unlike other Serena memories (dense notes written for LLM agents, see `mem:memory-maintenance`), this document is also written for human release managers who need full context. That is why it uses plain language and examples. It is the canonical home of the changelog flow; the `update-changelog` skill is only an entry point that routes here.

`CHANGES.md` (repository root) is the release notes file. Every entry comes from GitHub: a milestone issue, its merged PR, or a security advisory whose `patched_versions` include the version. It is never written from scratch by hand; the only manual parts are the wording of a description when the title is not enough and the advisory summaries (polished on GitHub, the source of the generated security entries).

The commands live in `scripts/gh.py` (GitHub queries) and `scripts/changelog.py` (changelog checks). Their flags and internals are documented in `mem:scripts/gh` and `mem:scripts/changelog`.

## 1. When to run it

- Before a release, to fill the section of the version being released.
- When new issues are added to an existing milestone.
- When asked to audit or refresh `CHANGES.md`.

Editing `CHANGES.md` happens only on explicit request or as part of the release process; see `mem:critical-info`.

## 2. Sources of truth

| Question | Source |
|---|---|
| Which issues belong to the version? | GitHub milestone |
| Which security fixes? | Advisories whose `patched_versions` include the version |
| Which section? | Issue Type |
| Is it a breaking change? | `breaking change` label |
| Is it a highlight? | `release highlight` label |

- **Issue Type decides the section**: `Bug` → `:bug:`, `Feature`/`Enhancement` → `:sparkles:`, `Task` is excluded (internal chores). Labels `bug`/`enhancement` and title emoji prefixes are often wrong or missing; do not use them.
- **Security fixes come from advisories**: an advisory whose `patched_versions` include the version becomes a `:lock:` entry (section 7). `triage`, `draft` and `published` are all valid states; the advisory may still be unpublished when the changelog is written. A security fix may also carry a regular bug issue; both entries are legal and independent (the issue lives in `:bug:` as any other bug).
- **Two labels override the Issue Type**: `breaking change` → an entry only under `:boom:`, never in its regular section; `release highlight` → an extra entry under `:rocket:` on top of its regular entry.
- **Only closed issues**. An open issue in the milestone is not generated, even if someone added a provisional entry by hand before the merge.
- **Excluded issues**: `no changelog`, `release blocker`, `Task` issue type, `Rejected` project status.
- **Excluded PRs**: a PR with `release blocker` or `no issue required` does not bring an entry of its own.
- **An issue with no milestone** belongs to another (probably private) project: it is neither changelog candidate nor anomaly.

## 3. Layout of `CHANGES.md`

The newest version goes at the top, right after the `# CHANGELOG` header. Inside a version, the section order is fixed:

```markdown
# CHANGELOG

## 2.19.0 (Unreleased)

### :boom: Breaking changes & Deprecations

#### <Breaking change title>

<content>

[#ISSUE](https://github.com/penpot/penpot/issues/ISSUE) (PR: [#PR](https://github.com/penpot/penpot/pull/PR))

### :rocket: Epics and highlights

- Add configurable keyboard shortcuts [#9924](https://github.com/penpot/penpot/issues/9924) (PR: [#10237](https://github.com/penpot/penpot/pull/10237))

### :lock: Security fixes

- Fix <user-facing description of the vulnerability> (<advisory URL>) (PR: [#PR](https://github.com/penpot/penpot/pull/PR))

### :bug: Bugs fixed

- Fix <user-facing description of the bug> [#ISSUE](https://github.com/penpot/penpot/issues/ISSUE) (PR: [#PR](https://github.com/penpot/penpot/pull/PR))

### :sparkles: New features & Enhancements

- Add <user-facing description of the feature> [#ISSUE](https://github.com/penpot/penpot/issues/ISSUE) (PR: [#PR](https://github.com/penpot/penpot/pull/PR))
```

- `:boom:` first, then `:rocket:`, `:lock:`, `:bug:`, `:sparkles:`. Other headings exist in old versions (`:heart:` community contributions); do not add them to new versions.
- Include a section only when it has entries. Historical versions may have an empty `:boom:` or `:rocket:` heading; `boom --write` keeps the `:boom:` heading even when there are no entries. `:lock:` is omitted (and removed on `--write`) when the version has no security advisories.
- One blank line between the last entry of a section and the next heading.
- No manual line wrapping: one line per bullet or paragraph, however long.

## 4. Regular entries (`:bug:`, `:sparkles:`)

The entry shape is:

```markdown
- <description> (by @github_username) [#ISSUE](https://github.com/penpot/penpot/issues/ISSUE) (PR: [#PR](https://github.com/penpot/penpot/pull/PR), [#PR2](https://github.com/penpot/penpot/pull/PR2))
```

- **Description**: derived from the issue title, not the PR title. Strip emoji prefixes (`:bug:`, `:sparkles:`, `:tada:`) and write the user-facing behaviour in imperative mood. Examples: `Plugin API token methods fail with schema validation error` → `Fix Plugin API token methods failing with schema validation error`; `Custom uploaded font family names are not sanitized` → `Sanitize font family names on custom uploaded fonts`.
- **Issue is the primary link**: the issue is the changelog unit, the PR is the implementation. Multiple fix PRs go comma-separated inline.
- **Community attribution**: when the issue or its PR carries `community contribution`, add `(by @username)` before the issue link. Use the PR author, not the issue author.
- **Closed issue with no PR** in the milestone: link the issue only, no PR reference.
- **PR with no linked issue**: link a matching closed issue in the milestone when one exists; otherwise skip it, because the issue is the changelog unit.
- **False-positive PR links**: a PR may claim to close an unrelated issue (ancient PR, cross-project reference). If the titles are clearly unrelated or the PR predates the issue by years, treat it as a data glitch and ignore it.

## 5. `:boom:`: breaking changes and deprecations

Each breaking change is a `####` block, not a bullet. The title is the issue title without its emoji prefix; the content comes from a comment on the issue; the references go on their own line at the end.

The **issue** must carry:

1. The `breaking change` label.
2. A comment whose first line is `## Breaking change note`. Everything after that marker line is the changelog content: paragraphs, bullet lists, code blocks, tables, headings (they are demoted to `#####` on write), and so on. The marker itself is case-insensitive and accepts `#`, `##` or `###`; use `##`.

Example comment:

```markdown
## Breaking change note

The Admin Console is now enabled by default and the `enable-admin-console` flag is no longer needed.

Self-hosted instances must deploy the `penpot-admin-console` service and set `PENPOT_ADMIN_CONSOLE_URI` in frontend and backend (check the [upgrade guide](https://help.penpot.app/technical-guide/getting-started/docker/#upgrade-to-2.19)).
```

Generated block:

```markdown
#### Enable the Admin Console by default

The Admin Console is now enabled by default and the `enable-admin-console` flag is no longer needed.

Self-hosted instances must deploy the `penpot-admin-console` service and set `PENPOT_ADMIN_CONSOLE_URI` in frontend and backend (check the [upgrade guide](https://help.penpot.app/technical-guide/getting-started/docker/#upgrade-to-2.19)).

[#12807](https://github.com/penpot/penpot/issues/12807) (PR: [#12475](https://github.com/penpot/penpot/pull/12475))
```

Rules for the note:

- **Write it for admins**: what changes, what they must do, and what breaks if they do nothing. Do not paste the full runbook; explain the implications and link the runbook or docs.
- **Link a long-lived branch**, `staging` or `main`, never a feature branch (`issue-1234`). The link may 404 until the PR merges; that is expected.
- **Do not add issue or PR links**: the generator appends them.
- **Do not hard-wrap the text**: one line per paragraph or bullet. The generator copies lines verbatim, so wrapped lines end up wrapped in `CHANGES.md`.
- **One note per issue**. If several comments carry the marker, the generator uses the last one and warns.
- **No note**: the generator warns on stderr and emits only the heading and references; fix the issue, not `CHANGES.md`.
- **`####`, not `###`**: `###` is the level of the changelog sections. An entry at `###` would render as a sibling section and the scripts that read and rewrite `:boom:` would cut the section there. The note author never writes the `####`: the generator adds it.
- **Only merged closing PRs** are referenced; unmerged or superseded PRs are skipped.
- **Never hand-edit the section**. Generate it with `boom` (section 8). The note lives on GitHub; edit it there.

## 6. `:rocket:`: epics and highlights

- Lists exactly the milestone issues with the `release highlight` label, and nothing else. Product decides highlights by labelling issues; a regeneration cannot undo that choice.
- Every entry copies the text and references of the issue's regular entry and MUST carry both an issue link and a PR reference.
- If no issue is labelled, leave the section out; do not pick highlights yourself.
- Never edit `:rocket:` by hand: add or remove the label and regenerate.

## 7. `:lock:`: security fixes

Generated from the advisories that declare the version in `patched_versions`; never written by hand.

- **Source and filter**: `python3 scripts/gh.py advisories --patched-version <VERSION>` keeps the advisories whose `vulnerabilities[*].patched_versions` contain the version as an exact token (so `2.19.1` never matches `2.19.10`). Any state is valid — `triage`, `draft`, `published` — because the advisory may still be unpublished when the changelog is written.
- **Fill the advisories first**: `patched_versions` must be filled before generating the changelog. The release process publishes the advisories with (or after) the release; the changelog does not wait for publication.
- **PR reference**: a merged PR in the milestone labelled `pr pt` whose body contains the GHSA id contributes its number inline; several PRs go comma-separated. An advisory with no PR is legal: the entry links only the advisory.
- **Missing `patched_versions`**: when a `pr pt` PR references an advisory that declares no matching `patched_versions`, `security` prints a warning on stderr and leaves it out. Fix the advisory, not `CHANGES.md`.
- **Entry format**: `- Fix <description> (<advisory URL>) (PR: [#PR](...))`. The description is `Fix` plus the advisory summary, with the first word decapitalized unless it starts with an acronym (`SVG`, `CVE-...`).
- **Wording**: the flow may polish the description. Re-running `security --write` rewrites it from the summary, so the durable place for wording is the advisory summary on GitHub; keep it a problem statement, because the generator adds `Fix`.
- **Empty version**: no matching advisories → the subsection is not inserted (and is removed by `--write`).
- **Never hand-edit the section**: generate it with `security` (section 8).

## 8. Flow

1. **Confirm the version** (a semver string such as `2.19.0`) with the user when it is not obvious.
2. **Fetch the milestone issues**: `python3 scripts/gh.py issues "<VERSION>" --exclude "release blocker,no changelog"` (closed by default; add `--state all` only to inspect open issues). `gh.py` already drops `Task` and `Rejected`; pass `--include-tasks` / `--include-rejected` only to inspect them. To find what is missing from an existing section, add `--compare CHANGES.md`.
3. **Fetch PR details when needed**: `python3 scripts/gh.py prs <numbers...>` (also `--milestone`). Use it for the PR author, labels and merge state.
4. **Categorize** by Issue Type and the two labels (section 2), and **build the descriptions** (section 4).
5. **Verify PR merge status** before writing: `python3 scripts/changelog.py check-merged <PR numbers...>`. Only merged PRs go in the changelog; if a closing PR was closed unmerged, look for the PR that superseded it.
6. **Reconcile the existing text** (section 9).
7. **Generate `:boom:`**: `python3 scripts/changelog.py boom "<VERSION>"` prints the section; add `--write` to replace it in `CHANGES.md`. The write replaces only that subsection, inserts it after the version header when missing, keeps the heading when there are no entries, and is idempotent.
8. **Generate `:rocket:`** from the `release highlight` label (section 6).
9. **Generate `:lock:`**: `python3 scripts/changelog.py security "<VERSION>"` prints the section; add `--write` to replace it in `CHANGES.md`. The contract lives in section 7.
10. **Cross-check PRs**: `python3 scripts/changelog.py cross-ref "<VERSION>"`. It lists merged milestone PRs missing from the section and warns about closed (unmerged) PRs. PRs linked from `:lock:` count as referenced; an advisory with no PR is invisible here by design.
11. **Generate the anomaly report**: `python3 scripts/changelog.py report "<VERSION>"`. It overwrites `CHANGES-ISSUES.md` with the current state (section 10).

## 9. Pre-flight: reconcile before writing

Re-fetch the milestone (do not trust cached data) and fix these in `CHANGES.md` before adding the new section:

1. **Duplicate across versions** → remove it from the current section; the earlier version wins for backports. The same holds after merging a patch branch into `staging`: the fix keeps its released patch entry and leaves the newer, unreleased section (the merge conflict in `CHANGES.md` is resolved by keeping both sections, newest first, minus the duplicate).
2. **Stale milestone assignment** (the issue moved out of this milestone) → remove the entry.
3. **Newly applied exclusion labels** (`no changelog`, `release blocker`) → remove the entry.
4. **Issue no longer closed, deleted, or now `Rejected`** → remove the entry.
5. **Unmerged or moved PR reference** → fix the reference or remove the entry. A PR merged in a different milestone is an anomaly (section 10); do not remove it silently.
6. **Issue Type changed to `Task`** → remove the entry.
7. **Breaking change out of sync** (a `breaking change` issue outside `:boom:`, or a `:boom:` entry without the label) → regenerate the section; a labelled issue has no regular entry.
8. **Highlight out of sync** (`:rocket:` entry without the label, or a labelled issue missing) → remove it or add it.
9. **Missing valid issues** (closed, non-excluded, referenced nowhere) → add them to their section.

## 10. Anomaly report

`report` classifies problems it cannot decide alone. The total counts A, B, E, F and G; C and D are warnings.

- **A — issue in this milestone, referenced PR in another milestone (or none).** The claim "this issue is fixed by this PR in this milestone" is inconsistent. Fix the milestone on the issue or the PR, or remove the entry.
- **B — PR in this milestone whose closing issue is in another milestone.** Same fix. An issue with no milestone is not an anomaly (another, probably private, project).
- **E — `:boom:` entry whose issue lacks the `breaking change` label.** The entry stays in the changelog: either label the issue or move the entry to its regular section.
- **F — `:rocket:` out of sync with the `release highlight` label.** A rule violation: `:rocket:` must list exactly the labelled issues. Fix the label or re-run the flow.
- **G — `breaking change` issue missing from `:boom:`.** A rule violation: `:boom:` must list exactly the labelled issues. Regenerate the section or relabel.
- **C — released X.Y.0 version without a `:rocket:` section** (gap; patches never carry highlights).
- **D — `:rocket:` entry without issue AND PR references** (gap).

Anything else is a rule violation, not a report item: fix it in the pre-flight and re-run.

## 11. Pitfalls

- **Open issues with provisional entries**: a PR author may add the entry before the merge. `boom` will not generate it until the issue closes; keep the hand-written block identical to what the generator would produce.
- **Labels, not emoji**: title prefixes are cosmetic and often missing; the section comes from Issue Type and the two labels.
- **Taiga references**: old entries link to Taiga. Resolve them to the GitHub issue via the description text or the PRs that mention the Taiga URL, then link issue and PR.
- **Long-lived links only**: runbooks and docs referenced from a note must point to `staging` or `main`, never to a feature branch that will be deleted.
- **Generated sections**: `:boom:`, `:rocket:` and `:lock:` are generated. Never patch them by hand; edit the label, the note comment or the advisory and re-run.
- **Deleted or transferred issues**: an issue referenced in the changelog that returns 410/404 (deleted or moved out of the repo) must lose its entry in the pre-flight. Its security fixes, if any, stay in `:lock:` through the advisory.

## 12. Where the code is

- `scripts/gh.py`: `issues`, `issue`, `comments`, `prs`, `link-issue`, `advisories` (with `--patched-version`). Details: `mem:scripts/gh`.
- `scripts/changelog.py`: `check-merged`, `boom`, `security`, `cross-ref`, `report`. Details: `mem:scripts/changelog`.
- Tests: `scripts/test_gh.py`, `scripts/test_changelog.py`.
- The note contract for issue authors: `mem:workflow/creating-issues`, section **Breaking changes**.
