---
name: refactor-scss
description: Migrate one Penpot SCSS module off the deprecated styles under frontend/resources/styles/common/refactor/ to the current Design System (ds/ tokens, mixins and px2rem). Pure refactor, no visual change. Use when the user asks to refactor, migrate or clean up a .scss file to the DS.
---

# Skill: refactor-scss

Refactor a single SCSS file so that it no longer depends on the deprecated
styling system, using the current Design System instead.

This is a **pure refactor**. Do NOT introduce visual or behavioral changes.
Preserve the generated CSS behavior exactly unless a rule below requires
otherwise. The rules that allow a small visible change (closest DS
typography, DS border width) want it shown in the visual check and listed
in the report and the commit body.

Reviewers read every line of the result. A line that is
copied but does nothing, a bare element selector or a raw z-index comes
back as a review comment, so the rules below are strict about them.

## Input

- The path to one `.scss` file (e.g. `frontend/src/app/main/ui/foo/bar.scss`).
- If no path is given, ask for it. Do not pick a file yourself.

## Workflow

1. Read the target `.scss` file and the `.cljs` file with the same name next
   to it.
2. Read every deprecated source the file uses, so you inline real values,
   never remembered ones:
   - `frontend/resources/styles/common/refactor/mixins.scss`
   - `frontend/resources/styles/common/refactor/design-tokens.scss`
   - `frontend/resources/styles/common/refactor/spacing.scss`
   - any other file under `frontend/resources/styles/common/refactor/` that
     the stylesheet pulls in (placeholders, borders, z-index, fonts...).
3. Read the DS modules you will map to:
   - `frontend/src/app/main/ui/ds/_utils.scss` (`px2rem()`)
   - `frontend/src/app/main/ui/ds/spacing.scss`
   - `frontend/src/app/main/ui/ds/_sizes.scss`
   - `frontend/src/app/main/ui/ds/typography.scss` (`use-typography()`)
   - `frontend/src/app/main/ui/ds/_borders.scss`
   - `frontend/src/app/main/ui/ds/z-index.scss`
4. Apply the rules below and edit the file in place.
5. Verify (see **Verification** and **Visual check**).
6. Report (see **Output**). Do not commit. When the refactor goes with other
   changes, it belongs in its own commit, after the task's commits.

## Primary goal

After the refactor, the SCSS file must not depend on anything under
`frontend/resources/styles/common/refactor/`. This includes imports, mixins,
placeholder selectors, functions, deprecated variables and design-token
aliases.

### Partial refactor

Some placeholders stand for a whole component family that will move to the DS
in its own PR: `%input-element`, `%input-with-label`, `%input-checkbox`,
`%modal-*`, `%menu-dropdown`, `%menu-item-base`, `%shortcut-*` and similar.
Inlining them in a small task produces big, risky diffs. In that case:

- migrate everything else (tokens, sizes, spacing, small mixins);
- keep the `refactor/` import only for those placeholders;
- list each remaining dependency and the selectors that use it in the report,
  so the user can plan it as a separate PR.

## General rules

- Keep the resulting SCSS as small and readable as possible.
- Avoid duplicated declarations and duplicated selectors.
- Do not change class names.
- Do not change DOM structure.
- Do not change specificity unless required.
- Follow the project's SCSS formatting (Prettier, see **Verification**).

## Imports

Use the DS modules. Import them with `as *` when they expose mixins,
variables or functions:

```scss
@use "ds/_utils.scss" as *;
```

not

```scss
@use "ds/_utils.scss" as utils;
```

Do not introduce namespaces such as `utils.`, `typography.`, `borders.`,
`spacing.` or `sizes.` unless a module explicitly requires one.

Remove every unused import.

## Deprecated mixins

If the stylesheet uses mixins from
`frontend/resources/styles/common/refactor/mixins.scss`:

- locate the mixin;
- inline its generated CSS (then apply the rest of these rules to it);
- remove the `@include`;
- remove the import.

Do not leave deprecated mixins.

## Placeholders

Replace every `@extend %placeholder` with the generated CSS. Remove every
deprecated placeholder, except the component-family ones described in
**Partial refactor**.

Inline only what does something on that element. Do not paste a
placeholder (or mixin) body as it is. For each property, check:

- **It applies to the element.** `fill`, `stroke` and `stroke-width` only
  act on SVG shapes: drop them from a `span`, `div` or `button`.
  `color: transparent` on a wrapper that only holds an icon does nothing
  useful: drop it and set the real color.
- **It is not the default or already given.** `stroke-width: 1px` is the
  SVG default; `fill: none` and `stroke: currentColor` are already set by
  the DS `icon*` (`.icon`). Drop them.
- **A later rule does not override it.**
- **It is not a stroke width on a DS icon.** DS icons keep the DS
  stroke width; drop any `stroke-width` that only imitates the legacy
  icon (1.33px, 2.25px...).

`%button-icon` is the usual case: on a wrapper it leaves `color:
transparent; fill: none; stroke-width: 1px`, and none of the three is
needed.

