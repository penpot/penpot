---
name: migrate-ds-area
description: Migrate one Penpot UI area to the Design System components for one group of the DS migration epic (#12375): buttons and icons (icon-button*, button*, icon*), selects (select*) and later groups. Moves the area's SCSS off the deprecated styles, proves every change with a zero-tolerance visual test, then opens the draft PR and updates the group issue (PRs table row, QA comment). Use when the user asks to migrate an area or module to the DS, continue a DS migration group, or names an area of a group issue.
---

# Skill: migrate-ds-area

Move one UI area (one `.cljs` + `.scss` pair, or a few related modules)
from legacy components and raw HTML to the Design System components,
prove with pixels what changed, and ship it as a draft PR that QA can
test.

The epic is #12375. Each control type is a group with its own issue
(buttons and icons #12376, selects #12377, radios #12378, …). The group
issue holds the plan (order, waiting areas), the changes QA should
expect, and the PRs table where people claim areas. Read it first.

## Input

- The group issue (e.g. `#12376`) and the area: a module path (e.g.
  `dashboard/fonts`) or an area named in the group issue.
- No area given: take the next unclaimed area in the group issue's plan
  order, and say which one.

## Ground rules

- Never `git push`. The user pushes; then you open the PR.
- PRs go to `develop`; branches start from `origin/develop`.
- **Stay in the group.** Only the controls of this group change. In the
  buttons/icons group, selects, radios, numeric/text inputs, checkboxes,
  menus (`%menu-*`, `%dropdown-*`) and modal structure (`%modal-*`) stay;
  a checkbox tick or an input label icon stays legacy.
- **Accept the DS look.** Do not add CSS to imitate legacy sizes or
  colors. Do keep positions: icons and labels must not move unless the
  DS size forces it, and then say so.
- No negative margins to compensate; use positive DS spacing or
  `px2rem()`.
- **Gaps in the DS wait.** An area that needs an icon or a feature the
  DS lacks keeps its legacy control; say so in the PR.
- Skip legacy shared components that another group will replace
  (`context_menu_a11y`, `forms`, `select`, `dropdown`, `radio_buttons`,
  `search_bar`, `title_bar`, …): they wait for that group's cleanup,
  where they are updated if still used or deleted if not.
- Debug-only UI gets no QA comment and no compare image.

## Devenv

Run every test, lint and `pnpm` command inside the workspace container,
never on the host (a host `pnpm` relinks `node_modules` and breaks the
container's watcher). The container is `penpot-devenv-ws<N>-main`, where
`<N>` is the workspace number (`ws0` is the main repo; `wsN` lives in
`~/.penpot/penpot_workspaces/wsN`). Write output to a file, then read it:

```bash
C=penpot-devenv-ws1-main   # your workspace
docker exec -u penpot -w /home/penpot/penpot/frontend $C \
  bash -lc '<command>' > <scratch>/out.txt 2>&1
```

When the app does not behave as the code says, check the build first:

- **Stale build after a branch switch** ("is not a function", or a page
  with empty `stl/css` classes): `touch` the stale `.cljs`/`.scss`, wait
  for the compiled file under `frontend/resources/public/` to update,
  hard-reload.
- **A whole area unstyled:** a new `.scss` added after the watcher
  started is missing from `main.css` (grep `main.css` for the module
  prefix), or `main.css` was written corrupted after many `.scss`
  changed at once. Touch a `.scss`; if the module is still missing,
  restart the watcher in the workspace's tmux session (`penpot:0`,
  `./scripts/watch app`).
- **Blank app after switching between `develop` and `staging`:**
  dependencies differ. Run `pnpm install --frozen-lockfile` in the
  container and restart the watcher.

## Workflow

### 1. Claim the area and branch

1. Read the group issue: plan, waiting areas, expected changes, PRs table.
2. Add a row to the PRs table with no PR number, the user's @login as
   Owner and the state `Claimed` (`gh issue view/edit <n> --body-file`).
   Read the body right before editing, and read it again after saving:
   when two people edit at the same time, GitHub keeps only the last
   edit, so a row can vanish. Put yours back if it is gone.
   Skip an area someone else already claimed, unless the claim has had
   no PR for 7 days and its owner did not answer a comment asking.
3. `git switch -c <user>-ds-<group>-<area> origin/develop`.
4. Survey the area: every control of the group in the `.cljs`, the
   legacy icons, the placeholders and mixins the `.scss` uses, the
   `div`/`span` with `:on-click`.

### 2. Visual baseline

Add a `test.describe("<Area> controls", …)` block to the matching spec
in `frontend/playwright/ui/visual-specs/` (project `ds`, dark theme).
Put it next to the blocks of the same screen, not always at the end of
the file: two PRs that both append at the end conflict.

- Every shot: `toHaveScreenshot({ maxDiffPixelRatio: 0, threshold: 0 })`.
- Show every changed control at rest, on hover and open or pressed.
  Capture the element or the smallest stable container.
- Locators must work before and after the change: text, role, testid,
  `[class*='…']`, or `a.or(b)` when the element type changes (raw
  `[class*='menu-btn']` before, `getByRole("button", { name: "Options" })`
  after). Scope `.or()` to the area so it cannot match elsewhere.
- Missing data? Add a fixture under `frontend/playwright/data/` and mock
  it (`dashboardPage.mockRPC(...)`, `DashboardPage.mockConfigFlags` for
  flags). Watch for expired or inconsistent fixtures (dates in the past,
  wrong file for a mock).
- Capture with `--update-snapshots=all`:
  ```bash
  npx playwright test --project=ds <spec> -g "<describe>" --update-snapshots=all
  ```
  Plain `--update-snapshots` keeps a snapshot within the project's 0.5%
  tolerance, so a real diff looks like 0.
- Copy the snapshots (`<spec>.spec.js-snapshots/<Describe>-*`) to
  `<scratch>/diffs/<area>/before/` and look at them: the state you
  wanted must really be on screen.

### 3. Controls commit

#### Buttons and icons

| Legacy | DS |
|---|---|
| icon-only raw button | `[:> icon-button* {:variant "ghost" :aria-label (tr …) :icon i/x :on-click f}]` (always pass `:variant`; `"action"` for 24px icon toggles) |
| pressed/selected | `:aria-pressed (boolean x)` |
| text button | `[:> button* {:variant "primary"/"secondary"/"ghost"/"destructive" :type "button"} label]` |
| `[:input {:type "button"}]` | `button*` |
| modal close | `icon-button*` ghost, `:aria-label (tr "labels.close")`, `:tooltip-class` carrying the corner position |
| legacy icon outside a button | `[:> icon* {:icon-id i/x :class …}]` |

- Not every raw button changes: rows, tabs, swatches, avatar widgets,
  full-height segments, round custom buttons and link-style text buttons
  stay raw (their legacy icon still changes). List the kept ones in the
  PR.
- A `div`/`span` with `:on-click` in the area becomes a `[:button]`
  (with `aria-expanded`/`aria-haspopup` when it opens something). An
  `[:a]` without `href` stays; list it in the PR.
- **Tooltip wrapper.** `icon-button*` renders inside a tooltip trigger
  wrapper. Position (`position: absolute`, grid placement, margins
  against siblings) goes on `:tooltip-class`; look (`--button-height`,
  colors) goes on `:class`.
- **Tooltip at the right edge.** Small icon buttons at the right end of
  a sidebar row or title bar get `:tooltip-placement "top-left"`; list
  it as an expected change.
- **Sizes through custom properties:** `--button-height: #{px2rem(64)}` /
  `--button-width`. Sass does not evaluate functions inside custom
  properties without `#{}`.
- **A grown `button*` keeps its label at the start**: add
  `justify-content: center` when it is wider than its content.
- **Icon colors:** DS icons use `stroke: currentColor`. Set `color` on
  the icon class, not `stroke`/`fill`. A legacy `color: transparent` left
  on the button or wrapper makes the DS icon invisible: drop it. A class
  that redefines the color variable on the icon breaks the hover that
  changes it on the button: define the variable on the button.
- **Every DS icon needs a color of its own** (on its class or a parent):
  one that inherits none shows in the devenv debug yellow.
- **Icon sizes:** `"s"` draws 12px inside a 16px box (compensate the
  margin by 2px per side to keep the drawing in place); `"m"` 16px; `"l"`
  24px; `"xl"` 32px.
- **Stroke width:** DS icons keep the DS stroke width. Never set
  `stroke-width` on a DS icon to match the legacy one; a thinner or
  thicker line is part of the DS look.
- **Legacy placeholders that style `svg` descendants** (e.g.
  `%dropdown-element-base` → `span svg { 12px box; stroke }`) override a
  DS icon placed inside them: give the icon class its own box
  (`inline-size`/`block-size` 16px) and `stroke: currentcolor` with more
  specificity.
- **Flex shrink:** a DS svg inside a small flex button can shrink and be
  cut; add `flex-shrink: 0` to the icon class.
- **Rotated legacy arrows:** prefer `i/arrow-left`/`-right`/`-up`/`-down`
  over rotating `i/arrow` (same path), unless a rotation class drives the
  open/closed state.
- **Menus anchored by the button size** move when the button grows; open
  them from the top (`inset-block-start: calc(100% + var(--sp-xs))`) or
  fix the context menu offset, and say so.

#### Selects

`[:& select {:default-value v :options [{:value :label}] :on-change f}]`
→ `[:> select* {:default-selected <id> :options <memoized vector of {:id :label}> :on-change f}]`
(`app.main.ui.ds.controls.select`).

- **Ids are non-blank strings.** A blank id shows a dimmed "--", and an
  id that matches no option falls back to the first one. Give mixed,
  none and auto their own ids (`"multiple"`, `"none"`, `"auto"`) and map
  them back in the handler. Use `""` only where "--" is the wanted look.
- **Convert the value.** The legacy select ran `d/read-string`, so
  handlers got keywords, numbers or uuids; `select*` passes the `:id`
  string. Convert with `keyword`, `d/parse-integer` or `uuid/parse`.
- **Memoized options, live selection.** `select*` resets its selection
  when `:default-selected` or the `:options` identity changes. Build
  options with `mf/with-memo` and `mapv` (the schema wants a non-empty
  vector) and pass `:default-selected` from the live value, never a
  constant.
- **Never pass nil.** In dev builds a present prop with value nil
  (`:class`, `:wrapper-class`, `:disabled`, `:default-selected`) throws
  `invalid props on component select*`. Use `(boolean x)`, `d/nilv`, or
  add the prop only when it has a value.
- **Props the legacy select ignored stay out**: `select*` passes rest
  props to its button, so they would reach the DOM.
- **Classes:** `:wrapper-class` for layout, `:class` for look.
- **Portal:** add `:has-portal true` only where the visual test shows the
  list cut at the bottom of a panel, and say so in the QA comment.
- **Locators:** the button is a `combobox` with no accessible name; use
  `getByRole("combobox").filter({ hasText: "Linear" })` (it matches the
  legacy select too) and `getByRole("option", { name: … })`.
- **Keyboard:** the arrows no longer change the value while the list is
  closed. It is an expected change, already listed in #12377.

#### All groups

- New labels: add the key to `en.po` and `es.po` by hand, in sorted
  position, with a `#:` reference to the cljs line. Never run the
  translations sync.
- The `.scss` only gets what the new controls need in this commit.
- Some e2e specs are not Prettier-clean on develop: never run
  `prettier --write` on a whole spec; format only your block.

Then wait for the watcher (grep the compiled JS/CSS under
`frontend/resources/public/` for the change), recapture with `=all`,
copy to `after/`, diff:

```bash
SK=.agents/skills/migrate-ds-area/scripts
python3 -I $SK/diff.py <before> <after>              # per-file pixel count + bbox
python3 -I $SK/trip.py <b> <a> <out.png> <scale>     # before | after | red diff
```

`trip.py` pads images of different sizes, so a taller "after" still
renders.
Explain every diff. Fix what is not a DS-look change (moved icons, cut
icons, lost hover color). Rerun a shot that changes on unchanged code
before trusting it; flaky spots seen so far: the invite modal textarea
border, the search input caret, the user account page, the path handler
menu trigger.

Lint, then commit (read `mem:workflow/creating-commits` first):

```bash
npx prettier --check <spec> <fixtures>; npx stylelint <scss>; clj-kondo --lint <cljs>; cljfmt check <cljs>
git add -f <cljs> <scss> <po> <spec> <fixtures>   # -f: visual-specs/ is gitignored
./scripts/check-commit
```

A plain `git add` with an ignored path in the list adds nothing; always
`-f`, and check `git show --stat`. Never commit the screenshots.
Subject: `:recycle: Use DS <controls> in <area>`. A pre-existing bug
found and fixed on the way goes in its own `:bug:` commit.

### 4. SCSS commit

Run the `refactor-scss` skill on every `.scss` the PR touched (only
those), in its own commit. Keep `refactor/` only for component-family
placeholders (`%modal-*`, `%menu-*`, `%input-*`, `%checkbox-*`) and say
why in a comment above the `@use`. Unused selectors are deleted, not
commented out (list them in the commit body). Typography without an
exact match uses the closest DS typography. Run the skill's
**Self-review** before committing: no bare element selectors (add
classes in the `.cljs`), no copied properties that do nothing, z-index
tokens by purpose. Compare against the controls commit: it should be
0 px; explain anything else (typically a typography or border change).
For files that several modules share or that are huge, stop and propose
a separate PR instead.

### 5. Hand off, PR and tracking

1. Report to the user: commits, visible changes, kept raw controls,
   pre-existing bugs, and the compare image path
   (`<scratch>/diffs/<area>/compare.png`, before | after | diff). Give
   the push command: `git push -u origin <branch>` (the branch tracks
   `origin/develop`, so a bare `git push` is refused).
2. After the user pushes, check `git rev-parse HEAD origin/<branch>`,
   then (read `mem:workflow/creating-prs` first):
   ```bash
   gh pr create --repo penpot/penpot --draft --base develop --head <branch> \
     --title "<commit subject>" --body-file <body> \
     --label "WG adoption" --label "no issue required" \
     --milestone <group issue milestone> --project Main
   gh pr edit <n> --repo penpot/penpot --remove-label "needs triage"
   ```
   Never add `no changelog` to a PR: that label goes on issues.
   Body: AI note, `Part of #<group> (DS migration epic #12375).`, a
   short What / How, the kept raw controls, an empty `## Screenshots`
   when something is visible (the user attaches the image). Never
   `Closes #<group>`. Keep it short: reviewers find long PRs hard to read.
3. In the group issue's PRs table, fill your row:
   `| #<n> | <area>: <controls> (<visible changes>) | @<owner> | Draft |`.
4. Post a QA comment on the group issue: `**#<n>: <area>**`, *Where* (how
   to reach it), *Expected changes* (every visible change; QA treats
   anything not listed as a bug), *No other change* (what still works
   the same).

Do not mark the draft ready or ask for review: the user decides when.

### 6. Review fixes

When the reviewer comments on a PR:

1. Fix each comment in a new commit on the branch (never amend pushed
   commits). A comment you do not apply gets a short reason the user can
   post, with an image when it is visual.
2. Rerun the area's visual spec before and after the fix with `=all`.
   Build a compare image for every visible change and give the user its
   full path, saying which PR it goes to.
3. Update everything the fix makes stale, in the same turn: the QA
   comment (*Expected changes* must list the new visible changes), the
   PRs table row, and the PR body.
4. When working on several PRs in one go, report per PR: branch, new
   commit, what changed, image path. Never mix two branches in one list.
5. Give the push command per branch. If a rebase is coming soon, say
   that pushing now costs one extra CI run; the user decides.
6. After a round of review fixes, check whether the same issue exists in
   the user's other open PRs of the group, and fix it there too.

### 7. After merges

Whenever PRs merge (or go from draft to ready), sync the state column of
the group's PRs table with GitHub for **every** row, not only your own:
read each row's PR (`gh pr view <n> --json state,isDraft`) and set
Merged / In review / Draft. The table drifts when other people merge.

## Output

- Branch with 2 commits (controls, SCSS) plus any `:bug:` commit.
- Visual spec block with zero-tolerance shots.
- Compare image in the scratch folder.
- After the push: draft PR, row and QA comment in the group issue.
