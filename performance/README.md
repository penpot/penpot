# Penpot Performance Tests

k6-based load and performance test suite for the Penpot backend. Measures HTTP RPC latency, throughput, and error rates under synthetic user load.

## Prerequisites

- **k6** — Install from https://k6.io/docs/get-started/installation/
- **Running Penpot backend** — Local devenv (`http://localhost:6060`) or a remote instance

## Quick Start

```bash
# Smoke test — 1 VU, 1 iteration, demo mode
./run.sh smoke

# Full lifecycle with 10 VUs, 5 iterations each
./run.sh lifecycle -v 10 -n 5

# Use registration flow instead of demo profiles
./run.sh lifecycle -m register -v 5 -n 1

# Point to a remote backend
./run.sh lifecycle -u https://penpot.example.com

# Show all options
./run.sh help
```

## Test Scripts

### `scripts/lifecycle.js` — Full User Lifecycle

Simulates a realistic user journey from account creation through CRUD operations:

1. **Register** — Create a new user (demo profile or full registration)
2. **Login** — Authenticate and obtain session cookie
3. **Get Profile** — Fetch current user profile
4. **Get Teams** — List user teams
5. **Create Project** — Create a new project in the default team
6. **Create File** — Create a new design file in the project
7. **Get File** — Fetch the file with its data (pages, objects)
8. **Update File** — Add a rectangle shape (tests optimistic concurrency)
9. **Upload Image** — Upload a PNG to the file's media objects
10. **Delete File** — Remove the file
11. **Delete Project** — Remove the project
12. **Logout** — End the session

Each VU performs the full flow independently, creating and cleaning up its own artifacts.

## Configuration

Options for `run.sh lifecycle`:

| Flag | Env Variable | Default | Description |
|------|-------------|---------|-------------|
| `-u URL` | `PENPOT_BASE_URL` | `http://localhost:6060` | Penpot backend URL |
| `-v NUM` | — | `1` | Number of virtual users |
| `-n NUM` | — | `1` | Iterations per VU |
| `-m MODE` | `PENPOT_REGISTER_MODE` | `demo` | `demo` or `register` |
| `-k PATH` | `K6` | `k6` | Path to k6 binary |

### Register Modes

- **`demo`** (default): Uses the `create-demo-profile` RPC endpoint. Requires the `demo-users` feature flag to be enabled on the backend. Fastest for testing.
- **`register`**: Uses the full two-step registration flow (`prepare-register-profile` + `register-profile`). Works without any feature flags but is slower.

## Shared Client (`lib/penpot-client.js`)

The shared client module wraps the Penpot backend RPC API using plain JSON (not Transit). Key features:

- **JSON transport**: Uses `Content-Type: application/json` for POST bodies and `Accept: application/json` (or `_fmt=json` for GET) for responses.
- **Cookie-based auth**: k6 automatically manages session cookies per VU.
- **Session headers**: Generates `x-session-id` and `x-external-session-id` UUIDs per VU.
- **Tagged metrics**: Every request is tagged with `rpc_command` for k6 metric slicing.

## Results

Test results are written to `results/<timestamp>/` as JSON. k6 also prints a summary to stdout with percentile breakdowns per RPC command.

## Thresholds

The lifecycle script includes built-in thresholds that will cause k6 to exit with a non-zero code if exceeded:

- `http_req_duration p95 < 5000ms` (global)
- `http_req_failed < 1%` (global)
- Per-command thresholds for login, profile, project, file, and update operations

## Adding New Flows

To add a new test flow:

1. Create `scripts/<flow-name>.js`
2. Import the shared client: `import { createClient } from "../lib/penpot-client.js";`
3. Implement the flow using the client methods
4. Add a command in `run.sh`

## Architecture Notes

- The backend supports both Transit JSON and plain JSON. This test suite uses **plain JSON** for simplicity (no Transit encoder needed in k6).
- JSON request keys are in **kebab-case** (matching Clojure conventions). JSON response keys are in **camelCase** (backend's default JSON encoding).
- `update-file` sends the `id` parameter both in the query string and in the POST body, matching the frontend's behavior.
- The backend uses optimistic concurrency control (`revn`) for file updates. The test retries once on conflict.
