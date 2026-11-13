# Run 1: execution results and evaluation

Feature: Duplicate Export Job Prevention. Tests:
`duplicate_job_test.generated.cljs` (run unchanged as
`exporter/test/exporter_tests/duplicate_job_test.cljs`).

## Environment

- `penpotapp/devenv:latest` (arm64) in Docker, the image the group CI uses,
  with `valkey/valkey:8.1` on a private Docker network.
- `PENPOT_REDIS_URI=redis://dupjob-valkey/0`,
  `PENPOT_SECRET_KEY=test-secret-key`.
- Command: `pnpm run build:test && node target/tests/test.js --focus
  exporter-tests.duplicate-job-test --log-level warn`
- Feature code: **not implemented** at the time of the run. `enqueue!` in
  `exporter/src/app/jobs/queue.cljs` always RPUSHes and checks no id.

Changes to the harness, not to the generated test:

1. Added `exporter-tests.duplicate-job-test` to `runner.cljs` (required and
   listed in `test-namespaces`).
2. `render-wasm/build export` fails to link on arm64 (`AssertionError:
   invoke_ functions exported but exceptions and longjmp are both disabled`,
   see `setup-render-wasm.log`). The test build only needs the generated
   enums file, so `render_wasm_shared.js` from that build's output was
   copied to `exporter/src/app/wasm/shared.js`, as `copy_shared_artifact`
   would do. The first build attempt that failed without it is in
   `setup-attempt-1.log`.

Baseline: the existing `exporter-tests.queue-test` passed in the same
environment, 15 tests, 49 assertions, 0 failures (`baseline-queue-test.log`).

## Summary

| Compiles | Tests | Assertions | Passed | Failed | Errors |
|---|---|---|---|---|---|
| Yes, 1 warning | 13 | 31 | 3 | 10 | 0 |

Compiler warning: `Use of undeclared Var exporter-tests.duplicate-job-test/&`
at line 208. The test uses `p/loop` with a `[job & more]` destructuring
binding, which promesa's `p/loop` doesn't support.

## Per-test results

| # | Test | Req | Result | Error type | What happened |
|---|---|---|---|---|---|
| 1 | `each-new-job-gets-its-own-unique-id` | R1 | Pass | n/a | Two distinct UUIDs |
| 2 | `a-valid-new-job-is-added-to-the-queue` | R2 | Pass | n/a | One entry, payload stored |
| 3 | `enqueuing-the-same-job-twice-keeps-a-single-entry` | R3 | Fail | Assertion (feature gap) | 2 entries, depth 2 |
| 4 | `a-separate-request-reusing-an-existing-id-is-not-queued-again` | R3 edge | Fail | Assertion (feature gap) | Same id queued twice |
| 5 | `concurrent-enqueues-of-the-same-job-keep-a-single-entry` | R3 edge | Fail | Assertion (feature gap) | 5 entries |
| 6 | `a-job-already-claimed-by-a-worker-is-not-queued-again` | R3 edge | Fail | Assertion (feature gap) | Claimed job back on the queue |
| 7 | `different-jobs-are-all-accepted-in-order` | R4 | Pass | n/a | a, b, c in order |
| 8 | `queue-holds-one-entry-per-id-after-mixed-adds` | R5 | Fail | Assertion (**test defect**) | Loop stopped after the first job: queue `[a]`, not `[a b a c b]` |
| 9 | `a-null-job-id-is-rejected` | R6 | Fail | Assertion (feature gap) | Resolved; `""` queued |
| 10 | `a-job-without-an-id-is-rejected` | R6 | Fail | Assertion (feature gap) | Resolved; `""` queued |
| 11 | `a-blank-job-id-is-rejected` | R6 | Fail | Assertion (feature gap) | Resolved; `""` queued |
| 12 | `a-non-uuid-job-id-is-rejected` | R6 | Fail | Assertion (feature gap) | Resolved; `"not-a-uuid"` queued |
| 13 | `a-rejected-id-leaves-existing-jobs-in-place` | R6 edge | Fail | Assertion (feature gap) | Resolved; `""` queued after the job |

