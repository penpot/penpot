# Penpot Plugin API — Bug Fixes on `fix/plugin-openpage-context-sync`

**Branch:** `fix/plugin-openpage-context-sync`
**Base:** upstream `develop` (`52496243a`)
**Date:** 2026-03-20
**Tested on:** Self-hosted Penpot (develop build) with MCP plugin

This branch consolidates 12 commits fixing plugin API bugs discovered during MCP-driven design system construction. All bugs are in `frontend/src/app/plugins/`.

---

## Summary

| # | Bug | Severity | Commits | Upstream issue |
|---|-----|----------|---------|----------------|
| 1 | `openPage()` doesn't change plugin execution context | P0 | `0db5edc` | [#8321](https://github.com/penpot/penpot/issues/8321) |
| 2 | `theme.addSet()` crash — nil name from async state race | P0 | `dec0356`, `df23c9c` | [#8520](https://github.com/penpot/penpot/issues/8520) |
| 3 | `createColor()` / `.clone()` return broken proxy — async state race | P1 | `353c038` | |
| 4 | `createTypography()` / `.clone()` return broken proxy — async state race | P1 | `8826e42` | |
| 5 | `createComponent()` — lib-component-proxy nil crashes | P1 | `deea329` | |
| 6 | `shape.clone()` nil return-ref crash | P1 | `b64d7f7` | |
| 7 | `page.name` getter crash on async state race | P1 | `3cbccac` | |
| 8 | `flow.startingBoard` getter inconsistent nil guard | P2 | `56e8196` | |
| 9 | Flow proxy async state race in page.cljs | P2 | `fe62b37` | |
| 10 | `addRulerGuide()` async state race | P2 | `3577567` | |
| 11 | Compilation failure — paren mismatches from merge | Build | `6f2b232` | |

---

## Bug 1: `openPage()` doesn't change plugin execution context

**Fixes:** [#8321](https://github.com/penpot/penpot/issues/8321)
**Commit:** `0db5edc` — Fix openPage not updating plugin execution context
**Files:** `api.cljs`, `page.cljs`

### Problem

`penpot.openPage(page)` navigates the user's UI viewport but does NOT change the plugin's execution context. After calling `openPage()`:
- `penpot.currentPage` still returns the original page
- Shapes created via `penpot.createRectangle()` etc. land on the original page
- Cross-page `appendChild()` silently fails

This made multi-page workflows impossible via MCP/plugins without manual user intervention.

### Root cause

`openPage()` only emitted `go-to-workspace` — a Potok `WatchEvent` that triggers an async chain:

```
st/emit!(go-to-workspace) → router navigation → React re-render
  → component useEffect → st/emit!(initialize-page*) → sets :current-page-id
```

This chain never completes within a plugin execution because React render cycles are scheduled on animation frames, which don't fire while plugin code is executing.

Meanwhile, `create-shape` reads `(dsh/lookup-page @st/state)` which uses `:current-page-id` — still the old value.

### Fix

Emit a synchronous `ptk/UpdateEvent` that sets `:current-page-id` immediately before the async navigation event. Potok processes `UpdateEvent`s synchronously via `swap!` on the state atom, so the plugin context is correct by the time the next line of code runs.

```clojure
(st/emit! (ptk/reify ::open-page-context
            ptk/UpdateEvent
            (update [_ state]
              (assoc state :current-page-id id)))
          (dcm/go-to-workspace :page-id id ::rt/new-window new-window))
```

Applied to both `penpot.openPage(page)` in `api.cljs` and `page.openPage()` in `page.cljs`.

### Verified

```javascript
penpot.currentPage.name;              // "cover"
penpot.openPage(targetPage);
penpot.currentPage.name;              // "atoms-button" — immediate!
const r = penpot.createRectangle();   // lands on "atoms-button" ✓
```

---

## Bug 2: `theme.addSet()` crash — nil name from async state race

**Commits:** `dec0356`, `df23c9c`
**Files:** `tokens.cljs`

### Problem

Calling `theme.addSet(tokenSetProxy)` after `catalog.addSet(name)` crashes the workspace:

1. `catalog.addSet(name)` creates the set via `st/emit!` (async) and returns a proxy
2. The proxy's `:name` getter calls `locate-token-set` on `@st/state` — but the async `st/emit!` hasn't propagated yet, returns `nil`
3. `theme.addSet(proxy)` reads `proxy.name` → gets `nil`
4. `enable-set` conjs `nil` into the theme's `:sets` → `:sets #{nil}`
5. Backend rejects with 400 → workspace reloads → plugin disconnects

### Fix

- **Multi-arity `token-set-proxy`**: accepts `initial-name` fallback for the async window
- **`catalog.addSet`**: passes set name at proxy construction time
- **`theme.addSet` / `theme.removeSet`**: nil guards prevent `nil` from reaching `enable-set`/`disable-set`

---

## Bugs 3–6: Async state race in library proxy constructors

**Commits:** `353c038`, `8826e42`, `deea329`, `b64d7f7`
**Files:** `library.cljs`, `shape.cljs`

### Pattern

All library creation methods (`createColor`, `createTypography`, `createComponent`) and `.clone()` share the same bug pattern:

1. Method calls `st/emit!` to create the asset (async)
2. Returns a proxy object immediately
3. Proxy's getters (`:name`, `:path`, `:color`, etc.) call `locate-library-*` on `@st/state`
4. State hasn't propagated yet → `locate-library-*` returns `nil`
5. Getter crashes with NPE or returns garbage

### Fix

Same pattern as the token-set fix:
- Multi-arity proxy constructors with `initial-*` fallback parameter
- Creation methods pass the initial values at proxy construction time
- Nil guards (`when-let`, `some?`) in setters to prevent writes on stale state

---

## Bugs 7–10: Other proxy nil guards

**Commits:** `3cbccac`, `56e8196`, `fe62b37`, `3577567`
**Files:** `page.cljs`, `ruler_guides.cljs`

- **page-proxy name getter**: nil guard when page lookup returns nil during navigation
- **flow-proxy startingBoard getter**: consistent `some?` guard
- **flow-proxy async state race**: multi-arity with initial fallback
- **addRulerGuide**: nil guard on ruler guide proxy for async state race

---

## Bug 11: Compilation failure from merge

**Commit:** `6f2b232`
**Files:** `tokens.cljs`, `library.cljs`

Paren mismatches introduced during the merge of async-state-race fixes (multi-arity refactoring) with upstream develop. Missing/extra closing parens in:
- `tokens.cljs`: `removeSet` `:fn` missing 1 `)`; `catalog.addSet` `:fn` missing 2 `)`
- `library.cljs`: `lib-color-proxy` and `lib-typography-proxy` multi-arity closings off by 1-2 parens each

---

## Files changed

| File | Bugs addressed |
|------|---------------|
| `frontend/src/app/plugins/api.cljs` | 1 (openPage) |
| `frontend/src/app/plugins/page.cljs` | 1, 7, 8, 9, 10 |
| `frontend/src/app/plugins/tokens.cljs` | 2, 11 |
| `frontend/src/app/plugins/library.cljs` | 3, 4, 5, 11 |
| `frontend/src/app/plugins/shape.cljs` | 6 |
| `frontend/src/app/plugins/ruler_guides.cljs` | 10 |

---

## Not fixed by this branch

These are upstream behaviors/bugs observed during MCP testing but not addressed here:

| Issue | Description |
|-------|-------------|
| Token API signature mismatch | `addSet({name})` documented but only positional `addSet("name")` works |
| `catalog.sets` crash on empty catalog | Protocol dispatch on nil when no token sets exist |
| `createText('')` returns null | Silent null return for empty string input |
| Component name doubling | `createComponent` prepends shape name to component path |
| Slash normalization | `board.name = 'a/b'` stored as `'a / b'` |
| `comp.name` returns leaf only | Full path only available via `comp.mainInstance().name` |
| Orphaned shapes after crash | No transactional execution/rollback |

---

## How to test

```bash
cd /srv/penpot/penpot-git-dollro
git checkout fix/plugin-openpage-context-sync
./manage.sh build-frontend-bundle
# Then build Docker image and restart (see README)
```

MCP verification:
```javascript
// Bug 1: openPage
penpot.openPage(penpotUtils.getPageByName("some-page"));
penpot.currentPage.name; // should be "some-page" immediately

// Bug 2: theme.addSet
const catalog = penpot.library.local.tokens;
catalog.addSet("test-set");
const set = catalog.sets.find(s => s.name === "test-set");
set.name; // should be "test-set", not null
```
