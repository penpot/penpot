# CS4233 Penpot Project Log

Record of AI prompts, what the AI produced, and group decisions.
AI = Claude Opus 5.5 in Claude Code. Working branch: `kylebranch`.

## Goals

1. Scalable exporter: a shared job queue in Valkey, with several exporter
   workers taking jobs from it.
2. CI/CD pipeline for the changed exporter.
3. Document AI use and group decisions (this file).

## Key decisions

| # | Decision | By | Status |
|---|---|---|---|
| D1 | Build on Penpot's existing export-job code instead of a new exporter | AI | Approved |
| D2 | One exporter image; a role setting picks `api` (dispatcher), `worker`, or `all` (today's behaviour, default) | AI | Approved |
| D3 | Both export routes go through the queue | AI | Approved |
| D4 | Encrypt the user's login token before storing it in Valkey | AI | Approved |
| D5 | Crashed workers' jobs go back on the queue (heartbeat per worker) | AI | Approved |
| D6 | Per-user job limit stays per worker in v1 | AI | Approved |
| D7 | CI/CD is built by another group member, not the AI | Kyle | Approved |
| D8 | Kyle works on `kylebranch`; `develop` stays clean | Kyle | Approved |
| D9 | Keep this log short and general | Kyle | Approved |

## Sessions

### 1. Setup and code review (2026-09-28)

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

(Attached: Penpot's original architecture diagram and the group's proposed
scalable-exporter diagram.)

- AI read the exporter code and CI setup. Penpot already stores export job
  state in Valkey, but the queue lives in one process, so only one exporter
  can run safely.
- The fork can't run Penpot's CI, which needs Penpot's own servers.
- AI created this log.

### 2. Move work to `kylebranch` (2026-09-28)

> Here, before we go any further i made a new branch called kylebranch
> where I will be working at from now on. Revert the changes made to
> checkout and put it in kylebranch

- AI switched to `kylebranch`; the uncommitted log came with it.

### 3. Keep `develop` clean (2026-09-28)

> Can you make it to where develop is clean, with no documentation folder

- AI committed the log on `kylebranch`, so it no longer shows on `develop`.

### 4. Design (2026-09-28)

> Ok im ready, do you think you can implement what im wanting? Could you
> read the architecture diagrams?

> Give me a breakdown of what the changes will be when you can

- AI installed build tools (Clojure CLI, clj-kondo, Valkey) and wrote the
  design: an `api` exporter queues jobs, `worker` exporters run them.
- Found the frontend uses two export routes; both must use the queue.

### 5. Start implementation, shorten log (2026-09-30)

> Ok are you ready to start working on it? Also in the future I feel like
> there's too much slop on PROJECT_LOG.md, make it a ton shorter with just
> key details for the work process, and make it general, not like every
> implementation

- AI cut this log down to prompts, short notes and decisions.
- AI started building the scalable exporter.

### 6. Build without local tests (2026-09-30)

> Whats the command for Node 24

> I cant get it working, lets just go onto making it

- Node 24 wouldn't install on Kyle's Mac. The AI found pnpm 12 runs on
  Node 22, so it could still build and test locally.
- AI built the scalable exporter: `api` and `worker` roles, a shared queue
  in Valkey, worker heartbeats with crash recovery, and token encryption.
- AI added 16 tests (queue tests run against a real Valkey) and a CI/CD
  workflow, later removed (see session 8).
- Checked by running 1 api + 2 worker processes: 6 exports split 3/3.
- Renders need the WASM renderer and a browser, so those tests only run in CI.
/
### 7. CI/CD check (2026-09-30)

> You didnt work on the CI/CD right?

- AI confirmed the CI/CD workflow was written in session 6. It has not
  run on GitHub yet; the first push to `kylebranch` will run it.

### 8. Remove AI's CI/CD (2026-09-30)

> Someone else made the CI/CD pipeline, so get rid of it because I had
> someone else make it

- AI deleted its workflow file. The exporter changes stay.

### 9. Testing check (2026-09-30)

> Have you ran any tests to make sure what we wanted works?

- AI summarised the testing done in session 6 and what is still untested
  (real renders with a Penpot backend and browser).

### 10. Running Penpot (2026-09-30)

> So really what is needed last is to actually run Penpot

- Last step: run the full Penpot stack and export from the UI, with one
  `api` exporter and two `worker` exporters. This needs Docker, which is
  not on Kyle's Mac yet.

### 11. Ready to push? (2026-09-30)

> Are we good to push this? He committed the CI/CD pipeline to develop so
> should I push it anyway

- The teammate's pipeline only adds a workflow file on `develop`, so it
  doesn't clash with the exporter work. The exporter work had to be
  committed on `kylebranch` before pushing.

### 12. Commit (2026-09-30)

> Can you commit and push this branch to develop

- AI committed the exporter work and this log on `kylebranch`. Project
  rules say the AI never pushes, so Kyle pushes.

### 13. Hold off on the pull request (2026-09-30)

> Actually I dont wanna push it next, so for now I have a "Compare & pull
> request" how do I get rid of that agian

- `kylebranch` is on GitHub. GitHub's "Compare & pull request" banner is
  only a suggestion, so Kyle can ignore it; no pull request was opened.

### 14. Switching to `develop` blocked (2026-09-30)

(Kyle pasted a terminal error: `git checkout develop` refused because of
uncommitted changes to `cs4233-docs/PROJECT_LOG.md`.)

- AI committed the pending log entries on `kylebranch` so the switch
  works.

> Shorten it into like 2 sentences max

- AI cut each bullet to two sentences or fewer.

> Shorter and simpler

- AI cut the bullets to one short sentence each.

### 16. Bring `develop` into `kylebranch` (2026-10-05)

> There have been new changes to develop so I pulled the changes, so can
> you add those changes to my kylebranch?

- AI merged `develop` (the teammate's CI/CD workflow) into `kylebranch`.
  No conflicts.

### 17. Confirm which CI/CD is used (2026-10-05)

> You removed the CI/CD that you made a while ago right? Because the CI/CD
> my teammate added is the one we will be using

- AI confirmed its workflow was deleted and never committed; the
  teammate's `ci-cd-assignment.yml` is the only pipeline added.

### 18. Testing CI/CD before pushing (2026-10-05)

> Is there any way I can test the CI/CD before I push it?

- AI listed options: run the pipeline locally with `act` (needs Docker),
  check the file with `actionlint`, or push `kylebranch` (not `develop`)
  and start the pipeline by hand from GitHub's Actions tab.

> Shorten these response

- AI shortened its answer and will keep replies short.

### 19. Will the teammate's CI/CD work? (2026-10-05)

> Can you tell from the CI/CD my teammate made that if it will run well?
> Im sure you cant tell for sure but do you have at least an idea

- AI compared it with Penpot's own CI: same commands, all scripts exist.
  Likely to pass; main risks are slow runs and skipped queue tests.

> So should I be good to try it?

- AI confirmed `kylebranch` is ready to push and run from the Actions tab.

> Push it for me and ill do the github part

- The repo's AI rules forbid the AI from pushing, so Kyle runs `git push`.

### 20. Run the pipeline (2026-10-05)

> Now what

- Kyle pushed `kylebranch`. AI gave the steps to run the pipeline on it
  from GitHub's Actions tab.

(Kyle sent a screenshot of the GitHub Actions page.)

- AI pointed to the "CI/CD: Group Pipeline" workflow and noted that
  Penpot's `_DEVELOP` / `_STAGING` runs wait on Penpot's own servers.

### 21. First pipeline run (2026-10-05)

(Kyle sent a screenshot: "CI/CD: Group Pipeline" run #3 on `kylebranch`,
Success in 6m 24s.)

- All test jobs passed on the exporter work. Build and Deploy were
  skipped by design: they only run on a push or on `main`.

> So can I make a pull request

- AI said yes: push the latest log commits first, then open the pull
  request `kylebranch` → `develop`.
