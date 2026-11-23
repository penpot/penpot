# Changelog helper

Use `python3 scripts/changelog.py <subcommand> --help` for current CLI options. For release policy, eligibility and approval order see `mem:workflow/updating-changelog`.

- `collect` reads GitHub and the local changelog and prints a JSON snapshot; the snapshot can include unpublished release or security material. The consuming commands accept `--snapshot` for the same milestone. `cross-ref` and `report` still read the current changelog; `report` can query GitHub for additional references missing from the snapshot.
- `boom` and `security` print by default. `--write` replaces or removes their named subsection only. `cross-ref` checks the version section without writing; `report` overwrites the report file even without a write flag. `dev-only-bugs --apply` is a GitHub write, not a report option.
- Keep version lookup, reference parsing and eligibility shared across commands. Do not replace link-aware matching with bare issue numbers; legacy links are still supported. Batch unknown references through `scripts/gh.py`; keep queries out of per-entry loops.
