import { randomUUID } from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import {
    NreplCancelledError,
    NreplClient,
    NreplClosedError,
    NreplConnectError,
    type NreplRequestOptions,
    type NreplResult,
    NreplTimeoutError,
} from "../NreplClient";
import type { ToolCallContext } from "../Tool";
import { TextContent, ToolResponse } from "../ToolResponse";
import { createLogger } from "../logger";
import { delimiterProblem } from "./delimiters";
import { capEval, cleanOutput, shapeRun } from "./output";
import { containerPath } from "./paths";
import { TestJvm, TestJvmStartError } from "./TestJvm";
import {
    type CallOutcome,
    evalVerdict,
    RESTART_LINE,
    type Target,
    testVerdict,
    type Verdict,
    VERDICT_LINE,
} from "./verdicts";

/** Port of the running backend's nREPL server (app.main). */
export const BACKEND_NREPL_PORT = 6064;
/** Port of the test JVM's nREPL server (scripts/testjvm.clj). */
export const TEST_NREPL_PORT = 6065;
/** Print quota of an evaluation's values, in characters. */
export const EVAL_QUOTA = 16_000;

/**
 * A system restart (user, app.main) or a tools.namespace refresh inside the JVM. It can park the msgbus io-loop
 * and leave graph sync and websockets dead until the process restarts.
 */
export const IN_JVM_RESTART =
    /\((?:(?:app\.main|main)\/restart(?:-all)?|user\/restart[\w!*-]*|restart(?:-all)?|(?:clojure\.tools\.namespace\.)?repl\/refresh(?:-all)?)(?=[\s)])/;

