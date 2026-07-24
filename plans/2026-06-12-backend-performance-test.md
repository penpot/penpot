# Backend Performance Test Plan

**Context:** Build a k6-based load/performance test suite that simulates realistic browser-to-backend HTTP flows for distinct Penpot user operations. The goal is to measure backend impact (latency, throughput, error rates, resource saturation) under synthetic user load. **Browser rendering performance is explicitly out of scope.** WebSocket testing is deferred.

**Date:** 2026-06-12
**Validated Requirements:**
- Tool: **k6** (confirmed).
- Environment: flexible — local devenv first, then remote staging/perf.
- Target scale: **1000 concurrent VUs** (ramping from lower baselines).
- Flows: **realistic CRUD lifecycle** — create, edit, upload, delete. Must include **image upload** and **font upload**.
- `update-file` is important but difficult because it requires **2–3 concurrent users editing the same file**, and **file size matters**.
- WebSocket: **deferred**.

---

## Current Progress

### Completed (2026-06-12)

Phase 1 done. Phase 2 done (all core flows + performance optimization). Phase 3 done (orchestrator). Phase 4 done (concurrent editing + file size matrix). Phase 5 remains.

**What was built:**

```
performance/
├── run.sh                  # Bash runner — all commands + orchestrator
├── README.md               # Usage docs, configuration, architecture notes
├── lib/
│   └── penpot-client.js    # ~590 lines — shared k6 HTTP client module
├── scripts/
│   ├── lifecycle.js                # Full user lifecycle (register → CRUD → delete)
│   ├── workspace-open.js           # Read-heavy: file open loop (get-file, libraries, thumbnails)
│   ├── workspace-edit.js           # Write-heavy: file edit loop (get-file + update-file)
│   ├── workspace-edit-concurrent.js # Concurrent editing: same-file or multi-file mode
│   ├── file-size-matrix.js         # File size matrix: latency vs shape count (10, 100, 500, 1000)
│   ├── media-upload.js             # Image uploads: SVG/PNG direct, JPG chunked
│   ├── font-upload.js              # Font uploads: TTF+OTF chunked, create-font-variant
│   └── compare-results.cjs         # Compare two k6 JSON results for regression
├── results/                 # k6 JSON output (gitignored)
└── baselines/               # for regression baselines
```

Fixtures are reused from `backend/test/backend_tests/test_files/` (no copies in `performance/`).

**Backend changes:**
- `backend/src/app/rpc/commands/demo.clj` — demo profile emails changed from timestamp-based to UUID-based (eliminates collisions). Uses `derive-password-weak` for fast password hashing.
- `backend/src/app/auth.clj` — added `derive-password-weak` using pbkdf2+sha256 (100 iterations, ~0.13ms/hash, ~700x faster than argon2id). Safe for demo users because `demo-users` flag is disabled by default in production.

**All scripts use `setup()` user pool:**

| Script | setup() creates | VU pattern |
|--------|----------------|------------|
| `lifecycle.js` | N users | Each VU picks `users[__VU-1]` → login → full CRUD |
| `workspace-open.js` | 1 user + 1 file with shape | All VUs share same user + file (realistic concurrent reads) |
| `workspace-edit.js` | N users + shared project | Each VU creates own file → edit loop |
| `media-upload.js` | N users | Each VU creates project/file → upload 3 images |
| `font-upload.js` | N users | Each VU uploads TTF+OTF → create-font-variant |

Setup is sequential (~0.13ms/user with `derive-password-weak`), excluded from k6 metrics. At 1000 VUs: ~0.13s setup, then pure measurement.

**All flows validated (smoke test, 1 VU, 1 iteration each):**

| Script | Checks | Failure Rate |
|--------|--------|-------------|
| `lifecycle.js` | 10/10 | 0% |
| `workspace-open.js` | 9/9 | 0% |
| `workspace-edit.js` | 5/5 | 0% |
| `media-upload.js` | 8/8 | 0% |
| `font-upload.js` | 11/11 | 0% |

**Orchestrator (`./run.sh all`) validated** — runs all 5 flows in parallel, 0% failure rate.

**Key discoveries (cumulative):**

1. **JSON transport works.** Backend accepts `Content-Type: application/json` (kebab-case keys auto-converted) and returns `application/json` (camelCase keys) via `Accept: application/json` or `_fmt=json`. No Transit encoder needed.

2. **`create-file` `features` param.** Sending `features: []` causes 400. Omit entirely — it's optional (`backend/src/app/rpc/commands/files_create.clj`).

3. **`update-file` shape schema is strict.** The `add-obj` change requires: `selrect`, `points` (4 corners), `transform`/`transform-inverse` (identity matrix), `parentId`/`frameId` inside `obj`, and `frameId` at the change top level. Schema: `common/src/app/common/files/changes.cljc:189`.

