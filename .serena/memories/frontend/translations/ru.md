# Russian UI conventions

- Use formal `Вы` in prose and infinitives in buttons and menus (`Создать`, `Удалить`), not imperatives. Use `доска` for board, not `кадр`; use `сочетание клавиш` for shortcut, not `ярлык`. Match shipped `ru.po` in the relevant domain, especially plan names and token terminology; don't normalize from Spanish. Keep token type identifiers in English when they represent literal values.
- `words.ru.txt` deliberately excludes one-letter words and particles that can look like inflected endings from `[function]`. Do not complete that list; put specific false positives in `[ok]`. `пока` is also excluded: together with `за` it misclassifies `показа`.
- Spell out `и так далее` and `то есть` rather than `т. д.` and `т. е.`; lone letters skew the glue detector. Avoid standalone `те` where an equivalent phrase works (`такие же` instead of `те же`); a lone `те` can make `темы` look like `те` + `мы`.
- Preserve Penpot-specific markup such as `[text|target:self](%s)`. Follow `ru.po` for billing notation and spacing (`175 $/мес.`, `10 ГБ`).
