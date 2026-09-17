# Renderer benchmarks

Run from `frontend/` in the prepared Penpot development container, as the normal
`penpot` user. Dependencies, the matching Playwright Chromium installation, and
the static server must already exist. Start the server separately with
`node scripts/e2e-server.js`. The runner never installs packages or browsers,
starts services, or cleans Cargo targets.

Use the Cargo cache prepared for that user. In this container, noninteractive
shells inherit `CARGO_HOME=/opt/cargo`, while the populated cache is
`/home/penpot/.cargo`. For those shells, prefix the command with
`CARGO_HOME=/home/penpot/.cargo`. The runner preserves the supplied environment;
it does not move caches or download missing crates.

```sh
pnpm run benchmark:renderer run --output /tmp/baseline.json
pnpm run benchmark:renderer run --filter texts --seed 42 --output /tmp/texts.json
pnpm run benchmark:renderer run --headed --filter rects/default/zoom
pnpm run benchmark:renderer compare /tmp/baseline.json /tmp/candidate.json
pnpm run benchmark:renderer compare /tmp/baseline.json /tmp/candidate.json --diagnostic
pnpm run test:renderer-benchmarks
```

Each run builds the ordinary optimized frontend renderer once using
`PENPOT_WASM_PREPARED=1`, offline Cargo, and the existing build/copy path. Setup is
skipped, package-manager network fallback is disabled, and the emitted module is
named `render-wasm-benchmark`. Playground assets are refreshed before launch. A
per-run marker checks that the server points at this checkout. Concurrent builds
or benchmark commands against the same checkout are unsupported.

The default server URL is `http://localhost:3000`; override it with `--base-url`.
Default JSON output goes to a unique file under the system temporary directory.
Use `--warmups`, `--repetitions`, and `--timeout-ms` to override the provisional
defaults of 3, 10, and 30000. Counts are fixed, not adaptive; failures are never
silently replaced. A failed case stops its remaining attempts and records how
many were unattempted, then later independent cases can continue. A lost browser
ends the run and retains earlier results. Exit status reports execution validity,
not a performance regression.

## Workloads and state

Seven versioned scenes use a seeded generator: 1000 rectangles, stars, lines, or
shadowed rectangles; 100 text shapes; and fixed mask/clip scenes. Scene generation
and buffer construction happen before upload timing. Text uses the renderer's
embedded fallback font, not production font fetching. The JavaScript fixtures
share their implementation with the interactive playground pages; `?seed=42` is
repeatable, and numeric shape-count overrides remain available on those pages.

All cases run serially in one Chromium process with a 1920x1080 CSS viewport,
DPR 2, and product WebGL2 context options. Effective sizes, context attributes,
browser/platform identity, seeds, and workload parameters appear in JSON.
Software WebGL is allowed and warns; a GPU launch flag does not prove hardware
acceleration. Fresh pages do not clear browser-process, compilation, or driver
caches.

- **Fresh module/context:** each attempt gets a new page/context and module.
  Module initialization starts at the Emscripten factory and includes WASM
  fetching/instantiation; the module's JavaScript import precedes timing.
- **Context initialization:** canvas/context registration, native initialization,
  render/browser options, and viewport sizing.
- **Upload:** direct FFI upload, pending text layouts, background, initial view,
  and initial tile-index setup. This is not the frontend's serialization or
  batched asynchronous upload path.
- **First render:** request to `ViewportReady` and final `Full`, including rAF
  scheduling. An immediate `Full` supplies both boundaries.
- **Warm pan/zoom:** separate case-owned instances; restore the starting view and
  finish preparation rendering before each attempt. Caches evolve within each
  case. Restoring the view is not a cache reset.

Interactions use declared frame paths, cached rendering, a declared 100 ms
settling interval, and product finalization/continuation flags. The interval
models a settling case, not every possible editor gesture. Finalization time
starts before `_set_view_end`, and that call's duration also appears separately.
Last-input-to-`Full` additionally includes the delay. Raw render/cached calls,
timestamps, flags, frame types, and zero-duration slices remain available.

## Interpretation

These are renderer submission timings. They do not measure GPU completion,
displayed pixels, input delivery, whole-editor responsiveness, network behavior,
or visual correctness. WASM buffer size is context, not attributed CPU/GPU memory.

Reports retain means, medians, ranges, sample deviation, and unscaled median
absolute deviation. Nominal 95% bootstrap intervals require 10 valid attempts;
p95 requires 100 and p99 requires 500. Intervals are exploratory and conditional
on these runs: serial correlation, cache history, system load, and thermal drift
remain. Confirm important findings with repeated complete runs. No p-values or
automated regression thresholds are provided.

Offline comparison checks scenario/version, seeds, resolved workloads,
preparation, environment, and metric semantics. Source/build changes are expected.
`--diagnostic` lists incompatibilities and permits inspection without claiming
equivalence. Dirty worktrees warn and continue, recording only SHA and dirty
state, not diffs or artifact hashes.

## Separate fixture validation

```sh
pnpm run verify:renderer-benchmarks
pnpm run verify:renderer-benchmarks --screenshots /tmp/renderer-diagnostics
```

This unscored smoke uses the already-built benchmark artifact and Playwright
request routing; it starts no server. It checks every workload and interaction
through `Full`. Optional screenshots support independent manual fixture checks.
They never enter scored runs, and smoke durations are not benchmark evidence.

The implementation roadmap, pilot evidence, and remaining calibration work live
in Serena memory `render-wasm/performance/core` and its numbered tickets. The
2-5 minute full-suite goal excludes build time and is a target, not a guarantee
on software-rendered systems.
