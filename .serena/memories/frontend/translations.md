# Frontend translations

- `frontend/translations/en.po` defines the keys and English text; `es.po` is a support reference, not the base. Add new keys to both in the same change. Locale conventions: `mem:frontend/translations/ca` and `mem:frontend/translations/ru`.

## Keys and catalog sync

- Use feature-specific keys for feature vocabulary and `labels.*` for short labels shared across screens. Keep keys literal at `(tr "key")` call sites so `rehash` and the linter can see them. Branch outside `tr`, use a `case` with literal calls, or pass already translated text. Shortcut labels use literal calls in render-time `:label` functions; see `app.main.ui.shortcuts` and the command definitions.
- Only open sets that cannot be resolved statically should use the legacy `;; (tr "key")` declarations beside the call. Server-provided keys carry `#, backend`; don't remove or rename them or change their placeholder contract. Follow the existing marker and lint exception at that boundary.
- From `frontend/`, `node ./scripts/translations.js sync -l <locale>` copies base comments and flags and drops keys absent from `en`; it does **not** add missing keys. It can restore `fuzzy` from `en`, so strip that flag from completed entries after the last sync. Drop `#~` obsolete entries rather than reviving them. Do not run a repo-wide `rehash` for a local string edit; it can rewrite unrelated references and `unused` flags.
- Keep placeholders, link targets, markup, escapes and key names intact. Validate each plural form against the call site and the locale header. A plural key with one regular `msgstr` loses runtime count selection; a single-form locale instead keeps `msgid_plural` and one count-bearing `msgstr[0]`. If English text is empty, resolve it from Spanish and source context rather than leaving the translation empty.

## Verification by scope

- For a string change, verify the changed keys and placeholders in the catalogs you touched. New product keys belong in both `en.po` and `es.po`; don't expand the diff to fix unrelated coverage gaps.
- For a whole-locale pass, compare its active keys with `en.po`, check plural forms and placeholders, and require no missing, fuzzy or untranslated entries for a claim of full coverage. Use `msgfmt --check --statistics -o /dev/null <locale>.po` and, from `frontend/`, `node ./scripts/translations.js check -l <locale>`. The latter skips fuzzy entries and only runs universal checks when no word catalog exists; review warnings rather than treating silence as full coverage.
- When a valid word trips the glue detector, check `frontend/scripts/check-translations/words.<locale>.txt` and prefer a targeted `[ok]` entry over broadening `[function]`. `node ./scripts/translations.js check --self-test` exercises the detector. For placeholder drift, trust the `tr` call site; don't remove placeholders from unused entries just to silence warnings.
