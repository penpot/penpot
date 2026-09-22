# Frontend Translations

PO-based UI i18n. Files: `frontend/translations/*.po`. `en.po` is the
source of truth (`msgid` = key, `msgstr` = English); `es.po` is a
high-coverage support reference, never the base.

## Workflow

- Canonicalize with `node ./scripts/translations.js sync -l <locale>` from `frontend/`: sorts entries, syncs `#:` comments from `en`, deletes keys missing in `en`.
- `sync` copies ALL comment flags from `en`, including `#, fuzzy`. Translated entries must NOT stay fuzzy: strip the flag after translating (mirror `es.po`, which keeps fuzzy only on genuinely untranslated entries). Fuzzy entries are excluded from `msgfmt --statistics` translated counts.
- Canonical files carry NO `#~` obsolete blocks (`en`/`es` have zero). Drop them; `sync` does not resurrect them.
- Definition of done: `msgfmt --check <locale>.po` exit 0 and `msgfmt --statistics` shows 0 fuzzy, 0 untranslated.
- `rehash` scans `frontend/src` AND `common/src` for `(tr "key"` occurrences (any line counts, including `;;` comments) and refreshes `#:` refs in `en`, marking unreferenced keys `#, unused`.
- Dynamic keys are invisible to `rehash`: eliminate them instead of
  declaring them. Preference order: literal `(tr "key")` args only;
  never put a branch inside `tr`, hoist it out (`(if cond (tr "a") (tr "b"))`);
  resolve code→message maps with `case` returning literal calls;
  key-forwarding components take pre-translated strings (callers translate
  with literals). Only when no simple fix exists (open key sets sent by the
  backend, large data-driven matrices like `shortcuts.*`) declare each key
  with a `;; (tr "the.key")` comment next to the call site (grouped under
  `;; Execution time translation strings:`), otherwise the next `rehash`
  flags them `unused`. The declaration convention is legacy fallback, not
  the default: never introduce new declarations where a hoist or
  pre-translation works, and remove existing ones when fixing the call
  site. Same for `:error/code "key"` data: prefer `:error/fn #(tr "key")`. Any non-literal first arg to `tr` is reported by the `:penpot/tr-dynamic` clj-kondo warning (hook in `.clj-kondo/hooks/i18n.clj`, registered for `app.util.i18n/tr` and `app.common.i18n/tr`) and forbidden by the `tr` docstring: only `(tr "literal" ...)` is statically resolvable. Translation keys must not contain spaces (use `-` or `.`).
- Shortcut command/section/subsection labels (`shortcuts.*`, including `shortcuts.section.*` and `shortcuts.subsection.*`) resolve through `:label` fns holding static `(tr "literal")` calls, executed at render time: commands carry `:label` on their definitions in the `app.main.data.*.shortcuts` namespaces, sections/subsections in static registries in `app.main.ui.shortcuts` next to `translation-keyname` (id lookup with raw-key fallback for stale custom ids). When adding, renaming, or removing commands/sections/subsections, add/update/remove the `:label`/registry entry; `rehash` sees the literals with no comment needed, and the exhaustiveness test fails otherwise. Exempt, untranslated by design: debug `:preview-frame` and colorpicker `:delete-stop` (no PO keys).

## Entry rules

- Keys resolved dynamically from server-provided data carry a `#, backend`
  flag (set it in `en`, `sync` copies it to the locales): do not remove or
  rename these entries, and keep their `%s` placeholders (the backend only
  sends the key). The code side keeps a `;; (tr "key")` marker for `rehash`
  plus a `#_{:clj-kondo/ignore [:penpot/tr-dynamic]}` on the call site.
- New entries take `#:` refs from `en`; copy `#, unused`, never `#, fuzzy`.
- `en` keys with empty `msgstr` (or `#, fuzzy` + empty): translate from `es`/source context, never leave empty.
- Entries whose `en` uses `msgid_plural` need `msgstr[0]`/`msgstr[1]` (header: `nplurals=2; plural=n != 1`). Keep the `msgid_plural` line: a singular `msgstr` on a plural key silently breaks count selection at runtime (the app build reads 1-elem `msgstr` as singular).
- `sync` re-adds `#, fuzzy` on EVERY run for entries fuzzy in `en`; re-strip after the last sync, never before it.
- Preserve verbatim: `%s`/`%d`, `{var}`/`{{...}}`, markdown `[text](%s)`, HTML tags, `\n` positions, brand names (Penpot), key names (Ctrl/Shift/Alt), technical terms (SVG, CSS, HSV, RGB).

## Catalan (ca) conventions

- Normative IEC/Termcat Catalan. Address the user in VOSALTRES (2nd person plural): "Deseu", "Creeu", "Ja teniu un compte?". Buttons/menus use short imperatives ("Crea", "Mou", "Restaura").
- Established glossary (reuse exactly, do not re-coin): layer=capa, board=tauler, stroke=traç, fill=Emplenat, blur=Difuminat, shadow=Ombra, clipboard=porta-retalls, delete=Elimina, rename=Canvia el nom, shortcut=drecera, grid=graella (keep "grid" where the file already does, e.g. grid-layout editing), plugins/extensions UI=extensions, layout=Disposició, gradient=Degradat, wireframing kept as loanword.
- Ela geminada uses the middle dot: Cancel·la, paral·lel, al·lega.
- ALL-CAPS source stays ALL-CAPS in Catalan; keep `$175`-style amounts in IEC format (`175 $/mes`) only where `es` already adapts.
- Shortcut/action names (`shortcuts.*`) are noun/infinitive labels, not sentences. Error strings are direct, no hedging.
- Same English source in different contexts may legitimately differ (verb "Copia" vs noun "Còpia"; "Desactivat" vs "Deshabilitada" agreeing with "drecera"). Normalize only true duplicates.

## QA before commit

- Run `node ./scripts/translations.js check -l <locale>` from `frontend/` (no default locale: pass `-l` explicitly): 0 errors required; review warnings by hand. Word lists live in `frontend/scripts/check-translations/words.<locale>.txt` (`[elision]` `[function]` `[common]` `[ok]` `[brands]`); new valid words that trip the gate go to `[ok]`; `check --self-test` covers the detector rules. Without a catalog only the universal checks run. `#, fuzzy` entries are skipped (known-pending, owned elsewhere).
- Placeholder parity per entry (singular AND each plural form, also enforced by the script); verify `%s` against the `tr` call site when `en`/`es`/code disagree (a `%s` the code never passes renders literally; a dropped one swallows the argument). On `#, unused` keys the script only warns: never "fix" them by deleting placeholders or links, a reactivation may need them.
- Glued words (AI batches drop spaces at wrap boundaries): the script flags function-word splits (`del'equip`, `lapolítica`, `sinecessiteu`), `,/.`/`:` without following space, lowercase+Uppercase joins (`delPenpot`, `oCapitalize`) and `%s` glued to a word.
- Balanced `[]`/`()` in markdown links; no double spaces; no glued words around `·`; trailing spaces match the source.
- `git diff --stat` must touch only `frontend/translations/<locale>.po`.
