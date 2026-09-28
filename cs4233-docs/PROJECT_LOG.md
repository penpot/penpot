# CS4233 Penpot Project Log

This file records the group's work on the Penpot architecture project: every
prompt sent to the AI, what the AI produced, and every decision or change the
group made. Claude (the AI) adds an entry each session. Group members can add
their own entries in the same format.

**Repository:** fork of `penpot/penpot` at `github.com/MooketsiNoko/penpot`
**Base branch:** `develop` (at commit `f9b8f1ba75` when this log started)
**Working branch:** `kylebranch` (Kyle's branch, created from `develop`)

## How to read this file

- **Decision log** lists each decision, who made it, and its status. The AI
  proposes; only the group approves. A decision stays `Proposed` until a
  group member confirms it.
- **Session log** lists sessions in order, newest last. Each entry holds the
  prompt (word for word), what the AI did, the files it created or changed,
  and open questions.
- **Attribution:** every entry marks who wrote what: `AI` (Claude
  Opus 5.5 in Claude Code), `Kyle`, or another group member.

---

## Project goals

1. **Scalable exporter.** Change Penpot's exporter so export jobs pass
   through a shared queue and several exporter workers can process them.
   The group's proposed diagram shows the frontend sending export requests
   to an "Export queue / dispatcher", which hands jobs to "Exporter Worker 1"
   and "Exporter Worker 2". Job state lives in Valkey (Redis-compatible);
   files go to asset storage.
2. **CI/CD pipeline.** Build, test and package the changed exporter
   automatically on every push and pull request.
3. **Documentation.** Record AI prompts, AI output, and group decisions
   (this file).

---

## Baseline: how the exporter works today

Written by: AI (session 1), from reading the code on `develop`.

Upstream Penpot added export jobs on 2026-08-31 (commit `66b4a99ac3`,
PR #11296). So part of the proposed design already exists:

| Piece in the proposed diagram | Exists today? | Where |
|---|---|---|
| Job state stored in Valkey/Redis | Yes | `exporter/src/app/jobs/store.cljs` stores each job as a Redis hash with a TTL |
| Progress updates to users | Yes | `exporter/src/app/jobs.cljs` publishes `:export-update` messages on Redis pub/sub; the backend relays them over websocket |
| Cancel a job from any instance | Yes | `store/request-cancel!` broadcasts on a Redis topic; the instance running the job acts on it |
| Export queue | **Only in memory** | `exporter/src/app/jobs/scheduler.cljs` keeps the queue in an atom inside one Node process |
| Dispatcher handing jobs to separate workers | **No** | The instance that receives the HTTP request always runs the job |
| Several exporter instances at once | **Unsafe** | See below |
| Asset storage | Yes | Finished files upload to the backend (`/api/management/methods/upload-tempfile`), so workers need no shared disk |

### What stops the exporter from scaling out today

1. **The queue lives in one process.** `scheduler/submit!` stores
   `{:job :resolve :reject}` in a local atom. Another exporter cannot see or
   take that work.
2. **The job's work is a closure, not data.** `handlers/export.cljs` passes
   `run-fn`, a function that captures the auth token and prepared exports.
   A function cannot be written to Redis, so no other process can run it.
3. **Startup cleanup cancels other instances' jobs.** `jobs/clean-abandoned!`
   marks every unfinished job in Redis as cancelled when an exporter starts.
   The code says so itself: *"with more than one exporter behind a load
   balancer this would also cancel a sibling's running jobs. Single-instance
   deployments only."*
4. **Limits apply per process.** `:exporter-max-concurrent-jobs` (4) and
   `:exporter-max-jobs-per-profile` (2) count only the local process, so two
   instances would allow a user twice the intended jobs.

### CI today

- `.github/workflows/tests-exporter.yml` runs format check, lint and tests
  for the exporter on pull requests and pushes to `develop`/`staging`.
- Every test workflow uses `runs-on: penpot-extended-runner`. That is a
  self-hosted runner that belongs to the Penpot organisation. **Our fork does
  not have it, so these jobs will wait forever and never run on our fork.**
  Our pipeline must use GitHub-hosted runners (`ubuntu-latest`).
- Release images come from `docker/images/Dockerfile.exporter` via
  `docker/images/build.sh`, pushed to Docker Hub as `penpotapp/exporter`.

---

## Proposed plan

Written by: AI (session 1). Status: **Proposed, awaiting group approval.**

### Part A: scalable exporter

Keep one Docker image. Add a role setting so the same code can run as the
dispatcher, as a worker, or as both (today's behaviour, and the default).

| Step | Change | Main files |
|---|---|---|
| A1 | Make the job payload plain data: `{:id :cmd :params :auth-token}`. The worker rebuilds the export work from it instead of receiving a closure. | `handlers/export.cljs`, `jobs.cljs` |
| A2 | Replace the in-memory queue with a Redis list. The dispatcher pushes job IDs; workers take them with `BLMOVE` into a per-worker "processing" list, so a crash does not lose the job. | `jobs/scheduler.cljs`, `redis.cljs` |
| A3 | Add `PENPOT_EXPORTER_ROLE` = `all` (default) / `api` / `worker`. `api` serves HTTP and queues jobs; `worker` only pulls and renders. | `config.cljs`, `core.cljs` |
| A4 | Give each instance an ID and a heartbeat key with a short TTL. Record the owner on each job. Startup cleanup then reclaims only jobs whose owner's heartbeat has expired. This fixes problem 3. | `jobs.cljs`, `jobs/store.cljs` |
| A5 | Move the per-profile limit into Redis (a counter per profile) so it holds across all workers. | `jobs/scheduler.cljs` |
| A6 | Update `docker-compose.yaml` to run one `api` exporter and N `worker` exporters (`deploy.replicas`). | `docker/images/docker-compose.yaml` |
| A7 | Tests for the queue, the heartbeat reclaim, and the role switch; a demo that runs several exports at once against 1 worker and then 3. | `exporter/test/` |

**Risk to discuss:** step A1 stores the user's auth token in Redis until the
job ends. Options: (a) accept it, with the job TTL as the limit; (b) encrypt
it with the exporter key; (c) ask the backend for a short-lived token only
for the export.

### Part B: CI/CD

| Step | Change |
|---|---|
| B1 | New workflow `ci-exporter.yml` for our fork, on `ubuntu-latest` with the `penpotapp/devenv` container: format check, lint, unit tests for `exporter` and `common` (the exporter depends on `common`). |
| B2 | Add a Redis/Valkey service container so the queue tests run against a real server. |
| B3 | CD: on merge to `develop`, build the exporter image and push it to GitHub Container Registry (`ghcr.io/<owner>/penpot-exporter:<sha>` and `:develop`). |
| B4 | Optional: a smoke test in CI that starts compose with 1 api + 2 workers and checks that both workers take jobs. |
| B5 | Turn off or limit the upstream workflows that need `penpot-extended-runner`, so the fork's Actions tab does not fill with stuck jobs. |

---

## Decision log

| # | Date | Decision | Made by | Status | Reason |
|---|---|---|---|---|---|
| D1 | 2026-09-28 | Keep all project notes in `cs4233-docs/PROJECT_LOG.md` inside the repo | AI | Proposed | Lives next to the code, versioned with the changes it describes |
| D2 | 2026-09-28 | Build on the existing `app.jobs` code rather than write a new exporter | AI | Proposed | Upstream already stores job state in Redis and handles cancel across instances; only the queue and ownership are missing |
| D3 | 2026-09-28 | One image, role chosen by `PENPOT_EXPORTER_ROLE`, default `all` | AI | Proposed | Existing single-instance setups keep working unchanged |
| D4 | 2026-09-28 | CI runs on `ubuntu-latest`, not `penpot-extended-runner` | AI | Proposed | The fork has no access to Penpot's self-hosted runners |
| D5 | 2026-09-28 | Publish images to GHCR instead of Docker Hub | AI | Proposed | Free for public repos, uses the built-in `GITHUB_TOKEN`, no extra secrets |
| D6 | 2026-09-28 | Kyle's work happens on branch `kylebranch`, not `develop` | Kyle | Approved | Keeps `develop` clean while Kyle works |

---

## Session log

### Session 1 (2026-09-28): project setup and code review

**Tool:** Claude Code, model Claude Opus 5.5
**Person:** Kyle

**Prompt (word for word):**

> We are working on an open source repository called Penpot. Our goal is to
> restructure and build off their architecture by improving their
> architecture. I sent 2 architecture diagrams, the original one and the
> proposed scalablility improvements. The main difference for the improved
> one is implementing a scalable exporter.
>
> Another part of this is also implementing a CI/CD pipeline for this
> project, so that is something that will also need to be taken into
> account.
>
> Lastly, for this project everything needs to be documented (AI prompts,
> what AI generated, and any decisions or changes your group made). I will
> have seperate documentation on my own, but I also want you to document
> everything. Not sure how you want to document what I've done, but maybe
> make a .md or a file to where you can write down all the documentation
> and I can use it later.

**Attached:** two images.
1. Penpot's original C4 container diagram: User → Frontend app (with Web
   worker) → Backend app (Clojure/JVM) and Exporter (ClojureScript/Node)
   → Headless browser; Backend uses PostgreSQL and Redis pub/sub.
2. The group's proposed diagram, "Penpot — Proposed Scalability
   Improvement": Frontend → "PROPOSED: Scalable Exporter" (Export queue /
   dispatcher → Exporter Worker 1 and 2), with job state in Valkey and
   files in asset storage.

**What the AI did:**
- Read the project's AI guidance (`AGENTS.md`, `.serena/memories/`
  `critical-info`, `exporter/core`, `prod-infra/core`).
