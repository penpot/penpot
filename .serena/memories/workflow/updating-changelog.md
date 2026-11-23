# Updating the changelog

Use this runbook to prepare a release or audit a version. Only update `CHANGES.md` when authorized under `AGENTS.md`. GitHub is the source for release entries; generate the breaking and security sections, not hand-written substitutes. See `mem:scripts/changelog` for command behavior and `mem:scripts/gh` for GitHub lookups.

## Sources and eligibility

- The milestone supplies the closed issues. Exclude `Task`, project status `Rejected`, and issues labelled `no changelog`. `release blocker` alone is not an exclusion. An issue without a milestone is not a changelog candidate. The issue is the release unit; a PR with `no issue required` does not supply an entry of its own.
- Issue Type gives the regular section: `Feature` → `:tada:`, `Enhancement` → `:sparkles:`, `Bug` → `:bug:`. Don't infer classification from title emoji or labels such as `bug`.
- `breaking change` puts the issue only in `:boom:`, not its regular section. `release highlight` adds a `:rocket:` entry for an eligible labelled issue; do not select highlights on your own. Security advisories supply `:lock:` independently of issue entries, so the same fix can also have a regular bug entry.
- Use only merged PRs. If a closing PR was closed unmerged, look for the merged replacement. Ignore references that plainly close an unrelated issue. If no PR is available, link the issue alone; if a PR has no matching closed milestone issue, don't manufacture an entry for the PR.

## Entry rules

- Add the newest version first; omit empty sections. From 2.20.0, the order is `:boom:`, `:rocket:`, `:lock:`, `:tada:`, `:sparkles:`, `:bug:`. Preserve the older bug-first, combined feature/enhancement layout in existing 2.19.x lines and their patches. Keep one blank line between sections and no manual wrapping within entries.
- Regular bullets describe user-facing behavior in the imperative, based on the issue title, then link the issue and append merged fix PR links inline. For `community contribution` on the issue or PR, credit the PR author before the issue link, not the issue author.
- `:rocket:` is label-driven and has no generator. Copy the applicable entry's wording and links into the highlight; it must carry both issue and PR references. Omit the section when no issue is eligible. The in-app update dialog uses this section.
- `:boom:` uses the issue label and the newest `## Breaking change note` comment, not the issue body. See `mem:workflow/creating-issues` for authoring. If the generator warns about a missing note or multiple notes, fix the source before regenerating. An unclosed issue will not be generated even if a provisional block is already present.
- `:lock:` uses exact matches in advisory `patched_versions`, including unpublished advisories. A merged milestone PR labelled `pr pt` and naming the advisory can supply a PR reference; an advisory without a PR is valid. If a `pr pt` PR refers to an advisory with no matching patch version, fix the advisory. Durable wording belongs in its summary; the generator adds `Fix` and regenerates the text.

## Flow

### Collect

Confirm the release version. Save `python3 scripts/changelog.py collect "<VERSION>"` output in a private temporary file (`$snapshot`); it can contain unpublished advisories and PR text. Reuse it through review and verification. Collect again if GitHub changes, or if new references now need to be resolved.

### Review

- Reconcile the current version before adding entries. Remove duplicates already listed in an earlier version (earlier wins for backports), stale milestone entries, deleted or transferred issues, and issues now open or excluded. Move retyped issues to the proper section, or remove them if now `Task`. Fix stale PR references, but don't silently drop cross-milestone discrepancies; use **Anomaly report** below. Reconcile the labels for breaking changes and highlights, then add eligible missing issues.
- Review `dev_only_bugs`: `auto` is a Bug under a Feature or Enhancement of the same milestone. An epic parent, `regression` label or unreleased-only environment is a clue, not proof. Show candidates to the user for confirmation; `--apply` must never apply to candidates. Also review eligible non-bug `release blocker` entries with the user: only confirmed internal work should receive `no changelog`.
- Keep review read-only with respect to GitHub. A separate authorized `dev-only-bugs "<VERSION>" --snapshot "$snapshot" --apply` labels only the `auto` set. A partial failure does not undo earlier edits: inspect the printed successes and refresh the snapshot before continuing.

### Write

- When authorized, reconcile and draft regular and highlight entries. Preview generated sections first by omitting `--write`. The version header must exist before replacing the generated sections.

```bash
python3 scripts/changelog.py boom "<VERSION>" --snapshot "$snapshot" --write
python3 scripts/changelog.py security "<VERSION>" --snapshot "$snapshot" --write
```

### Verify

- Use `check-merged` for PRs verified outside the snapshot. Run `cross-ref` to find missing merged PRs and warnings for closed-unmerged PRs. `report` verifies the edited version and **overwrites a local report** (default `CHANGES-ISSUES.md`); it does not modify GitHub or `CHANGES.md`.

```bash
python3 scripts/changelog.py cross-ref "<VERSION>" --snapshot "$snapshot"
python3 scripts/changelog.py report "<VERSION>" --snapshot "$snapshot"
```

## Anomaly report

- Resolve issue/PR milestone mismatches by checking both sides. A known closing issue in another milestone is a mismatch; a closing issue with no milestone is not the same case. Do not silently remove a mismatched PR link to make the report pass.
- A breaking entry without the label needs a source correction or classification as a regular entry; a missing breaking entry needs regeneration or label correction. Highlight entries must match the eligible labels; resolve the underlying decision instead of silently selecting different highlights.
- Treat missing major/minor highlights and highlights lacking issue or PR references as warnings, not the same as counted mismatches. A patch release should not require a highlights section. Treat unconfirmed dev-only bug candidates as warnings as well; see `scripts/changelog.py` for the report codes.
- A deleted or transferred issue can lose its normal entry while a related advisory stays in `:lock:`. For historical Taiga references, resolve the actual GitHub issue from its text or fix PR; matching bare numbers are not enough.
