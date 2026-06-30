# Plugin API Test Suite

A Penpot plugin that is a launcher + runner for a battery of tests exercising the
Penpot **Plugin API** against a live Penpot instance. It doubles as living
documentation of what the public API actually does at runtime.

- A plain TypeScript + Vite Penpot plugin living in `plugins/apps/plugin-api-test-suite`.
- The UI (an iframe) lists auto-discovered tests and lets you run all / a subset /
  one. Each test shows green (pass) or red (fail, with the error message).
- It reports **API coverage**: which members of the public Plugin API the tests
  exercised, measured against `libs/plugin-types/index.d.ts`.
- The same test files run both in the plugin UI and in a headless CI runner, so a
  test is never written twice.

This document is the context a developer (or agent) needs to add tests. Read it
fully before writing any test.

## The one rule that matters most

> **Always call the API through `ctx.penpot`, never the global `penpot`.**

`ctx.penpot` is a recording proxy. Calls made through it are what count towards
coverage and are correctly attributed to the right interface. Calls on the global
`penpot` still work but are invisible to coverage. Same for shapes: operate on the
objects returned by `ctx.penpot.*` (and on `ctx.board`), not on objects obtained
some other way.

## Running and iterating

From `plugins/`:

- Dev server: `pnpm run start:plugin:api-test-suite` (serves on port 4202).
- In Penpot: open the Plugin Manager (Ctrl+Alt+P) and install
  `http://localhost:4202/manifest.json`.
- **Hot-reloading tests:** after editing a `*.test.ts`, click **Reload** in the
  plugin UI. It fetches the freshly built test bundle and swaps in your changes —
  no need to close/reopen the plugin. (The dev server rebuilds the bundle on save.)
- **Adding a _new_ test file:** tests are discovered via `import.meta.glob` at
  build time, and `vite build --watch` does not reliably pick up a brand-new file
  (only edits to files already in its graph). After creating a new `*.test.ts`,
  **restart the watch process** (`pnpm run watch` or `pnpm run init`) and then
  click **Reload** (or reopen the plugin). Editing an existing test file does not
  need this.
- The UI: tests are shown in **collapsible groups** (from `describe`) with per-group
  passed/failed/total counts. Run with **Run all**, **Run selected** (per-test or
  per-group checkboxes), the per-group **Run group**, or the per-row **Run** button.
  Failures expand to show the error. The coverage panel shows the percentage, a
  progress bar, and per-interface get/set/call targets.

## Running in CI

A headless runner executes the same tests against a live instance via Playwright:

```
E2E_LOGIN_EMAIL=… E2E_LOGIN_PASSWORD=… \
  pnpm --filter plugin-api-test-suite run test:ci
```

- It builds `headless.js`, logs in, creates a scratch file, injects the test
  bundle, and prints per-test results + the coverage report.
- Exit code is non-zero iff any test failed (coverage does not affect it).
- Optional env: `PENPOT_BASE_URL` (default `https://localhost:3449`). Against a
  local devenv with a self-signed certificate, prefix the command with
  `NODE_TLS_REJECT_UNAUTHORIZED=0` to avoid a `fetch failed` TLS error.