- Read the exporter source: `core`, `config`, `redis`, `jobs`,
  `jobs/scheduler`, `jobs/store`, `jobs/utils`, `handlers/export`,
  `handlers/jobs`, `handlers/resources`.
- Read the CI workflows in `.github/workflows/` and the Docker files in
  `docker/images/`.
- Found that upstream already stores export job state in Redis, and that
  the queue, job ownership and limits are still tied to one process (see
  *Baseline*).
- Found that the fork cannot run upstream CI, which needs Penpot's
  self-hosted runners.

**What the AI generated:**
- `cs4233-docs/PROJECT_LOG.md` (this file): baseline analysis, proposed
  plan, decision log, session log.
- No code changed.

**Open questions for the group:**
1. Approve or change decisions D1 to D5.
2. Which option for storing the auth token (see *Risk to discuss*)?
3. Does the group want the full plan (A1 to A7, B1 to B5) or a smaller
   scope for the course deadline?
4. Who owns each part (exporter work vs CI/CD)?

### Session 2 (2026-09-28): move work to `kylebranch`

**Tool:** Claude Code, model Claude Opus 5.5
**Person:** Kyle

**Prompt (word for word):**

> Here, before we go any further i made a new branch called kylebranch
> where I will be working at from now on. Revert the changes made to
> checkout and put it in kylebranch

