# GPU memory harness

Measures how much GPU memory the render-wasm workspace uses during a long
edit session, in Chromium, Firefox and WebKit, and compares builds.

Not part of CI. Needs the compiled frontend and wasm bundle that
`scripts/e2e-server.js` serves, and a display: the projects run headed,
since headless Chromium falls back to SwiftShader.

## Run

From `frontend/`:

```sh
# one engine, labelled run
PERF_LABEL=develop npx playwright test --project gpu-memory-chromium

# after rebuilding with a change
PERF_LABEL=cache-limit npx playwright test --project gpu-memory-chromium

# compare and write playwright/perf/report.html
node playwright/perf/gpu-memory/report.mjs
```

Projects: `gpu-memory-chromium`, `gpu-memory-firefox`, `gpu-memory-webkit`.

| Variable | Default | Meaning |
|---|---|---|
| `PERF_LABEL` | `git describe --dirty` | Name of the build under test; results go to `results/<label>/` |
| `PERF_FIXTURE` | `render-wasm/get-file-shadows.json` | `get-file` fixture under `playwright/data/` |
| `PERF_REPEATS` | `3` | Runs per engine; repeat 0 is dropped as warm-up |
| `PERF_ITERATIONS` | `12` | Steps per phase |
| `PERF_DPR` | `2` | Device pixel ratio |
| `PERF_PURGE_PROBE` | off | `1` calls `free_gpu_resources` at the end and samples again |
| `PERF_PROC_MATCH` | `ms-playwright` | Substring of the browser binary path used to find its processes |

Report flags: `--baseline <label>` (default `develop`, else the oldest
label), `--out <file>`, `--keep-warmup`, `--json`.

## What a run does

Loads the fixture, then runs these phases, sampling after the renderer goes
quiet: `load`, `resize` (cycles four page sizes through CSS, so every engine
takes the same path), `zoom`, `pan`, `edit` (select all, nudge back and
forth), `idle`, and optionally `purge`.

## What is measured

- **WebGL** (`gpu-memory/webgl-tracker.js`, every engine): bytes WebGL was
  asked to store in textures, renderbuffers and buffers, and a count of every
  storage allocation. Drivers add their own copies on top.
- **OS** (`gpu-memory/os-sampler.js`, Linux only): RSS of the processes that
  hold a GPU device open, DRM memory from `/proc/<pid>/fdinfo`, dma-bufs
  shared by the browser, and `nvidia-smi` when present.

A difference counts only when medians differ by 5% or more and the run
ranges don't overlap. Anything else is reported as noise.