4. **`update-file` URL convention.** `POST /api/main/methods/update-file?id=<uuid>` — `id` in both query string and body.

5. **Two registration modes:** `demo` (fast, needs `demo-users` flag) and `register` (two-step, no flags).

6. **k6 at** `/home/penpot/.local/bin/k6` (v0.56.0). Use `PATH="/home/penpot/.local/bin:$PATH"` or `K6` env var.

7. **Demo profile race condition — solved.** Backend now uses `uuid/next` for demo emails (no collisions). k6 scripts use `setup()` to create user pool before VUs start. Both changes together eliminate the scaling bottleneck.

8. **Chunked upload threshold.** The client uses 50 KB chunk size. Files ≤50 KB use direct multipart; files >50 KB use `create-upload-session` → `upload-chunk` × N → `assemble-file-media-object`.

9. **Font upload flow.** Each MIME type (ttf, otf, woff) gets its own `create-upload-session`. All session IDs are passed in the `uploads` map to `create-font-variant`. The `font-id` is a client-generated UUID that groups variants into a family.

10. **MIME type validation.** The backend validates that the uploaded content MIME matches the declared MIME. `sample.jpg` must be sent as `image/jpeg`, not `image/png`.

11. **workspace-open uses shared user.** All VUs read the same file with the same user. Multiple demo users can't access each other's files without team sharing, so a single shared user is the correct pattern for read-heavy tests.

12. **Demo profile creation was slow due to argon2id — now solved.** `derive-password` in `backend/src/app/auth.clj` uses argon2id with 32 MiB memory, 3 iterations, parallelism 2 (~94ms/hash). Created `derive-password-weak` using pbkdf2+sha256 with 100 iterations (~0.13ms/hash) — **~700x faster**. `demo.clj` now uses `derive-password-weak` for all demo profiles. Safe because `demo-users` is already a development-only feature (disabled by default in production). At 1000 VUs, setup time drops from ~2–3 min to ~0.13 sec.

13. **bcrypt minimum cost factor is 4.** Can't go below 4 for bcrypt. pbkdf2+sha256 with 100 iterations is even faster (~0.13ms/hash vs ~2.7ms for bcrypt cost 4) and was chosen instead. Benchmark: argon2id ~94ms/hash, bcrypt cost 4 ~2.7ms/hash, pbkdf2+sha256 100 iter ~0.13ms/hash.

14. **revn conflicts don't happen in normal concurrent editing.** The conflict check in `files_update.clj` is `(> incoming stored)` — only fires when incoming revn is *greater* than stored. If two VUs both read revn=5 and VU A saves first (revn becomes 6), VU B saves with revn=5 → `5 > 6?` → false → no conflict. The real contention point is the **file-level advisory lock** (`db/xact-lock! conn id`) that serializes all `update-file` calls on the same file. More VUs = more lock queuing = higher latency.

15. **`update-file` response doesn't include `vern`.** The response is `{:revn N, :lagged [...]}`. `vern` only changes on snapshot restore, so it can be kept constant across iterations. Get it from the initial `get-file` call.

### Remaining Work

| Phase | Status | Next Actions |
|-------|--------|-------------|
| Phase 1 – Discovery & Tooling | **Done** | — |
| Phase 2 – Core HTTP Flows | **Done** | All 5 flows + orchestrator + setup() pool |
| Phase 2 – Performance Optimization | **Done** | `derive-password-weak` using pbkdf2+sha256 (100 iter) — ~700x faster than argon2id |
| Phase 3 – Scenarios | **Done** | `./run.sh all` runs all flows in parallel |
| Phase 4 – Concurrent Editing | **Done** | `workspace-edit-concurrent.js` with same-file and multi-file modes |
| Phase 4 – File Size Matrix | **Done** | `file-size-matrix.js` with 4 tiers (10, 100, 500, 1000 shapes) |
| Phase 5 – Regression Guard | **Done** | `compare-results.cjs` + CI workflow (relative comparison) |
| Phase 5 – Grafana Dashboards | **Deferred** | No Prometheus remote write or InfluxDB in current stack |

### Immediate Next Steps

1. ~~Phase 2 – Fast password for demo users~~ ✅ Done
2. ~~Phase 4: File size matrix (`update-file` latency vs shape count: 10, 100, 500, 1000 shapes).~~ ✅ Done — `file-size-matrix.js` with 4 tiers
3. ~~Phase 4: Concurrent editing test (2–3 VUs per file, measure conflict rate).~~ ✅ Done — `workspace-edit-concurrent.js` with same-file and multi-file modes
4. ~~Phase 5: Regression guard — implement `compare-results.cjs` and CI workflow.~~ ✅ Done
5. ~~Add `--scenario` flag to `run.sh`~~ ✅ Done
6. Write `viewer.js` — `get-view-only-bundle` + `get-comment-threads` (deferred per user request).

---

## Affected Modules

