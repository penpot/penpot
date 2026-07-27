# Composable Penpot API Tests (Plugin)

A Penpot plugin that runs a suite of component behaviour tests against the live
Penpot document, driving the public Plugin API exactly as a user's actions would.

## What this is for

Penpot components have subtle behaviour — overrides, propagation from a main to
its copies, nesting, precedence between competing changes. This plugin exercises
that behaviour through the same Plugin API that real integrations use, so the
tests act as a check that the API surfaces the expected component semantics end to
end.

## Core principles

These are the ideas the suite is built around. They are intended to outlast any
particular code structure.

- **A test is a composition of operations over a situation.** A _situation_ is the
  state a test acts on (the relevant shapes of a configuration, plus a record of
  what has happened). An _operation_ is one step — an edit, a structural change, or
  an assertion. Tests are built by composing small operations, not by writing
  bespoke procedures, so behaviour is described declaratively and pieces are
  reused across tests.

- **Operations compose, and choice points expand into variants.** Operations can
  be sequenced, and a test can express a _choice_ (do this, or skip it; pick one of
  several). Before running, every choice is expanded into the full set of concrete
  variants — so a single compact test definition becomes many independent runs
  covering every combination. Each variant runs against a freshly built situation,
  so variants never interfere.

- **The real API, with real propagation.** Operations mutate the live document
  through the Plugin API; propagation happens for real, and assertions read the
  real resulting state. The suite does not simulate or model component behaviour —
  it observes it.

- **Foundation operations own what they expose.** A starting configuration is
  built by a foundation operation — the first step of a test — which names the
  participants a test refers to, so a test addresses parts of the configuration by
  role rather than by reaching into internals. How a configuration is grown
  (instantiated, nested) is offered by the foundation operation itself; what
  content it is built around is supplied by a pluggable content-creation strategy.

- **Results are addressed by stable identity.** Every test (every expanded variant)
  has a stable identity assigned once. The UI renders the tests, and each result
  streams back keyed by that identity, so what you select to run and what you see
  reported always refer to the same thing.

## Usage

### Build and run the plugin

The plugin lives in the plugins workspace (`plugins/apps/composable-test-suite`)
and runs like the other plugins there. From the `plugins/` directory (after a
workspace `pnpm install`):

```
pnpm run start:plugin:composable-test-suite
```

Alternatively, from this directory, self-contained (installs its own
dependencies, isolated from the surrounding workspace):

```
pnpm run bootstrap
```

Either way this builds the plugin and then keeps rebuilding on change while
serving it locally; a connected plugin panel reloads automatically on each
rebuild. The first build takes a little while before the server is ready.

Other scripts: `pnpm run build` (one-off build), `pnpm start` (watch + serve),
`pnpm run init` (build, then watch + serve), `pnpm run types:check` (type-check
only).

### Connect it in Penpot

In Penpot, open the plugin manager and add a plugin by URL, using:

```
http://localhost:4202/manifest.json
```

(4202 is the conventional dev port shared by the plugins in this workspace, so
only one of them can be served at a time.)

The plugin panel will open inside Penpot.

### Run the tests

The panel lists every case as a group, headed by the case's identifier and its
test count (e.g. `MainEditSyncs [4 tests]`), with passed/failed counts at the
right edge. From there you can:

- **Run all**, or select individual tests (or whole groups) and **run selected**;
  **clear selection** deselects everything in one step.
- Watch each test's status update live as it runs — pending, running, then passed
  or failed.
- **Fold open a group** to read the case's description — what is set up, what is
  varied, and what must hold — shown in its own box above the group's tests.
- **Fold open a test** to see the steps that were applied and, if it failed, the
  failure message. (Details appear once a test has been run.)

Because the tests create and modify shapes in the current document, run them in a
scratch file rather than one whose contents you care about.

## Remote control (for agents and scripts)

The panel can be driven programmatically as well as by hand. Every checkbox
carries a stable DOM id: a case's group checkbox has the case identifier (e.g.
`MainEditSyncs`), and each of its tests has the composite identifier with the
1-based index (e.g. `MainEditSyncs-2`).