## Element selectors

Do not style HTML elements directly (`svg {}`, `h3 {}`, `button {}`,
`input.x {}`, or `.component { svg {} }`).

When no class exists, **add one in the `.cljs`**: a DS component takes
`:class (stl/css :x)` (`icon*`, `heading*`, `button*`…), and a plain
element takes `{:class (stl/css :x)}`. Touching the `.cljs` for this is
part of the refactor. A comment like "the icon has no class" is not an
answer.

Prefer the DS component when one fits: `heading*` for `h1`–`h6`
(`:level`, `:typography`), `text*` for text, `icon*` for icons.

`element.class` (e.g. `input.element-title`) is not needed to beat the
input defaults: try the class alone and check it in the visual check.

An element selector stays only for markup you cannot reach (injected
HTML, third-party widgets), with a comment saying so.

**Icon colors.** A DS icon draws with `stroke: currentColor`. Set
`color` on the icon class or its parent, never `stroke`/`fill` on an
`svg` selector.

## Color variables

Whenever a color changes across states, introduce a component-local CSS
variable.

Instead of

```scss
.button {
  color: var(--color-foreground-primary);

  &:hover {
    color: var(--color-accent-primary);
  }

  &:disabled {
    color: var(--color-foreground-disabled);
  }
}
```

write

```scss
.button {
  --button-foreground-color: var(--color-foreground-primary);

  color: var(--button-foreground-color);

  &:hover {
    --button-foreground-color: var(--color-accent-primary);
  }

  &:disabled {
    --button-foreground-color: var(--color-foreground-disabled);
  }
}
```

Use this pattern for `color`, `background-color`, `border-color`,
`outline-color`, `fill`, `stroke` and any similar property.

## Design token aliases

Remove every alias defined in
`frontend/resources/styles/common/refactor/design-tokens.scss`. Resolve each
alias to its final DS token. Do not keep intermediate aliases.

Instead of `color: var(--button-foreground-hover);` write
`color: var(--color-accent-primary);`.

## Spacing

Replace deprecated spacing variables using
`frontend/src/app/main/ui/ds/spacing.scss` for `margin`, `padding`, `gap` and
`inset`.

If no exact spacing token exists, use `px2rem()`. Do not approximate with the
closest token.

## Sizes

Replace deprecated size variables using
`frontend/src/app/main/ui/ds/_sizes.scss` for `inline-size`, `block-size`,
`width`, `height` and min/max sizes.

If no exact size exists, use `px2rem()` from
`frontend/src/app/main/ui/ds/_utils.scss`. Do not leave hardcoded pixel values
unless absolutely necessary.

## Typography

When any of `font-size`, `font-weight`, `line-height`, `font-family` or
`letter-spacing` appear, check whether together they match one of the
definitions in `frontend/src/app/main/ui/ds/typography.scss`.

If they match, replace all the individual declarations with:

```scss
@include use-typography("<typography-name>");
```