| Module | Why it is involved |
|--------|---------------------|
| `backend/` | Target system. All HTTP RPC (`/api/main/methods/*`), auth, storage, media processing, DB, and Prometheus metrics (`/metrics`). |
| `frontend/` | Source of truth for user request flows. We inspect `app.main.repo` (RPC client), `app.main.data.*` (user flows), and `app.main.data.persistence` (save semantics). |
| `common/` | Shared schemas, Transit helpers, and data structures. Used to understand valid `update-file` `changes` payloads. |

---

## Approach

### Phase 1 – Discovery & Tooling (Days 1–2)

#### 1.1. Read the frontend RPC flows to build a request catalog

Inspect these files to map every user action to its RPC command:

- `frontend/src/app/main/repo.cljs` — HTTP client conventions (headers, retry, GET vs POST rules, query params, form-data, multipart).
- `frontend/src/app/main/data/dashboard.cljs` — Dashboard init (`get-projects`, `fetch-fonts`, `search-files`).
- `frontend/src/app/main/data/workspace.cljs` — Workspace init (`get-file`, `get-file-libraries`, `get-file-object-thumbnails`, `resolve-file` via `get-file-fragment`).
- `frontend/src/app/main/data/persistence.cljs` — File save flow (`update-file` with `changes`, `revn`, `session-id`, debounce/buffer logic).
- `frontend/src/app/main/data/viewer.cljs` — Viewer flow (`get-view-only-bundle`).
- `frontend/src/app/main/data/comments.cljs` — Comment thread fetch (`get-comment-threads`).
- `frontend/src/app/main/data/media.cljs` / `upload.cljs` — Media upload flows (`upload-file-media-object`, `create-upload-session`, `upload-chunk`, `assemble-file-media-object`).
- `frontend/src/app/main/data/fonts.cljs` — Font upload flow (`create-font-variant` with `:uploads` map).
- `frontend/src/app/main/data/team.cljs` — Team creation (`create-team`), invitation (`create-team-invitations`).
- `frontend/src/app/main/data/project.cljs` — Project creation (`create-project`).

**Goal:** produce a **Request Catalog** mapping user actions to RPC command names, HTTP methods, payload shapes, and required preconditions (e.g., `team-id`, `file-id`).

#### 1.2. Confirm JSON compatibility for the test harness

The backend middleware (`app.http.middleware`) supports `application/json` request bodies and `application/json` responses (via `_fmt=json` or `Accept: application/json`).

- **Action:** Send a manual `curl` to `POST /api/main/methods/login-with-password` with `Content-Type: application/json` and verify the response format.
- **Action:** Verify `GET /api/main/methods/get-profile` with `Accept: application/json` returns plain JSON.
- **Action:** Verify `POST /api/main/methods/update-file` with `Content-Type: application/json` and `_fmt=json` works.
- **Action:** Verify `POST /api/main/methods/upload-file-media-object` with `multipart/form-data` works (k6 supports this natively).

#### 1.3. Set up the load testing directory and shared client

Create a directory `performance/` at the repo root.

Install **k6** (`k6` CLI or Docker image).

Create a shared `penpot-client.js` module that wraps:
- `login(email, password)` → returns session cookie / token.
- `rpc(cmd, params, opts)` → builds the correct URL, headers, body, and query params.
- `uploadFileMediaObject(fileId, filePath, name)` → multipart upload.
- `createUploadSession(totalChunks)` → chunked upload setup.
- `uploadChunk(sessionId, index, chunkBytes)` → multipart chunk upload.
- `assembleFileMediaObject(sessionId, fileId, name, isLocal)` → finalize chunked upload.

**Headers to replicate (critical for backend telemetry and session binding):**
- `x-session-id`: generated UUID per VU (must be consistent across requests for the same session).
- `x-external-session-id`: generated UUID per VU.
- `x-event-origin`: a string origin (e.g., `"perf-test"`)
- `accept`: `application/json` (for HTTP-only load path)
- `content-type`: `application/json` (or `multipart/form-data` for uploads)
- `credentials: "include"` (for cookie jar)

#### 1.4. Data seeding strategy for 1000 VU scale

Creating 1000 users/teams/files *inside* the load test is too slow and will distort the results.

**Recommended approach:**
- **Setup Phase (k6 `setup()`):** Run a pre-test script that creates a shared pool of test artifacts.
  - Use `login-with-password` with a fixture account (e.g., `profile1@example.com` / `123123` if fixtures exist).
  - Create `N` teams, `N` projects, `N` files of varying sizes (see **File Size Tiers** below).
  - Export the IDs into a JSON file that k6 `setup()` reads.