"Feature gap" means the assertion is right and the code under test lacks
the feature. "Test defect" means the test itself is wrong.

## Evaluation

**Do the tests reflect the requirements?** Yes. Every S1 requirement and the
prompt's "one entry per job ID" has at least one test, and each test names
its requirement. Two tests go past the written requirements on the AI's own
assumptions: #6 (an id already claimed by a worker) and #12 (a non-UUID
counts as invalid). The group should confirm both are wanted.

**Are the assertions meaningful?** Mostly yes. They check what is really in
the Redis list (`lrange`, counts, order), not just return values. The
failures show the real behavior: 2 and 5 copies of one id, an empty-string
id pushed for nil. Weak spots:

- #1 tests `jobs/create-queued!` (`uuid/next`), not the queue. It would pass
  with or without the feature.
- #2 and #7 pass on the current code. They guard against breaking normal
  enqueueing but don't show the feature works.
- R6 tests accept any rejection, of any type. A crash for an unrelated
  reason would also count as "rejected". The queue-state check makes up
  for part of this.

**Do the tests validate the intended functionality?** Against the current
code, 9 of the 10 failures correctly report a missing feature (true
positives), so the tests do tell the feature apart from its absence.
One failure, #8, is a false failure caused by a bug in the generated test.
That test can't validate R5 until it is repaired. Whether the 9 tests pass
on a correct implementation can only be checked once the feature exists.

**Useful by the group's criterion** (compiles, passes, checks real
requirements, covers an edge case, needs little change): none of the 13
fully qualify on this run. The 3 passing tests are normal cases, and the
edge-case tests can't pass until the feature is built. The criterion
assumes the feature already exists; the group needs to decide how to score
tests written before the code.

## Re-run after the feature was implemented

Same generated test file, unchanged. Feature in
`exporter/src/app/jobs/queue.cljs` and `exporter/src/app/redis.cljs`:

- `enqueue!` rejects with `:invalid-job-id` unless the id coerces to a
  UUID.
- It then takes an `enqueued.<id>` marker with an atomic `SET NX EX`. If
  the marker already exists, it writes nothing and resolves `false`.
  Otherwise it writes the payload, pushes the id and resolves `true`.
- `ack!` and `remove-queued!` delete the marker. If the payload write or
  the push fails, the marker is dropped too, so a retry isn't turned away.

Logs: `after-feature-tests.log` (each namespace run on its own),
`after-feature-fmt.log`, `after-feature-lint.log`,
`after-feature-build.log`.

| Namespace | Tests | Assertions | Failures |
|---|---|---|---|
| `duplicate-job-test` (generated) | 13 | 31 | 2 (both in #8) |
| `queue-test` | 15 | 49 | 0 |
| `crypto-test` | 5 | 5 | 0 |
| `jobs-test` | 4 | 9 | 0 |
| `scheduler-test` | 2 | 5 | 0 |
| `export-shapes-test` | 1 | 2 | 0 |
| `shell-test` | 3 | 5 | 0 |
| `wasm-pool-test` | 2 | 7 | 0 |

- Generated tests: 12 of 13 now pass, up from 3. The 9 tests that failed
  for lack of the feature all pass, which confirms they were true positives.
- #8 still fails for its own reason (the `p/loop` destructuring bug), which
  confirms it's a test defect.
- `renderer-svg-test` was not run. It needs the compiled
  `render-wasm.wasm`, which can't be built on arm64 here (see Environment).
  A full run crashes on it with `ENOENT .../render-wasm.wasm`
  (`after-feature-all-tests.log`).
- Format check passes. Lint fails with 3 errors, all at line 208 of the
  generated test (`Unresolved symbol: job`, `&`, `more`), the same defect
  as #8. The source changes lint clean.

## Next step (ChatTester)

Send back to the AI: the compiler warning at line 208, the #8 failure
output, and the `p/loop`/`p/recur` signature from promesa. Up to 3 tries.