If there is no exact match, use the **closest DS typography** (same role:
a panel title in uppercase → `headline-small`, body text → `body-*`),
even when more than one detail differs. Show the change in the visual
check and state it in the report and in the commit body (e.g. "11px
regular → 12px medium").

Keep the original declarations only when no DS typography fits the role
(e.g. a 32px display number in a 14px-only area); then leave a TODO
naming the closest one and ask the user.

## Borders

Replace deprecated border radius and border width values using
`frontend/src/app/main/ui/ds/_borders.scss`. A raw width close to a token
(`1.75px`) takes the token (`$b-2`); say so in the report. If nothing is
close, use `px2rem()`.

## Logical properties

Prefer logical properties whenever they preserve the existing behavior:

- `width` → `inline-size`
- `height` → `block-size`
- `max-width` → `max-inline-size`
- `margin-left` → `margin-inline-start`
- `padding-right` → `padding-inline-end`
- `top` → `inset-block-start`
- `left` → `inset-inline-start`

## Z-index

Never leave magic z-index values. Use tokens from
`frontend/src/app/main/ui/ds/z-index.scss`, and pick the token **by what
the element is, not by its number**: a dropdown, menu or select →
`--z-index-dropdown`; a toolbar, palette, sidebar or panel →
`--z-index-panels`; and so on (read the comments in that file). A raw `4`
on a dropdown becomes `var(--z-index-dropdown)` even though the number
changes. Check the visual check for overlap.

Leave a TODO only when the element fits no token's purpose.

Legacy z-index values between sibling elements that do not overlap can be
removed, but only when the **Visual check** shows no pixel change in every
state that matters (open menus, collapsed, hidden). Say so in the report.

## Duplicated properties

Within the same selector, avoid duplicated properties, duplicated CSS
variables and duplicated declarations introduced by the refactor. Allow
duplicates only when they are intentional browser fallbacks.

## Unused classes

Open the `.cljs` file with the same name as the SCSS file and check whether
every CSS class is referenced (as `(stl/css :class-name)`,
`(stl/css-case :class-name ...)` or similar).

CSS modules prefix every class with its module
(`main_ui_<path>__<class>`), so only the `.cljs` of the same module can use
it. Before calling a selector unused, also search the whole `frontend/src`
for the class name as a string, a `:global` use, or a dynamic class built
at runtime.

If a selector is unused after that check:

- delete it (do not leave it commented out, do not add a TODO);
- list every deleted selector in the report and in the commit body.

A rule that matches nothing because the markup changed (e.g. `.icon svg`
when the DS icon is the `svg` itself) is deleted the same way. Before
deleting a descendant rule, check every element it matched, not only
the one you changed: `.placeholder .icon svg` can also color a second
icon, which then has no color (in the devenv it shows in the debug
yellow).

Every DS icon needs a color of its own (`color` on its class or on a
parent with a defined color): an icon that inherits nothing shows in
the debug yellow in the devenv.

Classes used by markup injected from outside (e.g. an external widget)
stay, with a comment saying so.

Also check the other direction: every class the `.cljs` passes to
`stl/css` / `stl/css-case` must exist in the `.scss`. A missing one silently
does nothing; report it.

## Sass and CSS pitfalls

- Sass does not evaluate functions inside CSS custom properties. Write
  `--button-height: calc(var(--height) - #{px2rem(16)});`, not
  `... - px2rem(16))`, or the browser gets the literal `px2rem(16)`.
- A `/` between Sass size variables is read as a division
  (`grid-template: $sz-32 / auto` breaks). Split it into
  `grid-template-rows` / `grid-template-columns` (or areas).
- A CSS-wide keyword cannot be part of a shorthand: `inset-inline: unset 0`
  is invalid and stylelint does not catch it. Use the longhands.
- After editing, check the compiled CSS for any of these
  (`frontend/resources/public/css/main.css`, search the module class).

## Uncertainty

If any transformation is uncertain, never guess. Leave a TODO in the code
and explain why in the report.

## Self-review

Before the report, read the final diff as the reviewer would and search
the file for these; each hit must be fixed or have a real reason:

```bash
rg -n 'svg \{|^\s*(h[1-6]|p|hr|input|button|span|a)\b[^-_]|[a-z]\.[a-z-]+ \{' <file>
rg -n 'stroke-width|color: transparent|fill: none|z-index: [0-9]|TODO' <file>
rg -n '[0-9]\.[0-9]+px|[^(]\b[0-9]+px' <file>
```

- an element selector → add a class (see **Element selectors**);
- a property that does not apply or is a default → drop it (see
  **Placeholders**);
- a raw z-index → token by purpose;
- a typography TODO → closest DS typography;
- a raw px value → token or `px2rem()`.

## Verification

Run the project scripts from `frontend/` inside the devenv container
(`penpot-devenv-ws<N>-main` for workspace `wsN`; never run `pnpm` on the
host), writing output to a file first (never pipe lint output into
filters):

```bash
docker exec -u penpot -w /home/penpot/penpot/frontend penpot-devenv-ws<N>-main \
  bash -lc 'pnpm run lint:scss; echo LINT=$?; pnpm run check-fmt:scss; echo FMT=$?' \
  > <scratch>/scss-check.txt 2>&1
```

If the format check fails, run `pnpm run fmt:scss` and check again.

**Dependency check.** A grep for `refactor/` is not enough. For the final
file, check:

- `@use` / `@import` sources: all must be `ds/...` (or the sidebar common
  module, which is DS-only);
- every `@include`, `@extend` and `%placeholder`;
- every `$variable`: which imported module defines it (`$br-*` exists in both
  `ds/_borders.scss` and `refactor/borders.scss`; the `@use` decides);
- every `var(--token)`: defined in the DS (colors, spacing, sizes) or only as a
  `refactor/` alias.

Report any failure as it is; do not hide it.

## Visual check

"No visual change" must be shown, not assumed. When a visual spec covers the
component (`frontend/playwright/ui/visual-specs/`, project `ds`), or one can be
added:

1. Capture before (base branch styles) and after with
   `pnpm exec playwright test --project=ds -g "<test>" --update-snapshots=all`
   in the container. `=all` is required: plain `--update-snapshots` only
   rewrites past the 0.5% tolerance, so a real diff can look like 0.
2. Before each capture, wait until the compiled CSS/JS shows the expected
   version (search `main.css` for the changed rule).
3. Compare with zero tolerance. If pixels change, build the before | after |
   diff (red) image and explain every change in the report.

## Output

1. The diff of the file (`git diff -- <file>`).
2. A summary with:
   - removed imports;
   - removed deprecated mixins;
   - removed placeholders;
   - typography replacements;
   - design-token aliases removed;
   - logical properties introduced;
   - deleted unused selectors;
   - TODOs requiring manual review, each with its reason;
   - remaining `refactor/` dependencies (partial refactor) and where they are
     used;
   - classes missing from the `.scss`, if any.
3. The verification results, including the visual check (pixel count, or why
   it was not possible).