- `PRINT_UNCOVERED=1` dumps the uncovered targets per interface; `PRINT_STATIC=1`
  dumps the statically-covered ones (see [Coverage](#how-coverage-works-and-how-to-write-tests-that-move-it)).

CI entry points reuse the exact same test files (`src/ci/headless.ts` discovers
them the same way the plugin does).

### Mocked-backend mode

The same runner can run without a live instance — it serves the prebuilt
frontend via the frontend e2e static server and intercepts every backend RPC
with Playwright `page.route`, reusing the frontend e2e mock fixtures:

```
pnpm --filter plugin-api-test-suite run test:ci:mocked
```

(equivalently `MOCK_BACKEND=1 … run test:ci`). No login or backend is needed.
This validates the frontend Plugin API binding + in-memory store only, so it
can't faithfully reproduce results that depend on real backend behaviour
(validation, persistence, generated ids, …). Tests that need the real backend
opt out of this mode by tagging themselves `skipIfMocked`:

```ts
test.skipIfMocked('depends on backend validation', (ctx) => {
  /* … */
});

// or a whole group:
describe.skipIfMocked('Backend-dependent', () => {
  /* … */
});
```

Skipped tests are listed in the runner output. The wiring (fixtures, RPC mocks,
WebSocket mock) lives in `ci/run-ci.ts`; mocked-mode fidelity is its main
limitation, so prefer the live `test:ci` for anything backend-sensitive.

## Anatomy of a test

Tests live in `src/tests/*.test.ts` and are **auto-discovered** (via
`import.meta.glob`) — just create a file matching that glob, no registration list
to update. A file registers one or more tests by calling `test(name, fn)`.

```ts
import { expect } from '../framework/expect';
import { test } from '../framework/registry';

test('creates a rectangle', (ctx) => {
  const rect = ctx.penpot.createRectangle();
  ctx.board.appendChild(rect);

  expect(rect.type).toBe('rectangle');
  rect.name = 'sample-rect';
  expect(rect.name).toBe('sample-rect');
});
```

### Grouping tests

Wrap related tests in `describe(groupName, fn)` to group them. In the UI each group
is a **collapsible section** showing its own passed / failed / total counts, with a
"Run group" button and a select-all checkbox. Tests not inside any `describe` fall
into the `General` group.

```ts
import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

describe('Shapes', () => {
  test('creates a rectangle', (ctx) => {
    /* … */
  });

  test('creates an ellipse', (ctx) => {
    /* … */
  });
});
```

`describe` blocks may be nested in a file. Nested names are **joined into a single
group path** with `" / "`, so the group reveals the file/area it lives in — e.g.
`describe('Layout', () => describe('Flex', …))` produces the group `Layout / Flex`.
Wrap each file's tests in a top-level `describe` named after its area so every
group is recognizable. Several files may contribute to the same group path (they
merge in the UI). Prefer one clear group per feature area.

In the UI each group header shows an aggregate **status dot** rolled up from its
tests: it turns purple while any test in the group is running, red if any failed,
green only once every test passed, and grey until then.

### The test context (`ctx`)

`fn` receives a `TestContext` (`src/framework/types.ts`):

- `ctx.penpot` — the recording proxy over the real `penpot` global. Use it for
  every API call.
- `ctx.board` — a **fresh scratch `Board`** created for this test and
  **removed automatically afterwards**. Append shapes you create to it
  (`ctx.board.appendChild(shape)`) so the user's canvas is left clean. Do not rely
  on it persisting between tests.

The runner also resets shared state between tests: the selection is cleared and the
active page is restored to whatever was active when the run started (both through
the raw `penpot`, so they aren't credited toward coverage). A test that changes the
active page therefore won't leak into later tests.

### Sync or async

`fn` may be `void` or `Promise<void>`; async tests are awaited. Use `async (ctx) =>`
and `await` when the API call is asynchronous (e.g. `uploadMediaUrl`,
`library.availableLibraries()`, token application — see notes below).

### Naming

The test name becomes its id (slugified) and is shown in the UI. Keep names unique
and descriptive; duplicates are de-duplicated automatically but that's confusing.

## Assertions

Import `expect` from `../framework/expect`. It is a small, dependency-free,
jest-like matcher set (it must stay dependency-free — it runs inside the SES
sandbox). Available matchers:

- `toBe(expected)` — `Object.is` equality
- `toEqual(expected)` — deep structural equality
- `toBeTruthy()` / `toBeFalsy()`
- `toBeNull()` / `toBeUndefined()` / `toBeDefined()`
- `toContain(item)` — substring or array membership
- `toHaveLength(n)`
- `toBeGreaterThan(n)` / `toBeLessThan(n)`
- `toBeCloseTo(n, numDigits?)` — for floats
- `toThrow(expected?)` — `expected` is a substring or `RegExp` matched against the
  error message; pass a function as the value: `expect(() => …).toThrow('msg')`
- `.not` negates any matcher: `expect(x).not.toBeNull()`

For asynchronous failures use `expectReject(promiseOrThunk, expected?)`: `toThrow`
calls its argument synchronously, so it can't catch a rejected promise, whereas
`expectReject` awaits and asserts the rejection (string includes / RegExp on the
message).

A failing matcher throws; the runner turns that into a red test with the message.
You can also just `throw new Error('…')` to fail a test.

> Do not add other assertion libraries. Anything imported here is bundled into the
> sandbox and must be SES-safe and dependency-free.

## How coverage works (and how to write tests that move it)

Coverage is **type-aware** and tracks three separate targets per member:

- **`name (get)`** — reading a property (`const n = shape.name`)
- **`name (set)`** — writing a property (`shape.name = 'x'`)
- **`appendChild()`** — calling a method (credited only when actually **called**,
  not when merely referenced)

Implications when writing tests:

- A property has independent get/set targets. To cover both, read it _and_ write
  it. Read-only properties (declared `readonly` in the d.ts) only have a get
  target; methods only have a call target.
- Accessing a member through a value you got from `ctx.penpot` is what counts.
  Reaching a nested object also counts: e.g. `ctx.board.children[0].type` records
  `Board.children (get)` and then the element's `type` get, resolved to the
  concrete shape type at runtime.
- Coverage **accumulates across a run**. Running all tests aggregates every test's
  accesses. Running a single test shows only that test's accesses.

### Recorded vs. effective coverage

The report distinguishes three states per target:

- **Covered (recorded)** — credited by the recording proxy (green).
- **Statically covered** — exercised behaviourally by the tests but the proxy
  _structurally cannot_ credit it (shown in a distinct colour). These come from a
  curated allowlist in `src/framework/static-coverage.ts`, keyed by
  `Interface.member#mode`. See [Coverage notes](#coverage-notes) for which members
  and why.
- **Uncovered** — neither.

The header shows two numbers: the **recorded** percentage (what the proxy actually
credited) and the **effective** percentage (recorded + statically covered).
Recorded coverage always wins, so listing a target in the static allowlist that
turns out to be recorded is harmless — it simply never shows as static. Coverage is
report-only; it never fails a run or the build.

The denominator comes from `src/generated/api-surface.json`, generated from
`libs/plugin-types/index.d.ts`. If the Plugin API types change, regenerate it:

```
pnpm --filter plugin-api-test-suite run gen:api
```

## Runtime details you need to know

- **Shape `type` values** returned at runtime: `Board` → `'board'`,
  `Rectangle` → `'rectangle'`, `Ellipse` → `'ellipse'`, plus `'text'`, `'path'`,
  `'group'`, `'image'`, `'svg-raw'`. (`createRectangle().type === 'rectangle'`.)
- `createText(str)` returns `Text | null` — guard the result (`if (text) { … }`).
- `width`/`height` are read-only; use `resize(w, h)`. `x`/`y` are writable.
- The plugin manifest already requests broad permissions (`content:*`,
  `library:*`, `user:read`, `comment:*`, `allow:downloads`, `allow:localstorage`),
  so most of the API is callable from tests without changes.
- The runner sets `throwValidationErrors = true` and `naturalChildOrdering = true`,
  so invalid API usage throws (surfacing as a red test) and `children` is always in
  z-index order.
- The runtime is SES-sandboxed: no Node APIs, no DOM, no extra npm deps inside
  tests. Stick to the Plugin API, `expect`, and plain JS.

## Coverage notes

The suite covers a large majority of the type surface. The remaining members are
uncovered or only _statically_ covered for the reasons below — **not** missing
tests. Note these notes can drift as the API is fixed: when in doubt, write the
test asserting the documented correct behaviour and run `test:ci` to see what
actually happens.

### Exercised behaviourally but not creditable by the recorder (statically covered)

Listed in `src/framework/static-coverage.ts`:

- **`ContextTypesUtils.*` and `ContextGeometryUtils.center`** — `penpot.utils.types`
  and `penpot.utils.geometry` are frozen (SES) data properties, so the recording
  proxy must return them raw and cannot wrap their members. Both are exercised
  behaviourally in `platform.test.ts`.
- **`ColorShapeInfo.shapesInfo`, `ColorShapeInfoEntry.*`** — `shapesColors()` has an
  unresolved return type in the generated surface (`type: null`), so the recorder
  hands the result back raw and can't attribute nested access. Exercised in
  `colors.test.ts`. (Alternatively, resolving the return type in
  `tools/gen-api-surface.ts` would make these genuinely recorded.)
- **`EventsMap.*`** — a type map, not a runtime object. `on`/`off` are credited on
  `Penpot`, never as `EventsMap` members. The deterministic events
  (`selectionchange`, `shapechange`) are exercised in `events.test.ts`.
- **`ShapeBase.fills`** — every concrete shape redeclares `fills`, so accesses are
  attributed to the concrete type (`Rectangle.fills`, …); the base-interface target
  is never the attribution.
- **`LibraryVariantComponent.*`** — the recorder types a component as
  `LibraryComponent` and can't narrow to `LibraryVariantComponent` via the
  `isVariant()` type-guard. The behaviour is exercised via `VariantContainer.variants`
  in `variants.test.ts`.

### Read-only at runtime

Members that have no setter in the runtime binding (`frontend/src/app/plugins/*.cljs`)
are now marked `readonly` in the Plugin API d.ts (`Font.*`, `FontVariant.*`,
`FontsContext.all`, `Image/Ellipse/SvgRaw.type`, `File.name/pages/revn`, `Page.root`,
`TokenTheme.activeSets`, `Variants.properties`, `ImageData.*`, and the board guide
value objects `GuideColumn/GuideRow/GuideSquare` and their params — `board.guides`
returns a formatted snapshot, so guides are reconfigured by reassigning the whole
array, not by mutating a returned guide), the `Point`/`Bounds` value objects, the
`Penpot.ui`/`Penpot.utils` subcontexts, and the derived `Boolean` path data
(`d`/`content`/`commands` are computed from the operands — a `Boolean` isn't editable
like a `Path`). They therefore have only a `(get)` target and need no runtime
assertion — the type system enforces the contract.

Members that **do** have a runtime setter stay writable, even when the setter
rejects some inputs (that's input validation, not read-only-ness): `Board.children`
(assigning a reordered array reorders the children), `Path.d/content/commands`
(editing the path), and `FileVersion.label` (relabels the version).

### Excluded from coverage

`tools/gen-api-surface.ts` drops two categories from the denominator so they never
count:

- **`@deprecated` interfaces and members** — the legacy `Image` shape interface
  (images live in a `Fill` via `fillImage`), `Color.refId`/`refFile`, and the
  `Boolean`/`Path` `toD()`/`content` path accessors.
- **Members removed by the public interface via `Omit`** — `Context` is the
  internal interface and the public `Penpot` is `Omit<Context, 'addListener' |
'removeListener'>` (those are superseded by `on`/`off`). The generator honors the
  `Omit`, so `Context.addListener`/`removeListener` aren't reachable surface and
  don't count.

### Red tests pinning confirmed API bugs

When a member is confirmed broken, add a test that asserts its **correct** behaviour
and comment it as blocked-by-bug; it stays red until the API is fixed and then turns
green (at which point drop the "API bug" framing). There are currently no such red
tests — e.g. the `fontFamilies` token `resolvedValue` bug (it used to leak the raw
tokenscript structure instead of `string[]`) has since been fixed.

### d.ts / runtime mismatches

`strokeStyle: 'none'` is listed in the d.ts but rejected at runtime ("Value not
valid"); `fills-strokes.test.ts` pins this with a `toThrow`.

### External state / not reachable headless

- **`ActiveUser.position/zoom`** — needs a second collaborator in the file.
- **`LibrarySummary.*`, `LibraryContext.connectLibrary`** — need a published shared
  library.
- **`FileVersion.restore`, `Penpot.closePlugin`, `Penpot.ui`, `Context.openViewer`** —
  tear down or navigate away from the running plugin/workspace.
- **`FileVersion.pin`** — only converts a _system_ autosave to a permanent version;
  a plugin can only create manual versions (`saveVersion`), so `pin()` always
  rejects.
- **`Context.addListener/removeListener`** — omitted from the `penpot` global
  (`Omit<Context, 'addListener' | 'removeListener'>`), so unreachable via `penpot`.
- **`EventsMap` events `pagechange/filechange/themechange/contentsave/finish`** —
  can't be triggered deterministically in the headless runner.

## Checklist before finishing

- [ ] Test file is `src/tests/<name>.test.ts` and uses `test(...)` + `expect`,
      ideally wrapped in a `describe('<Group>', …)`.
- [ ] All API calls go through `ctx.penpot`; shapes are appended to `ctx.board`.
- [ ] Created shapes don't leak (rely on the scratch board cleanup; don't touch the
      user's existing content).
- [ ] Lint/format/typecheck pass:
      `pnpm --filter plugin-api-test-suite run lint` and, from `plugins/`,
      `pnpm exec prettier --check "apps/plugin-api-test-suite/**/*.{ts,css,json}"`.
- [ ] If you relied on new API members, `gen:api` was re-run so coverage reflects
      them.

## Where things live (for deeper changes)

- `src/framework/registry.ts` — `test()`, `describe()`, `getTests()`, `setTests()` (reload).
- `src/framework/runner.ts` — runs tests, scratch board lifecycle, per-test state reset, coverage.
- `src/framework/coverage.ts` — the recording proxy + coverage computation.
- `src/framework/static-coverage.ts` — the statically-covered allowlist.
- `src/framework/expect.ts` — the assertion library.
- `src/framework/types.ts` — `TestContext`, `TestResult`, `CoverageReport`, etc.
- `tools/gen-api-surface.ts` — generates `src/generated/api-surface.json`.
- `src/plugin.ts` (sandbox), `src/ui.ts` (iframe), `src/model.ts` (messages).
- `src/ci/headless.ts` + `ci/run-ci.ts` — CI path.

Writing tests should only ever require touching `src/tests/`.