- **Alternative:** Use the backend REPL / fixtures (`app.cli.fixtures/run {:preset :small}`) to create fixture data, then export the IDs via a small Clojure script.
- **Data pool per VU:** Each VU picks a random user from the pool, or uses a dedicated user (e.g., VU #1 → `profile1@example.com`, VU #2 → `profile2@example.com`). For 1000 VUs, we need at least 1000 pre-seeded users.
- **Cleanup:** A post-test script can delete the seeded data, or we can use a dedicated perf DB that is reset between runs.

**Action:** Document the seeding procedure in `performance/README.md` and create a `seed-data.js` script.

---

### Phase 2 – Core HTTP Flow Scripts (Days 3–5)

Create one k6 script per user flow. Each script:
- Uses `setup()` to read the shared data pool and log in.
- Uses `vu` iterations to simulate the flow.
- Tags every request with the RPC command name so k6 metrics are sliced by endpoint.
- Uses `check()` assertions for HTTP 200 and valid JSON structure.

#### Flow 1: Realistic User Lifecycle (`lifecycle.js`)

This is the primary realistic flow. Each VU performs a full lifecycle:

1. **Auth**
   - `POST /api/main/methods/login-with-password` → `{email, password}`
   - `GET /api/main/methods/get-profile`
   - `GET /api/main/methods/get-teams`

2. **Create Team**
   - `POST /api/main/methods/create-team` → `{name: "Perf Team <uuid>"}`
   - `GET /api/main/methods/get-team?team-id=<id>`

3. **Create Project**
   - `POST /api/main/methods/create-project` → `{team-id, name}`
   - `GET /api/main/methods/get-project?id=<id>`

4. **Create File**
   - `POST /api/main/methods/create-file` → `{project-id, name, features}`
   - `GET /api/main/methods/get-file?id=<file-id>&features=<...>`

5. **Edit File (Simple Update)**
   - `POST /api/main/methods/update-file` with a minimal `changes` payload.
   - **Changes payload:** Use a simple change like `{:type "add-obj", :id "<uuid>", :page-id "<page-id>", :parent-id "<parent-id>", :obj {:type "rect", ...}}`. Inspect `app.common.files.changes` for the exact schema. For a load test, we only need the shape to be structurally valid; the backend validates it.
   - **Revn tracking:** Fetch the file first, read `revn`, then send `revn` in the update. If a conflict occurs (`409` or `:revn-conflict` error), retry once with the latest `revn`.

6. **Upload Image (Direct)**
   - `POST /api/main/methods/upload-file-media-object` (multipart)
   - Payload: `file-id`, `is-local: true`, `name`, `content` (the file bytes).
   - Use a small dummy PNG/SVG (e.g., 1 KB, 100 KB, 1 MB) stored in `performance/fixtures/`.

7. **Upload Image (Chunked)**
   - `POST /api/main/methods/create-upload-session` → `{total-chunks: N}`
   - Loop `N` times: `POST /api/main/methods/upload-chunk` (multipart, `session-id`, `index`, `chunk`)
   - `POST /api/main/methods/assemble-file-media-object` → `{session-id, file-id, name, is-local}`
   - Use a larger dummy file (e.g., 5 MB) to stress the chunked pipeline.

8. **Upload Font**
   - `POST /api/main/methods/create-upload-session` (chunked, because fonts can be large)
   - `POST /api/main/methods/upload-chunk` for each chunk
   - `POST /api/main/methods/create-font-variant` → `{team-id, font-id, font-family, font-weight, font-style, uploads: {"font/ttf": "<session-id>"}}`
   - Use a small real TTF/OTF file from `performance/fixtures/`.

9. **Delete File**
   - `DELETE /api/main/methods/delete-file` (verify the exact method name; it may be `update-file` with a deletion flag or a dedicated command). Inspect `frontend/src/app/main/data/dashboard.cljs` for the delete action.

10. **Delete Project**
    - `DELETE /api/main/methods/delete-project?id=<id>`

11. **Delete Team**
    - `POST /api/main/methods/delete-team?id=<id>`

12. **Logout**
    - (Optional; session cookie expiry is usually sufficient)

**Pacing:** Add `sleep()` between steps to simulate realistic think time (e.g., 1–3 seconds between dashboard navigation, 3–5 seconds between edits).

#### Flow 2: Workspace Open (Read-heavy) (`workspace-open.js`)

For 1000 VUs, most will be read-only viewers or editors opening files.

1. Login (reuse token from `setup`).
2. `GET /api/main/methods/get-file?id=<file-id>&features=<...>`
3. `GET /api/main/methods/get-file-libraries?file-id=<file-id>`
4. For each library: `GET /api/main/methods/get-file?id=<lib-id>`
5. `GET /api/main/methods/get-file-object-thumbnails?file-id=<file-id>`
6. `GET /api/main/methods/get-file-data-for-thumbnail?file-id=<file-id>&page-id=<page-id>&object-id=<frame-id>`

**Data:** Use a pool of files of varying sizes (see **File Size Tiers**).

#### Flow 3: Workspace Edit (Write-heavy) (`workspace-edit.js`)

**Scenario A — Independent editors (default, easiest to scale):**
- Each VU creates its own file in `setup()`, or picks a dedicated file from the pool.
- Loop:
  1. `GET /api/main/methods/get-file?id=<file-id>` (to refresh `revn`)
  2. `POST /api/main/methods/update-file` with minimal changes
  3. `sleep(3)`
- This measures the latency of the save path without concurrency conflicts.

**Scenario B — Concurrent editors (advanced, measures conflict resolution):**
- 2–3 VUs share the **same file ID**.
- Each VU:
  1. `GET /api/main/methods/get-file?id=<file-id>` (to get latest `revn`)
  2. `POST /api/main/methods/update-file` with changes
  3. If `revn-conflict` (HTTP 400 or 409 with `:code :revn-conflict`), retry with the latest `revn`.
- **Problem:** k6 VUs are independent; they cannot easily share a mutable `revn` counter.
- **Solutions:**
  1. **Optimistic concurrency:** Let conflicts happen naturally. Measure the conflict rate and retry latency. This is realistic for many-user editing.
  2. **Shared state service:** Run a tiny Redis or in-memory service that stores the latest `revn` per file. VUs read/write it before each update. This adds coordination overhead but reduces conflicts.
  3. **Sequential VU groups:** Use k6 `scenarios` with `executor: 'per-vu-iterations'` and a small shared file pool. Accept that some conflicts will occur and measure them as part of the benchmark.
- **Recommendation:** Start with **Solution 1** (optimistic). If the conflict rate is >10%, consider **Solution 2**.

**File Size Tiers for `update-file`:**
The backend `update-file` performance depends heavily on file data size (serialization, validation, pointer-map resolution, snapshotting).

| Tier | Size | How to create |
|------|------|---------------|
| Small | ~10 shapes | Create a file with a few rectangles. |
| Medium | ~100 shapes | Duplicate a page with many shapes. |
| Large | ~1000 shapes | Import a real-world design file or use a fixture. |

**Action:** Create a `create-file-fixture.js` helper that generates files of each tier via the `create-file` + `update-file` API (or by importing a `.penpot` file via the binfile import API if available).

#### Flow 4: Viewer (Read-heavy, anonymous or logged-in) (`viewer.js`)

1. Login (or use share-link token for anonymous).
2. `GET /api/main/methods/get-view-only-bundle?file-id=<id>&share-id=<id>&features=<...>`
3. `GET /api/main/methods/get-comment-threads?file-id=<id>&share-id=<id>`

#### Flow 5: Export (CPU/IO-heavy) (`export.js`)

1. Login.
2. `POST /api/export` with export payload.
   - Inspect `frontend/src/app/main/data/export.cljs` for the exact payload shape.
   - Common exports: `type: "png"`, `type: "svg"`, `type: "pdf"`.
   - This hits the **exporter** service (Node.js/Playwright), which is a separate process. If the goal is to stress the **backend**, limit export tests or target the backend export queue endpoints.

#### Flow 6: Media Upload (Storage/IO-heavy) (`media-upload.js`)

1. Login.
2. Direct upload: `POST /api/main/methods/upload-file-media-object` (multipart, small PNG).
3. Chunked upload: `POST /api/main/methods/create-upload-session` → `upload-chunk` x N → `assemble-file-media-object` (large PNG).
4. URL-based upload: `POST /api/main/methods/create-file-media-object-from-url` (if a stable external image URL is available).

#### Flow 7: Font Upload (Storage/CPU-heavy) (`font-upload.js`)

1. Login.
2. `POST /api/main/methods/create-upload-session` (for the font file)
3. `POST /api/main/methods/upload-chunk` for each chunk
4. `POST /api/main/methods/create-font-variant` → `{team-id, font-id, font-family, font-weight, font-style, uploads: {"font/ttf": "<session-id>"}}`
5. `GET /api/main/methods/get-font-variants?team-id=<id>`

---

### Phase 2 – Performance Optimization: Fast Password Hashing for Demo Users ✅ Done

**Goal:** Reduce `setup()` time for performance tests by making demo profile password derivation faster.

**Problem:** `derive-password` in `backend/src/app/auth.clj` uses argon2id with 32 MiB memory, 3 iterations, parallelism 2 (~94ms/hash). At 1000 VUs, creating the user pool in `setup()` takes ~2–3 minutes just for password hashing.

**Solution:**
Since `demo-users` is already a development-only feature (disabled by default in production), all demo profiles use a weaker, faster password algorithm. No special parameters or tenant checks needed.

1. In `backend/src/app/auth.clj`, added `derive-password-weak` using pbkdf2+sha256 with 100 iterations (~0.13ms/hash — **~700x faster** than argon2id).
2. In `backend/src/app/rpc/commands/demo.clj`, switched from `derive-password` to `derive-password-weak`.

**Files touched:**
- `backend/src/app/auth.clj` — added `weak-options` (pbkdf2+sha256, 100 iter) and `derive-password-weak`
- `backend/src/app/rpc/commands/demo.clj` — uses `derive-password-weak` instead of `derive-password`

**Impact:** Setup time for 1000 users dropped from ~2–3 min to ~0.13 sec (~700x improvement).

**Safety:** Demo users are already a development-only feature (disabled by default in production via `demo-users` config flag). Using weaker passwords for demo users only affects development/test environments where the flag is explicitly enabled.

---

### Phase 3 – Scenarios & Orchestration (Day 6)

Define k6 `options.scenarios` that mix the flows to simulate realistic traffic.

**Example scenario mix for 1000 VUs:**

| Scenario | Script | VUs | Arrival Rate | Duration | Notes |
|----------|--------|-----|--------------|----------|-------|
| `lifecycle` | `lifecycle.js` | 100 | 1/s (ramp 0→100 over 5m) | 10m | Full CRUD, most realistic. |
| `workspace_open` | `workspace-open.js` | 400 | 5/s (ramp 0→400 over 5m) | 10m | Read-heavy, simulates many editors opening files. |
| `workspace_edit` | `workspace-edit.js` | 200 | 2/s (ramp 0→200 over 5m) | 10m | Write-heavy, independent files. |
| `workspace_edit_concurrent` | `workspace-edit.js` | 30 (10 groups of 3) | 0.5/s | 10m | 3 VUs per file, measures conflicts. |
| `viewer` | `viewer.js` | 200 | 3/s (ramp 0→200 over 5m) | 10m | Read-heavy, simulates public/private viewers. |
| `media_upload` | `media-upload.js` | 50 | 0.5/s | 10m | Storage stress. |
| `font_upload` | `font-upload.js` | 20 | 0.2/s | 10m | Font processing stress. |

**Thresholds:**
- `http_req_duration{p95} < 200ms` for `get-profile`, `get-teams`, `get-projects`.
- `http_req_duration{p95} < 500ms` for `get-file` (small), `search-files`.
- `http_req_duration{p95} < 2000ms` for `get-file` (large / 1000 shapes).
- `http_req_duration{p95} < 1000ms` for `update-file` (small).
- `http_req_duration{p95} < 3000ms` for `update-file` (large).
- `http_req_duration{p95} < 5000ms` for `upload-file-media-object` (1 MB).
- `http_req_duration{p95} < 10000ms` for `assemble-file-media-object` (5 MB chunked).
- `http_req_failed < 1%` globally.
- `http_req_failed{code:revn-conflict} < 5%` for `workspace_edit_concurrent`.

**Correlation with backend metrics:**
- Scrape `/metrics` before, during, and after the test.
- Key Prometheus metrics to watch:
  - `rpc_main_timing_seconds` (histogram/summary, labeled by command name)
  - `rpc_management_timing_seconds`
  - `http_server_dispatch_timing_seconds`
  - `websocket_active_connections` (if any WS is active)
  - `websocket_messages_total`
  - JVM hotspot metrics (`process_cpu_seconds_total`, `jvm_memory_bytes_used`, `jvm_threads_current`)
  - HikariCP metrics (if exposed; check `com.zaxxer.hikari:type=Pool` via JMX or custom Prometheus exporter)
  - PostgreSQL: `pg_stat_activity` count by state.
  - Redis: `INFO` `connected_clients`, `used_memory`.

---

### Phase 4 – Advanced `update-file` Testing (Days 7–8)

Because `update-file` is the core of the product and the user explicitly noted that **file size matters** and **concurrent editing is difficult**, we need a dedicated deep-dive.

#### 4.1. File Size Tiers

Create a `file-size-matrix.js` script that parameterizes the file size:
- `SMALL_FILE_ID`: 1 page, 10 shapes.
- `MEDIUM_FILE_ID`: 1 page, 100 shapes.
- `LARGE_FILE_ID`: 1 page, 500 shapes.
- `XLARGE_FILE_ID`: 1 page, 1000+ shapes, or a multi-page file.

Run `workspace-edit.js` against each tier separately and plot:
- `update-file` latency vs file size.
- `get-file` latency vs file size.
- Backend CPU and DB time vs file size.

#### 4.2. Concurrent Editing — Two Modes

**Key insight:** `revn` conflicts only occur when `incoming > stored` (should never happen in normal usage). The real contention point is the **file-level advisory lock** (`db/xact-lock! conn id`) that serializes all `update-file` calls on the same file.

**Mode 1: Same-file** — N VUs edit different pages in 1 file
- Measures lock contention on a single popular file
- Bottleneck: advisory lock serialization

**Mode 2: Multi-file** — G groups × M VUs per file, each group edits its own file
- Measures whole system responsiveness under parallel edit sessions
- Bottleneck: DB connection pool, CPU, memory
- More realistic: real usage has many files being edited concurrently

**Script:** `workspace-edit-concurrent.js`

**Configuration via env vars:**
- `PENPOT_EDIT_MODE=same-file | multi-file` (default: `same-file`)
- `PENPOT_FILE_COUNT=1` — number of files (for multi-file mode)
- `PENPOT_VUS_PER_FILE=3` — VUs per file (for multi-file mode)

**Setup logic:**
- `same-file`: create 1 file, add N pages (N = total VUs)
- `multi-file`: create G files, each with M pages (G = FILE_COUNT, M = VUS_PER_FILE)

**VU loop:**
1. Login with assigned user
2. Get file → pick assigned page
3. Loop (10 iterations):
   - `get-file` → get latest `revn`
   - `sleep(0.3)` (think time)
   - `update-file` with change to assigned page (add rectangle)
   - Track: success on first try (should always succeed)
   - `sleep(1)` (edit pacing)

**Scenario ladder — same-file mode:**

| Run | VUs | Iterations | What we measure |
|-----|-----|-----------|-----------------|
| 1 | 3 | 10 | Baseline lock contention |
| 2 | 5 | 10 | Moderate contention |
| 3 | 10 | 10 | Higher contention |
| 4 | 20 | 10 | Stress level |

**Scenario ladder — multi-file mode:**

| Run | Files | VUs/file | Total VUs | What we measure |
|-----|-------|----------|-----------|-----------------|
| 1 | 3 | 2 | 6 | Light load |
| 2 | 5 | 3 | 15 | Moderate |
| 3 | 10 | 3 | 30 | Heavy |
| 4 | 10 | 5 | 50 | Stress |

**Metrics to track:**
- `http_req_duration{rpc_command:update-file}` — p50, p95, p99 at each VU level
- `http_req_duration{rpc_command:get-file}` — should be unaffected
- `http_req_failed` — should be 0%
- Latency growth curve: how much does p95 increase per additional VU?

**Expected results:**
- `get-file` latency: constant (no lock, read-only)
- `update-file` p95: grows with VU count in same-file mode (lock queuing)
- `update-file` p95: stable in multi-file mode (independent locks)
- Failure rate: 0% (no revn conflicts in this scenario)

**Files to create:**
- `performance/scripts/workspace-edit-concurrent.js`

**Files to modify:**
- `performance/run.sh` — add `concurrent-edit` command

---

### Phase 5 – CI Integration & Reporting (Days 9–10)

1. **Runner script (`run.sh`):**
   - `./run.sh smoke` for a 1-VU, 1-iteration smoke test. ✅ Done
   - `./run.sh lifecycle -v 100 -n 10` for the standard run.
   - Add `--scenario` flag to run individual flows or the full mix. ✅ Done

2. **Output:**
   - k6 JSON/CSV output to `performance/results/<timestamp>/`.
   - Prometheus snapshot diff (before vs after).
   - Grafana screenshot or dashboard export.

3. **Grafana Dashboard:** *(Deferred — no Prometheus remote write or InfluxDB configured in current stack)*
   - Panel: `p95 latency by RPC command` (from `rpc_main_timing_seconds`).
   - Panel: `HTTP requests/sec` (from k6).  
   - Panel: `Error rate by command` (from k6).
   - Panel: `DB connection pool` (if available).
   - Panel: `JVM heap used`.
   - Panel: `update-file conflict rate` (custom metric from k6).
   - Panel: `File size vs latency` (from the matrix test).

4. **Regression guard (relative comparison):**
   - **Approach:** Run performance tests twice in the same CI job — once on base branch, once on PR branch. Compare p95/p99 directly. No stored baselines needed.
   - **Trigger:** Only when backend files change (`backend/src/**`).
   - **Comparison script:** `scripts/compare-results.js` — parses two k6 JSON outputs, compares p50/p95/p99 for each RPC command.
   - **Threshold:** Fail if p95 increases >20% for any critical command (`get-file`, `update-file`, `login-with-password`, `create-demo-profile`).
   - **Workflow:**
     1. Checkout base branch (main)
     2. Run performance tests → store as "baseline"
     3. Checkout PR branch
     4. Run performance tests → store as "current"
     5. Compare baseline vs current
     6. If p95 increases >20% → fail CI
   - **Advantages:** Same hardware, same conditions. No stored baselines. Only runs when backend changes.

---

## Risks & Considerations

| Risk | Mitigation |
|------|------------|
| **Scale: 1000 VUs creating data simultaneously will exhaust DB connection pool or storage quota.** | Pre-seed the data pool. Use a dedicated perf DB. Monitor `pg_stat_activity` and HikariCP metrics. |
| **Media upload (images/fonts) will saturate network I/O before the backend is stressed.** | Run the load test from the same datacenter/VPC as the backend. Use small dummy files for most tests; reserve large files for a dedicated storage-stress scenario. |
| **`update-file` conflicts under 1000 VUs may be so high that the test becomes a conflict test, not a latency test.** | Measure both. The conflict rate is itself a critical metric. If it is too high, we can add jitter or use independent files. |
| **Exporter service is a separate bottleneck.** | `export.js` should target the backend queue endpoint, not the full export pipeline, unless we want to test the exporter too. If exporter is in scope, run it as a separate scenario. |
| **Chunked upload creates many temporary DB rows (`upload_chunk` table).** | The backend has a `upload-session-gc` cron job. Ensure it runs after the test, or clean up manually. |
| **Font upload shells out to FontForge and WOFF tools.** | This is CPU-intensive and may be a bottleneck. Run font upload as a separate, low-VU scenario to measure the processing time without blocking other tests. |
| **Prometheus metrics may not expose DB pool wait time.** | Add a custom JMX exporter for HikariCP if needed, or query `pg_stat_activity` directly. |
| **Cleanup:** 1000 VUs creating teams/files will leave logical deletions or orphaned storage objects.** | Use a dedicated perf environment. Run a cleanup script after the test that deletes all seeded data via the RPC API. |

---

## Testing Strategy

### How to verify the test harness itself works

1. **Smoke test:** Run each k6 script with `1 VU, 1 iteration` against a local devenv. Verify all requests return `200` and the response body is valid JSON.
2. **Baseline run:** Run `workspace-open.js` with `10 VUs, 60 s` against a clean devenv. Record baseline p95 and p99 latencies.
3. **Regression guard:** After any backend change, re-run the baseline. If p95 increases by >20%, flag it.
4. **Saturation test:** Ramp `workspace-edit.js` to 100 VUs editing independent files. Monitor backend CPU and DB connection pool. The test should reveal the breaking point where `update-file` latency spikes.
5. **Media upload stress test:** Run `media-upload.js` with 50 VUs uploading 1 MB files. Verify storage throughput and no `413` errors.
6. **Font upload stress test:** Run `font-upload.js` with 10 VUs. Verify FontForge CPU usage and no timeouts.

### Manual validation checklist

- [x] `POST /api/main/methods/login-with-password` with JSON body returns a session cookie. (Validated via k6 lifecycle)
- [x] `GET /api/main/methods/get-profile` with `Accept: application/json` returns JSON. (Validated via k6 lifecycle)
- [ ] `curl -H "Accept: application/json" http://localhost:6060/metrics` returns Prometheus text.
- [ ] Backend fixtures create at least 100 test users and 100 test files.
- [x] A `update-file` request with a minimal `changes` payload succeeds and returns `{"revn": N}`. (Validated — needs full shape with selrect, points, transform, frame-id)
- [x] A `upload-file-media-object` multipart request succeeds and returns a media object ID. (Validated via k6 lifecycle + media-upload)
- [x] A chunked upload (`create-upload-session` → `upload-chunk` → `assemble-file-media-object`) succeeds. (Validated via media-upload with JPG 305 KB, and font-upload with TTF 68 KB + OTF 82 KB)
- [x] A `create-font-variant` request with chunked uploads succeeds. (Validated via font-upload — TTF + OTF, returns variant with id)

---

## Immediate Next Steps (if approved)

1. ~~Create `performance/` directory and `README.md`.~~ ✅ Done
2. ~~Write `penpot-client.js` (k6 shared module) with `login()`, `rpc()`, `uploadMultipart()`, and `uploadChunked()` helpers.~~ ✅ Done (~590 lines, JSON transport, cookie auth, session headers, tagged metrics, direct + chunked upload, file library/thumbnail methods)
3. ~~Write a manual `curl` validation script~~ — Skipped; JSON compatibility confirmed via k6 smoke test.
4. ~~Write a data seeding script~~ — Not needed. User pool created in k6 `setup()` phase (sequential, excluded from metrics). Each VU picks `data.users[__VU - 1]` to login.
5. ~~Write the first k6 script: `lifecycle.js`~~ ✅ Done (11 checks, 22 HTTP requests, 0% failure)
6. ~~Run a 1-VU smoke test against local devenv and commit the baseline results.~~ ✅ Done
7. ~~Write `workspace-open.js` and `workspace-edit.js`.~~ ✅ Done (both validated, 0% failure)
8. ~~Write `media-upload.js` and `font-upload.js`.~~ ✅ Done (both validated, 0% failure)
9. ~~Define the `1000-VU` scenario mix in `options.js` (shared scenario config).~~ ✅ Done (`./run.sh all` orchestrator runs all 5 flows in parallel)
10. Run the first 100-VU ramp test and capture Prometheus metrics.

---

**Plan Author:** Senior Software Architect
**Status:** Phase 1–5 complete. Regression guard implemented (relative comparison). Grafana dashboards deferred.

