/**
 * The verdict of a `clojure_test` or `clojure_eval` call, from its exit status and the lines the test JVM
 * printed.
 *
 * Exit status, shared with `scripts/testjvm.clj`: 0 pass or evaluated, 1 a test failed, no test matched, or the
 * code threw, 2 the reload failed, 3 the JVM is not running or died during the call, 4 busy, past the deadline,
 * or cancelled, 5 the test JVM must restart, 6 environment or harness error. The JVM prints one `[testjvm]` line
 * per case, and the verdicts that need to tell cases apart read it.
 */

export type Target = "test" | "backend";

/** What a call to a JVM came to. */
export interface CallOutcome {
    /** exit status, as above */
    code: number;
    /** what the JVM printed, colour codes out */
    out: string;
    /** with 3: the connection closed during the call, so the JVM died */
    died?: boolean;
    /** with 4: the call passed its deadline and the evaluation was interrupted */
    timedOut?: boolean;
    /** with 4: the client cancelled the call and the evaluation was interrupted */
    cancelled?: boolean;
    /** with 0: a value was cut at the print quota */
    valueCut?: boolean;
}

export interface Verdict {
    label: string;
    /** the tool could not do its job: reported as a tool error */
    error: boolean;
    note?: string;
}

export const VERDICT_LINE = /^\[testjvm\] \d+ tests, .*$/m;
const NO_MATCH_LINE = /^\[testjvm\] no test matched\b/m;
const RELOAD_FAILED_LINE = /^\[testjvm\] reload failed in /m;
const GAVE_UP_LINE = /^\[testjvm\] gave up waiting for .*$/m;
/** A run stopped between tests by its deadline or a cancel; the verdict line precedes it. */
const STOPPED_LINE = /^\[testjvm\] (deadline passed|cancelled); (\d+) tests not run/m;
export const RESTART_LINE = /^\[testjvm\] restart needed: (.*)$/m;
const ENVIRONMENT_LINE = /^\[(?:testjvm|mcp)\] (?:environment|harness error): .*$/m;
const NO_VERDICT = "No verdict; the output below says why.";
const SLICE_HINT =
    "Bind the value instead, (def r <expr>), then print a slice: (subs (pr-str r) 0 2000), (take 10 r), or (keys r).";

/** Exits 3 and up, shared by test runs and evaluations. */
function sharedVerdict(o: CallOutcome, call: "run" | Target): Verdict {
    switch (o.code) {
        case 3:
            if (o.died) {
                return {
                    label: "JVM DIED",
                    error: true,
                    note:
                        call === "backend"
                            ? "The backend JVM closed the connection before answering; check the backend window of the devenv's tmux session."
                            : "The test JVM died during the call; nothing was retried. Its log tail follows. The next call starts a fresh JVM (about 15 s).",
                };
            }
            return {
                label: "ERROR",
                error: true,
                note:
                    call === "backend"
                        ? "The backend's nREPL (port 6064) does not answer; is the backend running?"
                        : "The test JVM is not running.",
            };
        case 4: {
            const gaveUp = GAVE_UP_LINE.exec(o.out);
            if (gaveUp) {
                return {
                    label: "BUSY",
                    error: true,
                    note: `Another call holds the test JVM, and this one stopped waiting: ${gaveUp[0]}. Run again when it ends.`,
                };
            }
            const stopped = STOPPED_LINE.exec(o.out);
            if (stopped?.[1] === "cancelled") {
                return {
                    label: "CANCELLED",
                    error: true,
                    note: `The call was cancelled; ${stopped[2]} tests did not run. Results so far follow.`,
                };
            }
            if (stopped) {
                return {
                    label: "TIMEOUT",
                    error: true,
                    note: `The run passed timeout_s; ${stopped[2]} tests did not run. Results so far follow. Raise timeout_s or narrow the ids.`,
                };
            }
            if (o.cancelled) {
                return {
                    label: "CANCELLED",
                    error: true,
                    note:
                        call === "run"
                            ? "The call was cancelled before the run started; no test ran."
                            : "The call was cancelled; the evaluation was interrupted.",
                };
            }
            if (o.timedOut) {
                return {
                    label: "TIMEOUT",
                    error: true,
                    note:
                        call === "run"
                            ? "The run passed timeout_s and was interrupted. Raise timeout_s or narrow the ids."
                            : "No answer within timeout_s; the evaluation was interrupted.",
                };
            }
            return { label: "ERROR", error: true, note: NO_VERDICT };
        }
        case 5: {
            const stale = RESTART_LINE.exec(o.out);
            if (!stale) return { label: "ERROR", error: true, note: NO_VERDICT };
            return {
                label: "ERROR",
                error: true,
                note: `${stale[0]}. A restart did not clear it; read what the line names and fix that first.`,
            };
        }
        case 6:
            return { label: "ERROR", error: true, note: ENVIRONMENT_LINE.exec(o.out)?.[0] ?? NO_VERDICT };
        default:
            return { label: "ERROR", error: true, note: NO_VERDICT };
    }
}

export function testVerdict(o: CallOutcome): Verdict {
    switch (o.code) {
        case 0:
            return { label: "PASS", error: false };
        case 1:
            if (NO_MATCH_LINE.test(o.out)) {
                return {
                    label: "NO MATCH",
                    error: false,
                    note: "No test matched the ids; each must name a test namespace, a ns/var, or a suite keyword.",
                };
            }
            return VERDICT_LINE.test(o.out)
                ? { label: "FAIL", error: false }
                : { label: "ERROR", error: true, note: NO_VERDICT };
        case 2:
            return RELOAD_FAILED_LINE.test(o.out)
                ? { label: "RELOAD FAILED", error: false, note: "A file did not load, so no test ran." }
                : { label: "ERROR", error: true, note: NO_VERDICT };
        default:
            return sharedVerdict(o, "run");
    }
}

export function evalVerdict(o: CallOutcome, target: Target): Verdict {
    switch (o.code) {
        case 0:
            return o.valueCut
                ? { label: "OK", error: false, note: `The value was cut in the JVM. ${SLICE_HINT}` }
                : { label: "OK", error: false };
        case 1:
            return { label: "THREW", error: false, note: "The code threw; the exception follows." };
        case 2:
            return RELOAD_FAILED_LINE.test(o.out)
                ? { label: "RELOAD FAILED", error: false, note: "A file did not load, so nothing was evaluated." }
                : { label: "ERROR", error: true, note: NO_VERDICT };
        default:
            return sharedVerdict(o, target);
    }
}