**What the AI did:**
- Checked `git status`: the only change was the untracked folder
  `cs4233-docs/`. Nothing had been committed to `develop`.
- `kylebranch` pointed to the same commit as `develop` (`f9b8f1ba75`).
- Ran `git checkout kylebranch`. Git brings untracked files along, so
  the folder moved to `kylebranch` and `develop` has no changes.

**What the AI generated:**
- `cs4233-docs/PROJECT_LOG.md`: added the working branch and this entry.

**Group decisions or changes made after this session:**
- Kyle works on `kylebranch` from now on (decision D6).

---

### Session 3 (2026-09-28): keep `develop` free of the docs folder

**Tool:** Claude Code, model Claude Opus 5.5
**Person:** Kyle

**Prompt (word for word):**

> Can you make it to where develop is clean, with no documentation folder

**What the AI did:**
- Explained the cause: Git ignores untracked files when switching
  branches, so `cs4233-docs/` showed up on every branch, `develop` too.
- Committed `cs4233-docs/PROJECT_LOG.md` on `kylebranch`. Now Git
  removes the folder when you check out `develop` and restores it on
  `kylebranch`.
- Checked out `develop` to confirm the folder is gone and the tree is
  clean, then returned to `kylebranch`.

**What the AI generated:**
- `cs4233-docs/PROJECT_LOG.md`: this entry.
- One commit on `kylebranch` adding the log. Not pushed; Kyle pushes.

---

<!-- Template for new sessions: copy this block.

### Session N (YYYY-MM-DD): short title

**Tool:** Claude Code, model ...
**Person:** ...

**Prompt (word for word):**

> ...

**What the AI did:**
- ...

**What the AI generated:**
- `path/to/file`: what changed

**Group decisions or changes made after this session:**
- ...

**Open questions:**
- ...
-->
