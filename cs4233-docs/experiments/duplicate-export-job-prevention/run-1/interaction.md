# Run 1: AI interaction record

- Feature: Duplicate Export Job Prevention
- AI tool: Claude Code (model Claude Opus 5.5, `claude-opus-5-5`), VS Code extension
- Date: 2026-11-12
- Branch: `duplicate_exportJob_prevention` (no feature code yet; `enqueue!`
  has no duplicate check and no id check)
- Generated file: `duplicate_job_test.generated.cljs`, saved unchanged
  (SHA-1 `50fc0851223361b2709e72db4dba270712aaa755`)

## Threat to validity

The procedure asks for a new chat for each run. Run 1 happened in a chat
where the AI had already read and reviewed the whole exporter, so it knew
more context than the prompt gives. Runs 2 and 3 should use fresh chats.

## Feature (S1)

Duplicate export job prevention for the Penpot scalable exporter. It stops
the same export job from going onto the queue more than once, so several
workers don't process the same job and waste resources.

Requirements:

1. Each export job has a unique ID.
2. A new job can be added to the queue.
3. Adding a job with an existing ID does not create a duplicate.
4. Different jobs can be added normally.
5. Invalid or null job IDs are rejected.

## Prompt (S1, sent as written)

> You are a senior software engineer experienced in unit testing. I am
> working on duplicate export job prevention for the Penpot scalable
> exporter.
>
> The purpose of this feature is to make sure the same export job is not
> added to the queue more than once.
>
> First, explain your understanding of the feature's intended behavior
> before generating tests.
>
> Using the provided source code and method signatures, generate unit tests
> that verify:
>
> A valid new job is added successfully.
>
> Adding the same job ID twice does not create a duplicate.
>
> Different job IDs are accepted.
>
> The queue contains only one entry for each job ID.
>
> Invalid or null job IDs are rejected.
>
> Use the existing testing framework and do not invent methods or classes
> that are not provided.
>
> Include normal cases, edge cases, and invalid inputs. Give each test a
> clear name and explain which requirement it checks.
>
> Return one complete unit test file and identify any assumptions about the
> implementation.

## Code context used

Namespace `app.jobs.queue` (`exporter/src/app/jobs/queue.cljs`):

```clojure
(defn queue-key [])
(defn processing-key [instance-id])
(defn payload-key [job-id])
(defn depth [])                        ; promise of list length
(defn enqueue! [{:keys [id] :as job} payload])  ; payload {:cmd :params :auth-token}
(defn payload [job-id])                ; promise of payload, token opened, or nil
(defn claim! [timeout])                ; promise of claimed id or nil
(defn ack! [job-id])
(defn remove-queued! [job-id])
```

Also used: `app.jobs/create-queued!` (builds and stores a job with a fresh
UUID `:id`), `app.redis/lrange`, `app.redis/del!`, `app.instance/id`,
`app.instance/registry-key`. Test framework: `cljs.test` with promesa,
following `exporter/test/exporter_tests/queue_test.cljs` (real redis through
`PENPOT_REDIS_URI`).

## AI response: understanding of the feature

`app.jobs.queue/enqueue!` stores a job's payload and pushes its id onto the
shared redis list (`queue-key`). Workers claim ids from that list with
`claim!`. With the feature in place:

- `enqueue!` takes a job map whose `:id` is the job's unique id (a UUID from
  `jobs/create-queued!`).
- A new id goes on the list exactly once.
- Pushing an id that is already queued, or already claimed by a worker,
  leaves the list unchanged, so no second worker gets it.
- Different ids are queued normally, in arrival order.
- A nil, missing, blank or non-UUID id is rejected (the promise rejects)
  and the list is not touched.

## AI response: tests and the requirement each one checks

| Test | Req | Kind |
|---|---|---|
| `each-new-job-gets-its-own-unique-id` | R1 | normal |
| `a-valid-new-job-is-added-to-the-queue` | R2 | normal |
| `enqueuing-the-same-job-twice-keeps-a-single-entry` | R3 | normal |
| `a-separate-request-reusing-an-existing-id-is-not-queued-again` | R3 | edge |
| `concurrent-enqueues-of-the-same-job-keep-a-single-entry` | R3 | edge |
| `a-job-already-claimed-by-a-worker-is-not-queued-again` | R3 | edge |
| `different-jobs-are-all-accepted-in-order` | R4 | normal |
| `queue-holds-one-entry-per-id-after-mixed-adds` | R5 | normal |
| `a-null-job-id-is-rejected` | R6 | invalid |
| `a-job-without-an-id-is-rejected` | R6 | invalid |
| `a-blank-job-id-is-rejected` | R6 | invalid |
| `a-non-uuid-job-id-is-rejected` | R6 | invalid |
| `a-rejected-id-leaves-existing-jobs-in-place` | R6 | edge |

(R5 here is the prompt's "queue contains only one entry for each job ID";
the S1 requirement "invalid or null IDs are rejected" is R6.)

## AI response: assumptions about the implementation

1. Duplicate prevention lives in `queue/enqueue!`; no new function is added.
2. A duplicate enqueue may either resolve or reject. The tests only check
   queue state, not which of the two happens.
3. "Already in the queue" also covers a job a worker has claimed (in its
   processing list). Otherwise a second worker could still get it.
4. A valid id is a UUID (as `jobs/create-queued!` makes). Nil, missing,
   blank and non-UUID ids make `enqueue!` reject; no specific error code is
   assumed.
5. Rejection happens before anything is written, so the queue stays as it
   was.
6. Concurrent duplicate enqueues are handled atomically on the redis side.
7. Open question, not tested: whether a job that was removed or finished
   can be queued again with the same id.