Driving the panel from outside requires a browser-automation bridge such as
Playwright with access to the running Penpot session — available, for example, in
Penpot's agentic devenv setup. The plugin renders inside a
`<plugin-modal title="Composable Tests">` element in the Penpot workspace, which
hosts the panel in a cross-origin iframe. Top-page selectors therefore do not
reach the panel's elements; go through a frame-scoped locator:

```js
const frame = page.getByTitle("Composable Tests").locator("iframe").contentFrame();

// start from a clean slate: deselect everything
await frame.getByRole("button", { name: "Clear selection" }).click();

// select a whole case (its checkbox sits in the header — works while folded)
await frame.locator("#MainEditSyncs").click();

// select a single test: unfold its group first (click the header label),
// then click the test's checkbox
await frame.getByText("MainEditSyncs", { exact: true }).click();
await frame.locator("#MainEditSyncs-2").click();

// run what is selected
await frame.getByRole("button", { name: "Run selected" }).click();
```

State can be read back the same way — e.g. a checkbox's selection via
`isChecked()` (a group checkbox reports `indeterminate` for a partial selection),
or the per-group passed/failed counts from the header text.

### Reading logs

`console.log` statements anywhere in the plugin code — including test operations
and assertions, which run in the plugin sandbox — surface in the browser console
of the Penpot page, so a browser-automation bridge can read them (with
Playwright's MCP tools: `browser_console_messages`). The page console carries a
lot of unrelated traffic (Penpot itself, vite, other plugins), so prefix debug
logs with the case identifier, e.g. `[MainEditSyncs] …`, and filter for that.
Together with the panel state this closes the debug loop: add a log, run the
failing test by id, read the log.

### Auto-reload on code changes

While the dev server is running (`pnpm start` or `pnpm run bootstrap`), any code
change triggers a rebuild, and the live preview then reloads the plugin
automatically — sandbox included, so changed test code takes effect without any
manual reload step. Note that the reload resets the panel completely: all
checkboxes are cleared and previous results are gone, so re-select what you want
to run after changing code.

## Running in CI (headless, mocked backend)

The suite can run fully headless, without the panel and without a running
Penpot instance. From the `plugins/` directory:

```
pnpm --filter composable-test-suite run test:ci
```

This builds the in-sandbox entry (`src/ci/headless.ts`) as a single
self-executing bundle and hands it to the driver (`ci/run-ci.ts`), which
serves the prebuilt frontend bundle via the frontend e2e static server,
intercepts every backend RPC with Playwright fixtures (no backend, no login),
opens the mocked workspace file, injects the bundle directly into the plugin
sandbox, and streams each test's result from the page console — failing the
process if any test fails. The mocked backend is not a limitation here:
everything the suite asserts is frontend store logic executed in memory; the
backend's only role is persistence, which the mock answers with a canned
response.

Prerequisites: the frontend bundle must exist at `frontend/resources/public`
(the devenv watch build suffices; CI builds it via `frontend/scripts/build`),
and the Playwright browser must be installed
(`pnpm --filter composable-test-suite exec playwright install chromium`).

Options via environment variables:

- `TEST_FILTER` — run only tests whose composite identifier contains the
  given substring (case-insensitive), e.g. `TEST_FILTER=MainEditSyncs` for a
  whole case or `TEST_FILTER=MainEditSyncs-2` for a single variant.
- `CI_TIMEOUT_MS` — overall timeout waiting for results (default 600000).

## Adding a test

A new test is a composition of operations over a starting configuration, added to
the set of cases the suite runs. Give the case a meaningful CamelCase identifier
(e.g. `MainEditSyncs`) and a plain-terms description in three parts: the situation
setup (what is created), the actions and variations applied to it, and the
requirement that is asserted. Reuse existing operations and content-creation
strategies where they fit; introduce a new one only when a genuinely new kind of
step or configuration is needed. Express variation through the suite's choice
operations rather than by writing out each combination by hand.
