# Duplicate Export Job Prevention: AI unit test generation

## Feature Name

Duplicate Export Job Prevention, for the Penpot scalable exporter's shared
export queue (`exporter/src/app/jobs/queue.cljs`).

## Feature Description

The feature stops the same export job from going onto the queue more than
once, so two workers never process the same job and waste resources.

Requirements:

1. Each export job has a unique ID.
2. A new job can be added to the queue.
3. Adding a job with an existing ID does not create a duplicate.
4. Different jobs can be added normally.
5. The queue holds only one entry per job ID.
6. Invalid or null job IDs are rejected.

How it is built: `enqueue!` rejects any ID that is not a UUID. It then takes
a per-job marker in Redis with an atomic `SET NX`. If the marker already
exists, the job is waiting or a worker has it, so nothing is queued.

## Generated Test Cases

The AI (Claude Code, Opus 5.5) generated **13 unit tests**, with 31
assertions, in one file: `run-1/duplicate_job_test.generated.cljs`. The tests
use the project's existing `cljs.test` framework against a real Redis
(Valkey).

| Run | Passed | Failed |
|---|---|---|
| Before the feature was built | 3 | 10 |
| After the feature was built | 12 | 1 |

| # | Test | Requirement | Type |
|---|---|---|---|
| 1 | `each-new-job-gets-its-own-unique-id` | R1 | normal |
| 2 | `a-valid-new-job-is-added-to-the-queue` | R2 | normal |
| 3 | `enqueuing-the-same-job-twice-keeps-a-single-entry` | R3 | normal |
| 4 | `a-separate-request-reusing-an-existing-id-is-not-queued-again` | R3 | edge |
| 5 | `concurrent-enqueues-of-the-same-job-keep-a-single-entry` | R3 | edge |
| 6 | `a-job-already-claimed-by-a-worker-is-not-queued-again` | R3 | edge |
| 7 | `different-jobs-are-all-accepted-in-order` | R4 | normal |
| 8 | `queue-holds-one-entry-per-id-after-mixed-adds` | R5 | normal |
| 9 | `a-null-job-id-is-rejected` | R6 | invalid |
| 10 | `a-job-without-an-id-is-rejected` | R6 | invalid |
| 11 | `a-blank-job-id-is-rejected` | R6 | invalid |
| 12 | `a-non-uuid-job-id-is-rejected` | R6 | invalid |
| 13 | `a-rejected-id-leaves-existing-jobs-in-place` | R6 | edge |

## Accepted Test Cases

**11 accepted: #2–#7 and #9–#13.** Each one compiles, passes once the feature
exists, checks a real requirement and needed no changes. Most check the
actual contents of the Redis queue, not just a return value, so they test
behavior rather than wiring.

- **#3, #4, #5, #9–#13** failed before the feature was built and pass after
  it. They catch the missing feature, so they are the strongest tests.
- **#5** (five enqueues sent at the same time) and **#6** (a job a worker has
  already claimed) cover the cases where duplicates would really happen in a
  cluster.
- **#2 and #7** passed both before and after. They don't prove the feature
  works, but they guard normal queueing against breaking.
- **#6 and #12** rest on the AI's own assumptions: a claimed job counts as a
  duplicate, and a valid ID must be a UUID. Both match the design, but they
  go beyond the written requirements.

## Rejected Test Cases

**2 rejected: #1 and #8.**

- **#1** `each-new-job-gets-its-own-unique-id` passes, but it tests how job
  IDs are made (`uuid/next`), not the queue. It would pass with or without
  the feature, so it checks nothing about duplicate prevention.
- **#8** `queue-holds-one-entry-per-id-after-mixed-adds` has a bug in the
  test itself. It uses destructuring in promesa's `p/loop`, which doesn't
  support it, so the loop stops after the first job. Both the compiler
  (`undeclared Var &`) and the linter flag line 208. It fails before and
  after the feature, so in its current form it can't validate R5. It is the
  input for the ChatTester repair step.

## Key Observation

- **Good at coverage:** the AI covered every requirement and added edge cases
  I hadn't listed, such as concurrent enqueues and jobs already claimed by a
  worker. These are the cases most likely to produce duplicates in a real
  cluster.
- **Useful before the code existed:** written first, the tests acted as a
  specification. 9 of them failed for the right reason and passed once the
  feature was built.
- **Wrong about a library:** the one broken test came from the AI misusing
  a library macro (`p/loop`). It looked right but didn't behave as
  intended, and only the compiler warning and the run exposed it. Generated
  tests have to be run, not just read.
- **Weak tests can still pass:** #1 passes but checks the wrong thing, and
  the invalid-ID tests accept any error, of any type, as a rejection. A
  passing AI test is not always a meaningful one, so each assertion needs
  human review.
- **Assumptions need sign-off:** the AI stated its assumptions plainly, which
  made review easier. Some of those assumptions became requirements in the
  tests without anyone agreeing to them.
- **Threat to validity:** this run took place in a chat where the AI had
  already read the whole exporter, so it may have done better than a fresh
  chat would. Runs 2 and 3 should use new chats.

Records: `run-1/interaction.md` (prompt and AI response), `run-1/results.md`
(full results and evaluation), and the `run-1/*.log` files.
