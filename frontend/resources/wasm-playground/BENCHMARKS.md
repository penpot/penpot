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
pnpm run verify:renderer-benchmark-failures
pnpm run verify:renderer-benchmark-cli
pnpm run verify:renderer-playground --filter rects
```

This unscored smoke uses the already-built benchmark artifact and Playwright
request routing; it starts no server. It checks every workload and interaction
through `Full`. Optional screenshots support independent manual fixture checks.
They never enter scored runs, and smoke durations are not benchmark evidence.

The failure checks inject context loss, cancellation, stalled work, and crashes.
The CLI checks use the existing server and prepared build path, saving each
failure's JSON and log under a printed temporary directory. The playground check
loads the actual legacy pages and exercises pointer drag and wheel zoom. Run
these commands separately from scored runs.

The runner uses the manifest's default Cargo features and records their local
feature expansion. It rejects diagnostic defaults and disables the optional
function-name profiling switch for scored builds.

## Feature A/B comparisons

`run --features a,b` builds and runs one configuration with explicit Cargo
features and records it. `ab --features a,b` builds and runs every on/off
configuration of the requested features (2^N for N features, default cap 3,
`--max-features` 1..6), then compares the Boolean-lattice edges: pairs that
differ in exactly one feature. Build order follows a binary-reflected Gray
code so consecutive builds differ by one feature bit; this only minimizes
rebuild wall time. Build durations are provenance
(`metadata.build.durationMs`), never metrics, but non-adjacent edges are
measured further apart in wall time, so repeated whole runs confirm findings
that matter.

```sh
pnpm run benchmark:renderer run --features branch-b --filter rects/default/pan --output /tmp/renderer-one.json
pnpm run benchmark:renderer ab --features branch-b --filter rects/default/pan --output /tmp/renderer-ab-branch-b
pnpm run benchmark:renderer ab --features branch-b --dry-run
```

`ab` requires empty manifest defaults and aborts when a requested feature
overlaps the enabled defaults or names a `stats*`/`profile*` diagnostic;
`run --features` records defaults and enforces only the overlap abort.
`--dry-run` prints the plan and builds nothing. Configurations beyond 8, or an
empty `--filter`, require `--confirm`. Each configuration launches its own
browser; the printed plan states this cost. `--output` for `ab` is a directory
root that must not exist yet; `configs/<slug>.json`,
`comparisons/<from>__<to>.json`, and `matrix.json` land beneath it.

Run JSON is `schemaVersion` 2: `metadata.build.features` is the explicit
sorted set (the A/B identity axis), `defaultFeatures` the resolved manifest
defaults, plus the runner-controlled `env` map and the allowlisted
`ambientEnv` (`RUSTFLAGS`, `RUSTC_WRAPPER`, `CARGO_TARGET_DIR`, `CARGO_HOME`,
`CC`, `CFLAGS`, `CXXFLAGS`, `LDFLAGS`, `PENPOT_WASM_FUNCTION_NAMES`,
`CARGO_PROFILE_*`, `EMSDK*`). Comparison of the six build fields is always
diagnostic; the expected `features` mismatch on every edge is tagged, not
suppressed. An edge is marked `controlled: true` when the intended toggle is
the only mismatch: the comparison measures exactly what the matrix was built
for, and the diagnostic banner reads as single-run evidence, not as a
verdict. Edges with other mismatches (environment, seeds, manifest defaults
shifting mid-matrix) keep the strong diagnostic-only reading and print a
terminal warning. `branch-a` has no observable effect because the scenes never
call `set_modifiers`. Exit 0 means every edge has a comparison, never a
performance verdict.

## Current validation limits

The September 17 software-rendered pilot took 25 minutes 43 seconds excluding
the build at 3 warm-ups and 10 measured attempts. Eighteen cases completed all
attempts; all three shadow cases failed. A later one-attempt, zero-warm-up run
took 2 minutes 36 seconds; shadow pan and zoom still lost their WebGL contexts.
These runs used dirty source trees and are workflow evidence, not a comparison
of renderer changes. Shadow visual validation and default sampling remain open.
Keep the full workloads and provisional defaults until these limits are resolved.

Isolated September 17 probes reproduce the shadow pattern without changing
workloads or defaults. `verify.js --filter shadows/default/load` passes through
`Full` on SwiftShader; `--filter shadows/default/pan` and
`--filter shadows/default/zoom` fail with `WebGL context lost` in the warm
interaction loop that calls `_render_from_cache` per frame. Load success records
renderer submission only, not displayed pixels. The fixture keeps 1000 shapes
with two drop shadows and one inner shadow, seven FFI arguments per shadow,
style 0 for drop and 1 for inner, and a boolean hidden flag, matching
`render-wasm/src/wasm/shadows.rs`. No fixture defect, workload reduction, or
renderer change is claimed.

Sampling stays provisional at 3 warm-ups, 10 measured attempts, and 30s timeout.
The full pilot ran 240 attempts in 1543337 ms, about 6.4 s per attempt; the
one-attempt workflow run took 156466 ms for 21 attempts, about 7.4 s per
attempt. The 2-5 minute goal allows roughly 18-46 attempts in total, or one to
two per case across 21 cases, below the 10 valid attempts needed for median
intervals. Use zero-warm-up single attempts with `--filter` for workflow
checks; use the full provisional counts for intervals. Final calibration needs
a stable graphics environment with passing shadows.

The implementation roadmap, pilot evidence, and remaining calibration work live
in Serena memory `render-wasm/performance/core` and its numbered tickets. The
2-5 minute full-suite goal excludes build time and is a target, not a guarantee
on software-rendered systems.
