# Semantic file projection (security analysis spike)

A pruned, model-friendly view of a Penpot file, built for background phishing
detection and for prototyping with System One decision models (Jev / Laya).
It is a **spike**, not a product feature yet.

## Where

- Implementation: `app.rpc.commands.semantic` (backend), command
  `::get-semantic-file`. Registered in `app.rpc/resolve-methods` (main API
  scan list), so it is reachable at `POST /api/main/methods/get-semantic-file`.
- It is **not** in `app.rpc.management`: management needs a shared key plus a
  session cookie, which is friction for scripts. Main accepts an access token.
- Node prototype that consumes it: `experiments/security-projection/`
  (`analyze.mjs`, `compare.mjs`, `evaluate.mjs`, `seed.mjs`, plus fixtures and
  tests).
- Pure entry point reusable by a future background worker: `file->projection`
  (`app.rpc.commands.semantic`), which only reads a file map.

## Contract

- Params: `[:map [:id ::sm/uuid]]`.
- Result: `{:projection-version int :file-id uuid :file-name string
  :pages [...] :stats {...}}`.
- `projection-version` (currently `1`) must be bumped whenever the projection
  shape changes, so stored analyses can be invalidated and recomputed.
- Per page: `:name`, `:frames`, and optional page-level `:text` / `:links` /
  `:navigations` for shapes not inside a top-level frame.
- Per frame: `:name`, `:text` (vector of strings), optional `:links`
  (`{:text :url}`), `:navigations` (`{:text :action :target}`), optional
  `:images` count.

## What it keeps and drops

- Keeps: page and frame names, visible text (tree order), `open-url`
  interactions as links, destination interactions (`navigate`/overlay) as
  navigations, image counts.
- Drops: geometry, fills/strokes/shadows, ids, layout, media, components,
  colors, typographies, tokens, libraries, page backgrounds/grids/guides.
- Component main instances (`:main-instance`) are dropped: library content,
  not design content.
- Empty pages and signal-less frames are dropped.

## How it loads the file

- `bfc/get-file cfg id :read-only? true` (still migrates in memory, does not
  persist), then `feat.fdata/realize cfg file` to resolve pointer-map and
  objects-map. `realize` is `[cfg file]` — mind the argument order.
- Wrapped in `db/run!` so the connection is reused.
- Permissions: `perms/get-file-read-permissions` +
  `files/check-read-permissions!` (raises `:not-found` on no access, to avoid
  leaking file existence). Metadata sets `::rpc/id-type :file`.

## Constraints that shaped the design

- A raw Penpot file is mostly noise; TypeSafe documents "large state full of
  irrelevant detail" as an accuracy failure mode, so pruning is the pattern,
  not an optimization.
- Jev/Laya state is **text only** (no images), so visual brand impersonation
  needs a later phase (screenshot + vision model).
- Jev/Laya do **not** treat state as hostile: a design can carry prompt
  injection. Hard signals (URLs, credential keywords) must be computed in
  code and stay authoritative; the model judges only the ambiguous part.
- Jev limits (verified 2026-10): 64k tokens per request total, 32k for state
  plus the longest question, 255 choice options, 2..10 score levels. `noul`
  answers return a single probability (`{:type "noul" :noul 0..1}`), with no
  separate `value`/`confidence`.
- OpenRouter exposes the same System One shape at `POST
  https://openrouter.ai/api/alpha/decisions` (`{model, state, questions}` →
  `{answers, model, provider, usage}`). It is **not** `/chat/completions`:
  decision models reject chat requests with a 400. Models seen there:
  `typesafe/jev-1.13`, `upstage/solar-decide[-flash]`, `liquid/d1`,
  `cloudflare/clef-flash`, `inception/mercury-decide`. The prototype uses this
  endpoint and has `--request` to dump the payload before spending a call.
- First labeled-set run (24 synthetic projections, `evaluate.mjs`): Jev, d1 and
  pplx-decider hit precision/recall 1.00; clef-flash and mercury-decide produce
  false positives on a benign design that carries prompt injection. Jev is the
  default pick (fast, cheap, resists injection). Re-run when the set changes.