/** A focus id: a namespace, ns/var, or suite keyword. The check keeps an id from becoming code. */
const FOCUS_ID = /^:?[A-Za-z_*+!?<>=][A-Za-z0-9._/*+!?<>=:-]*$/;

/** The client waits this long past a run's deadline: no test starts after it, but the running one finishes. */
const RUN_MARGIN_MS = 30_000;
/** How often a long call reports progress. */
const PROGRESS_INTERVAL_MS = 10_000;
const LOG_TAIL_LINES = 30;
/** The nREPL session that cancels and checks; never the session of a run. */
const CONTROL_SESSION = "control";

/**
 * Settings of the Clojure tools, read from the environment by {@link ClojureRuntime.fromEnv}.
 */
export interface ClojureSettings {
    /** the repository root in the container */
    repoRoot: string;
    /** the repository root on the host (PENPOT_SOURCE_PATH), for files named by host path */
    hostRoot?: string;
    /** default timeout of a focused test run, in seconds */
    testTimeoutS: number;
    /** default timeout of a whole-suite run, in seconds */
    suiteTimeoutS: number;
    /** default timeout of an evaluation, in seconds */
    evalTimeoutS: number;
    /** JSON Lines file that records each call; undefined records nothing */
    callLog?: string;
}

/** Parameters of a test run, as the clj_test tool takes them. */
export interface TestParams {
    ids?: string[];
    seed?: number;
    timeout_s?: number;
    restart?: boolean;
}

/** Parameters of an evaluation, as the clj_eval tool takes them. */
export interface EvalParams {
    code?: string;
    file?: string;
    file_content?: string;
    file_name?: string;
    target?: Target;
    ns?: string;
    reload?: boolean;
    timeout_s?: number;
}

/** A call's outcome plus what the runtime did on its own to reach it. */
interface Attempt {
    outcome: CallOutcome;
    did: string[];
    startedJvm: boolean;
    restartedJvm: boolean;
    /** a start or restart that failed, which ends the call */
    failure?: Verdict & { body: string };
}

/**
 * The machinery behind `clj_test` and `clj_eval`: the test JVM's lifecycle, the nREPL clients of the
 * test JVM and of the running backend, and the shaping of what they print.
 *
 * Recovery, once per call: a test JVM that is not running is started and the call runs again; a test JVM that
 * answers "restart needed" (5) is restarted and the call runs again, unless another call holds it. A JVM that
 * died during the call is reported with its log tail and not retried, because the code may have killed it.
 */
export class ClojureRuntime {
    private readonly logger = createLogger("ClojureRuntime");
    /** keys of the persistent eval sessions that a call is using now */
    private readonly busySessions = new Set<string>();

    constructor(
        private readonly settings: ClojureSettings,
        private readonly testJvm: Pick<TestJvm, "start" | "restart" | "readyPid" | "logTail" | "logFile">,
        private readonly testRepl: NreplClient,
        private readonly backendRepl: NreplClient
    ) {}

    static fromEnv(env: Record<string, string | undefined>): ClojureRuntime {
        const repoRoot = env.PENPOT_REPO_ROOT ?? "/home/penpot/penpot";
        // Defaults only: each call can pass timeout_s, so the server needs no setting for them.
        const settings: ClojureSettings = {
            repoRoot,
            hostRoot: env.PENPOT_SOURCE_PATH || undefined,
            testTimeoutS: 300,
            suiteTimeoutS: 1800,
            evalTimeoutS: 120,
            callLog: env.PENPOT_MCP_CLOJURE_LOG || undefined,
        };
        const testJvm = new TestJvm({
            backendDir: path.join(repoRoot, "backend"),
            stateDir: env.PENPOT_TESTJVM_DIR ?? "/tmp/penpot-testjvm",
            port: TEST_NREPL_PORT,
            startTimeoutMs: 600_000,
        });
        return new ClojureRuntime(
            settings,
            testJvm,
            new NreplClient({ port: TEST_NREPL_PORT, host: "127.0.0.1" }),
            new NreplClient({ port: BACKEND_NREPL_PORT, host: "127.0.0.1" })
        );
    }

    get defaults(): Pick<ClojureSettings, "testTimeoutS" | "suiteTimeoutS" | "evalTimeoutS"> {
        return this.settings;
    }

    /** Runs kaocha in the test JVM on `params.ids`, after a reload of what changed. */
    async runTests(params: TestParams, context: ToolCallContext): Promise<ToolResponse> {
        const t0 = Date.now();
        const ids = params.ids ?? [];
        const bad = ids.find((id) => !FOCUS_ID.test(id));
        if (bad !== undefined) {
            return refusal(`Not run: '${bad}' is not a namespace, a ns/var, or a suite keyword.`);
        }
        const timeoutS = Math.max(
            1,
            Math.ceil(params.timeout_s ?? (ids.length > 0 ? this.settings.testTimeoutS : this.settings.suiteTimeoutS))
        );
        const seed = params.seed;
        const token = randomUUID();
        const forms = ids.map((id) => (id.startsWith(":") ? ` ${id}` : ` '${id}`)).join("");
        // a session of its own: nREPL runs one evaluation at a time per session, and a run is long
        const session = `run-${token}`;
        const what = ids.length > 0 ? `run of ${ids.join(" ")}` : "whole-suite run";

        const run = async (): Promise<CallOutcome> => {
            // a cancel during a start reached no JVM, so the run checks for it itself
            if (context.signal?.aborted) return { code: 4, out: "", cancelled: true };
            const deadline = Date.now() + timeoutS * 1000;
            const opts = `{:deadline-ms ${deadline} :token "${token}"${seed !== undefined ? ` :seed ${seed}` : ""}}`;
            return this.callInt(harnessCall("cli-run", `${opts}${forms}`), {
                session,
                timeoutMs: timeoutS * 1000 + RUN_MARGIN_MS,
            });
        };

        const onAbort = () => {
            void this.callInt(harnessCall("cancel!", `"${token}"`), {
                session: CONTROL_SESSION,
                timeoutMs: 10_000,
            });
        };
        context.signal?.addEventListener("abort", onAbort, { once: true });
        const stopProgress = startProgress(context, what, t0);
        try {
            const asked: string[] = [];
            if (params.restart) {
                const failure = await this.restartUnlessBusy("restart: true");
                if (failure) {
                    const attempt: Attempt = {
                        outcome: { code: 5, out: "" },
                        did: [],
                        startedJvm: false,
                        restartedJvm: false,
                        failure,
                    };
                    return this.finish("clj_test", attempt, failure, failure.body, t0, { what });
                }
                asked.push("Restarted the test JVM first, as asked.");
            }
            const attempt = await this.attempt(run, context);
            attempt.did.unshift(...asked);
            const { outcome } = attempt;
            if (attempt.failure) {
                return this.finish("clj_test", attempt, attempt.failure, attempt.failure.body, t0, { what });
            }
            const verdict = testVerdict(outcome);
            let body = shapeRun(outcome.out);
            if (verdict.label === "JVM DIED") body = [body, this.jvmLog()].filter(Boolean).join("\n\n");
            return this.finish("clj_test", attempt, verdict, body, t0, { what, order: runOrder(outcome.out) });
        } finally {
            stopProgress();
            context.signal?.removeEventListener("abort", onAbort);
            void this.testRepl.closeSession(session);
        }
    }

    /** Evaluates code, or loads a file, in the test JVM or in the running backend. */
    async evaluate(params: EvalParams, context: ToolCallContext): Promise<ToolResponse> {
        const t0 = Date.now();
        const given = [params.code, params.file, params.file_content].filter((v) => v !== undefined && v !== "");
        if (given.length !== 1) {
            return refusal("Not evaluated: pass exactly one of code, file, or file_content.");
        }
        const target: Target = params.target ?? "test";
        const timeoutS = Math.max(1, Math.ceil(params.timeout_s ?? this.settings.evalTimeoutS));

        let send: (session: string, options: NreplRequestOptions) => Promise<NreplResult>;
        let source: string;
        if (params.code) {
            const code = params.code;
            const restart = IN_JVM_RESTART.exec(code);
            if (restart) {
                return refusal(
                    `Not evaluated: ${restart[0]} restarts the system inside the JVM, which can park the msgbus io-loop and leave graph sync and websockets dead until a process restart. ` +
                        'To pick up an edited file, pass it as file with target "backend". To restart the backend, restart its process in the backend window of the devenv\'s tmux session. ' +
                        "To restart the test JVM: clj_test with restart: true."
                );
            }
            const problem = delimiterProblem(code);
            if (problem !== null) return refusal(`Not evaluated: ${problem}. Fix the delimiters and call again.`);
            source = "code";
            send = (session, options) => this.repl(target).evaluate(code, { ...options, session });
        } else {
            let content: string;
            let shown: string;
            if (params.file) {
                const resolved = containerPath(params.file, this.settings.hostRoot, this.settings.repoRoot);
                if ("refusal" in resolved) return refusal(`Not loaded: ${resolved.refusal}`);
                try {
                    content = fs.readFileSync(resolved.path, "utf8");
                } catch (error) {
                    return refusal(`Not loaded: cannot read ${params.file} (${(error as Error).message}).`);
                }
                shown = params.file;
            } else {
                content = params.file_content!;
                shown = params.file_name || "file_content.clj";
            }
            source = `file ${shown}`;
            send = (session, options) =>
                this.repl(target).loadFile(content, shown, path.basename(shown), { ...options, session });
        }

        // The client's persistent session keeps *1, *e, and bindings between calls. nREPL runs one evaluation at a
        // time per session, so a call that overlaps another of the same client gets a session of its own.
        const base = `${target}:${context.sessionId ?? "shared"}`;
        const ephemeral = this.busySessions.has(base);
        const session = ephemeral ? `${base}:${randomUUID()}` : base;
        this.busySessions.add(session);
        const call = async (): Promise<CallOutcome> => {
            const deadline = Date.now() + timeoutS * 1000;
            let prefix = "";
            if (target === "test") {
                // the reload, or with reload: false only the activity mark for the idle exit, runs first in the
                // same session; the code runs only when it returns 0
                const guard =
                    params.reload === false
                        ? harnessCall("touch!", "")
                        : harnessCall("reload-cli", `{:deadline-ms ${deadline} :quiet? true}`);
                const pre = await this.callInt(guard, { session, timeoutMs: timeoutS * 1000, signal: context.signal });
                if (pre.code !== 0) return pre;
                prefix = pre.out;
            }
            try {
                const result = await send(session, {
                    ns: params.ns,
                    timeoutMs: Math.max(1000, deadline - Date.now()),
                    signal: context.signal,
                    quota: EVAL_QUOTA,
                });
                const out = [prefix, cleanOutput(result.transcript)].filter((s) => s !== "").join("\n");
                if (result.namespaceNotFound) {
                    return { code: 1, out: `${out}\n[mcp] namespace not found: ${params.ns ?? "user"}` };
                }
                if (result.interrupted) return { code: 4, out, timedOut: true };
                return { code: result.failed ? 1 : 0, out, valueCut: result.truncated };
            } catch (error) {
                return failedCall(error, prefix);
            }
        };

        const stopProgress = startProgress(context, `evaluation of ${source}`, t0);
        try {
            const attempt: Attempt =
                target === "test"
                    ? await this.attempt(call, context)
                    : { outcome: await call(), did: [], startedJvm: false, restartedJvm: false };
            if (attempt.failure) {
                return this.finish("clj_eval", attempt, attempt.failure, attempt.failure.body, t0, { target });
            }
            const verdict = evalVerdict(attempt.outcome, target);
            let body = capEval(attempt.outcome.out);
            if (verdict.label === "JVM DIED" && target === "test") {
                body = [body, this.jvmLog()].filter(Boolean).join("\n\n");
            }
            return this.finish("clj_eval", attempt, verdict, body, t0, { target });
        } finally {
            stopProgress();
            this.busySessions.delete(session);
            if (ephemeral) void this.repl(target).closeSession(session);
        }
    }

    /** Closes the nREPL connections; the test JVM keeps running for the next server. */
    async close(): Promise<void> {
        await Promise.all([this.testRepl.close(), this.backendRepl.close()]);
    }

    private repl(target: Target): NreplClient {
        return target === "test" ? this.testRepl : this.backendRepl;
    }

    /** Runs `call` once, recovering a test JVM that is down (start) or stale (restart) for one more try. */
    private async attempt(call: () => Promise<CallOutcome>, context: ToolCallContext): Promise<Attempt> {
        const attempt: Attempt = { outcome: await call(), did: [], startedJvm: false, restartedJvm: false };
        const { outcome } = attempt;
        if (outcome.code === 3 && !outcome.died) {
            context.progress?.(0, "Starting the test JVM (about 15 s)");
            try {
                await this.testJvm.start();
                // a connection to a JVM that was down is gone, and its sessions with it
                await this.testRepl.close();
            } catch (error) {
                attempt.failure = startFailure("The test JVM was not running and did not start", error);
                return attempt;
            }
            attempt.startedJvm = true;
            attempt.did.push("The test JVM was not running; started it and ran again.");
        } else if (outcome.code === 5) {
            const why = RESTART_LINE.exec(outcome.out)?.[1] ?? "it reported itself stale";
            const failure = await this.restartUnlessBusy(why);
            if (failure) {
                attempt.failure = failure;
                return attempt;
            }
            attempt.restartedJvm = true;
            attempt.did.push(`The test JVM was stale (${why}); restarted it and ran again.`);
        } else {
            return attempt;
        }
        attempt.outcome = await call();
        return attempt;
    }

    /** Restarts the test JVM, or reports BUSY when a run holds it: a restart would kill another call's run. */
    private async restartUnlessBusy(why: string): Promise<(Verdict & { body: string }) | undefined> {
        if (this.testJvm.readyPid() !== null) {
            try {
                const holder = await this.testRepl.evaluate("((resolve 'testjvm/holder-line))", {
                    session: CONTROL_SESSION,
                    timeoutMs: 10_000,
                });
                const line = holder.values.at(-1);
                if (line !== undefined && line !== "nil") {
                    return {
                        label: "BUSY",
                        error: true,
                        note: `The test JVM needs a restart (${why}), but another call holds it: ${line}. Run again when that call ends.`,
                        body: "",
                    };
                }
            } catch {
                // a JVM that does not answer holds nothing worth keeping
            }
        }
        try {
            await this.testJvm.restart();
            // the old JVM's connection may not have seen its close yet; the next call must use the new JVM
            await this.testRepl.close();
        } catch (error) {
            return startFailure(`The test JVM needed a restart (${why}), and the restart failed`, error);
        }
        return undefined;
    }

    /** Evaluates a form that returns an exit status. A form that throws or returns no integer is a harness error. */
    private async callInt(code: string, options: NreplRequestOptions): Promise<CallOutcome> {
        try {
            const result = await this.testRepl.evaluate(code, options);
            const out = cleanOutput(result.out + result.err);
            if (result.interrupted) return { code: 4, out, timedOut: true };
            const value = result.values.at(-1);
            const status = value !== undefined && /^-?\d+$/.test(value) ? Number(value) : null;
            if (result.failed || status === null) {
                const why = result.failed ? `threw ${result.ex ?? "an exception"}` : `returned ${value}`;
                return { code: 6, out: `${out}\n[mcp] harness error: the test JVM's ${why}, not an exit status` };
            }
            return { code: status, out };
        } catch (error) {
            return failedCall(error, "");
        }
    }

    private jvmLog(): string {
        const log = this.testJvm.logTail(LOG_TAIL_LINES);
        return log ? `The test JVM's log, last ${LOG_TAIL_LINES} lines (${this.testJvm.logFile}):\n${log}` : "";
    }

    private finish(
        tool: string,
        attempt: Attempt,
        verdict: Verdict,
        body: string,
        t0: number,
        extra: { what?: string; target?: Target; order?: string | null }
    ): ToolResponse {
        const exit = verdict === attempt.failure ? null : attempt.outcome.code;
        this.record({
            tool,
            target: extra.target ?? "test",
            what: extra.what,
            verdict: verdict.label,
            exit,
            ms: Date.now() - t0,
            startedJvm: attempt.startedJvm,
            restartedJvm: attempt.restartedJvm,
        });
        const where = extra.target === "backend" ? "backend JVM" : "test JVM";
        return finish(
            { ...verdict, note: [...attempt.did, verdict.note].filter(Boolean).join("\n") || undefined },
            body,
            t0,
            { exit, where, order: extra.order }
        );
    }

    /** Appends one line to the call log, when one is configured. Never fails the call. */
    private record(entry: Record<string, unknown>): void {
        if (!this.settings.callLog) return;
        try {
            fs.appendFileSync(this.settings.callLog, JSON.stringify({ at: new Date().toISOString(), ...entry }) + "\n");
        } catch (error) {
            this.logger.warn("Cannot write the Clojure call log %s: %s", this.settings.callLog, error);
        }
    }
}

/**
 * A call of a function of `scripts/testjvm.clj` that answers "restart needed" (5) when the JVM runs a version of
 * the script without it, so the runtime restarts the JVM instead of reporting a harness error.
 */
export function harnessCall(fn: string, args: string): string {
    return (
        `(if-let [f (resolve 'testjvm/${fn})] (f${args ? ` ${args}` : ""}) ` +
        `(do (println "[testjvm] restart needed: the test JVM runs an older scripts/testjvm.clj") 5))`
    );
}

function failedCall(error: unknown, out: string): CallOutcome {
    if (error instanceof NreplConnectError) return { code: 3, out };
    if (error instanceof NreplClosedError) return { code: 3, out, died: true };
    if (error instanceof NreplTimeoutError) return { code: 4, out, timedOut: true };
    if (error instanceof NreplCancelledError) return { code: 4, out, cancelled: true };
    return { code: 6, out: `${out}\n[mcp] harness error: ${String(error)}` };
}

function startFailure(what: string, error: unknown): Verdict & { body: string } {
    const message = error instanceof TestJvmStartError ? error.message : String(error);
    return { label: "ERROR", error: true, note: `${what}.`, body: message };
}

/** The kaocha order of a run: its seed from the verdict line, or "order fixed". */
export function runOrder(out: string): string | null {
    const verdict = VERDICT_LINE.exec(out)?.[0] ?? "";
    const seed = /; seed (\d+)\s*$/.exec(verdict) ?? /^Randomized with --seed (\d+)/m.exec(out);
    if (seed) return `seed ${seed[1]}`;
    return /; order fixed\s*$/.test(verdict) ? "order fixed" : null;
}

/** Reports progress every PROGRESS_INTERVAL_MS while a call runs; returns the function that stops it. */
function startProgress(context: ToolCallContext, what: string, t0: number): () => void {
    const progress = context.progress;
    if (!progress) return () => undefined;
    const timer = setInterval(() => {
        const s = Math.round((Date.now() - t0) / 1000);
        progress(s, `${what}: ${s} s`);
    }, PROGRESS_INTERVAL_MS);
    return () => clearInterval(timer);
}

function refusal(text: string): ToolResponse {
    const response = new ToolResponse([new TextContent(text)]);
    response.isError = true;
    return response;
}

/** The tool result: the verdict line, the notes, then the body. */
function finish(
    verdict: Verdict,
    body: string,
    t0: number,
    extra: { exit: number | null; where?: string; order?: string | null }
): ToolResponse {
    const exit = extra.exit === null ? "" : ` (${extra.exit})`;
    const parts = [`${((Date.now() - t0) / 1000).toFixed(1)} s`];
    if (extra.where) parts.push(extra.where);
    if (extra.order) parts.push(extra.order);
    const lines = [`${verdict.label}${exit}; ${parts.join("; ")}`];
    if (verdict.note) lines.push(verdict.note);
    if (body) lines.push("", body);
    const response = new ToolResponse([new TextContent(lines.join("\n"))]);
    if (verdict.error) response.isError = true;
    return response;
}
