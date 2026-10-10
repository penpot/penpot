import { type ChildProcess, spawn as nodeSpawn, type SpawnOptions } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { createLogger } from "../logger";

/**
 * Where the test JVM runs and how it starts.
 */
export interface TestJvmOptions {
    /** the backend directory in the container, the JVM's working directory */
    backendDir: string;
    /** where the JVM's log and pid file go */
    stateDir: string;
    /** port of the JVM's nREPL server */
    port: number;
    /** how long a start may take before it is reported as failed */
    startTimeoutMs: number;
    /** spawns the JVM; replaced in tests */
    spawn?: (command: string, args: string[], options: SpawnOptions) => ChildProcess;
    /** whether `pid` is a live test JVM; replaced in tests */
    isAlive?: (pid: number) => boolean;
    /** sends a signal to a process; replaced in tests */
    kill?: (pid: number, signal: NodeJS.Signals) => void;
}

/** The JVM did not start; the message carries the reason and the tail of its log. */
export class TestJvmStartError extends Error {}

/**
 * JVM options of the test JVM. The heap cap keeps it from sizing itself from the host and sitting in swap when
 * idle: live heap after GC peaked at 333 MB over whole-suite runs and reloads, and 2 GB leaves room for a heavy
 * test. Attaching to itself lets nREPL stop an evaluation thread that ignores an interrupt.
 */
const JVM_OPTS = [
    "-Xmx2g",
    "-XX:MaxMetaspaceSize=1g",
    "-XX:+ExitOnOutOfMemoryError",
    "-XX:G1PeriodicGCInterval=300000",
    "-Djdk.attach.allowAttachSelf",
    "-Dlog4j2.configurationFile=scripts/testjvm-log4j2.xml",
];

/** The script the JVM runs, relative to the backend directory. */
export const TESTJVM_SCRIPT = "scripts/testjvm.clj";

/**
 * The alias passed with -Sdeps: clj-reload (kept out of deps.edn, since only this JVM uses it), the JVM options,
 * and the script as the entry point. As the last alias with :main-opts in `-M:dev:test:testjvm`, it replaces
 * kaocha.runner and keeps :dev's and :test's deps and jvm-opts.
 */
export function sdepsAlias(backendDir: string): string {
    const opts = JVM_OPTS.map((o) => JSON.stringify(o)).join(" ");
    const script = JSON.stringify(path.join(backendDir, TESTJVM_SCRIPT));
    return `{:aliases {:testjvm {:extra-deps {io.github.tonsky/clj-reload {:mvn/version "1.0.0"}} :jvm-opts [${opts}] :main-opts ["-i" ${script}]}}}`;
}

/** PENPOT_FLAGS with enable-backend-asserts: app.config sets `*assert*` from the flags, as CI runs the suite. */
export function flagsWithAsserts(flags: string | undefined): string {
    const list = (flags ?? "").split(/\s+/).filter((f) => f !== "");
    return list.includes("enable-backend-asserts") ? list.join(" ") : [...list, "enable-backend-asserts"].join(" ");
}

/**
 * The lifecycle of the persistent test JVM: `scripts/testjvm.clj` on the backend's `:dev:test` classpath, with
 * its own nREPL port.
 *
 * The JVM runs in a process group of its own, so it can outlive the server; the next server adopts it through
 * its pid file. The JVM exits by itself after two hours without a call. Starts and stops are serialised: two calls
 * that find the JVM down start one JVM.
 */
export class TestJvm {
    private readonly logger = createLogger("TestJvm");
    private readonly spawn: NonNullable<TestJvmOptions["spawn"]>;
    private readonly isAlive: NonNullable<TestJvmOptions["isAlive"]>;
    private readonly kill: NonNullable<TestJvmOptions["kill"]>;
    /** the start or stop in progress, which later callers join */
    private transition: Promise<void> | null = null;

    constructor(private readonly options: TestJvmOptions) {
        this.spawn = options.spawn ?? nodeSpawn;
        this.isAlive = options.isAlive ?? defaultIsAlive;
        this.kill = options.kill ?? ((pid, signal) => process.kill(pid, signal));
    }

    get port(): number {
        return this.options.port;
    }

    get logFile(): string {
        return path.join(this.options.stateDir, "jvm.log");
    }

    private get pidFile(): string {
        return path.join(this.options.stateDir, "jvm.pid");
    }

    /** The pid of a ready JVM: its pid file exists and names a live process. */
    readyPid(): number | null {
        let pid: number;
        try {
            pid = Number.parseInt(fs.readFileSync(this.pidFile, "utf8").trim(), 10);
        } catch {
            return null;
        }
        return Number.isInteger(pid) && pid > 0 && this.isAlive(pid) ? pid : null;
    }

    /**
     * Starts the JVM unless one is ready, and waits until it is.
     *
     * @throws TestJvmStartError when it exits during the start or is not ready in time
     */
    async start(): Promise<void> {
        await this.serialise(async () => {
            if (this.readyPid() === null) await this.launch();
        });
    }

    /**
     * Stops the JVM, if one runs: TERM, then KILL after 20 seconds.
     */
    async stop(): Promise<void> {
        await this.serialise(() => this.terminate());
    }

    /** Stops the JVM and starts a new one. */
    async restart(): Promise<void> {
        await this.serialise(async () => {
            await this.terminate();
            await this.launch();
        });
    }

    /** The last `lines` lines of the JVM's own output (stdout and stderr), or "" without a log. */
    logTail(lines: number): string {
        let text: string;
        try {
            text = fs.readFileSync(this.logFile, "utf8");
        } catch {
            return "";
        }
        const all = text.replace(/\n+$/, "").split("\n");
        return all.slice(Math.max(0, all.length - lines)).join("\n");
    }

    private async serialise(step: () => Promise<void>): Promise<void> {
        while (this.transition) await this.transition.catch(() => undefined);
        this.transition = step().finally(() => {
            this.transition = null;
        });
        return this.transition;
    }

    private async launch(): Promise<void> {
        const { stateDir, backendDir, startTimeoutMs } = this.options;
        fs.mkdirSync(stateDir, { recursive: true });
        fs.rmSync(this.pidFile, { force: true });
        if (fs.existsSync(this.logFile)) fs.renameSync(this.logFile, `${this.logFile}.1`);
        const log = fs.openSync(this.logFile, "a");
        this.logger.info("Starting the test JVM in %s (log: %s)", backendDir, this.logFile);
        let child: ChildProcess;
        try {
            child = this.spawn("clojure", ["-Sdeps", sdepsAlias(backendDir), "-M:dev:test:testjvm"], {
                cwd: backendDir,
                detached: true,
                stdio: ["ignore", log, log],
                env: {
                    ...process.env,
                    PENPOT_FLAGS: flagsWithAsserts(process.env.PENPOT_FLAGS),
                    PENPOT_TESTJVM_PORT: String(this.options.port),
                    PENPOT_TESTJVM_DIR: stateDir,
                },
            });
        } finally {
            fs.closeSync(log);
        }
        let exited: string | null = null;
        child.once("exit", (code, signal) => {
            exited = signal ? `signal ${signal}` : `code ${code}`;
        });
        child.once("error", (error) => {
            exited = error.message;
        });
        child.unref();

        const started = Date.now();
        for (;;) {
            if (this.readyPid() !== null) {
                this.logger.info("Test JVM ready after %d s", Math.round((Date.now() - started) / 1000));
                return;
            }
            if (exited !== null) {
                throw new TestJvmStartError(
                    `The test JVM exited during its start (${exited}). Its log ends:\n${this.logTail(30)}`
                );
            }
            if (Date.now() - started > startTimeoutMs) {
                if (child.pid !== undefined) this.signalGroup(child.pid, "SIGKILL");
                throw new TestJvmStartError(
                    `The test JVM was not ready within ${Math.round(startTimeoutMs / 1000)} s and was killed. Its log ends:\n${this.logTail(30)}`
                );
            }
            await sleep(200);
        }
    }

    private async terminate(): Promise<void> {
        const pid = this.readyPid();
        if (pid === null) return;
        this.logger.info("Stopping the test JVM (pid %d)", pid);
        this.signalGroup(pid, "SIGTERM");
        for (let waited = 0; waited < 20_000; waited += 200) {
            if (!this.isAlive(pid)) break;
            await sleep(200);
        }
        if (this.isAlive(pid)) this.signalGroup(pid, "SIGKILL");
        fs.rmSync(this.pidFile, { force: true });
    }

    /** Signals the JVM's process group (the `clojure` launcher execs java, so the leader is the JVM). */
    private signalGroup(pid: number, signal: NodeJS.Signals): void {
        try {
            this.kill(-pid, signal);
        } catch {
            try {
                this.kill(pid, signal);
            } catch {
                // already gone
            }
        }
    }
}

/**
 * Whether `pid` is a live process running the test JVM script. The pid file outlives a JVM killed with KILL, and
 * the pid may since belong to another process, which must be neither adopted nor signalled.
 */
function defaultIsAlive(pid: number): boolean {
    try {
        const argv = fs.readFileSync(`/proc/${pid}/cmdline`, "utf8").split("\0");
        return argv.some((arg) => arg.endsWith(TESTJVM_SCRIPT));
    } catch {
        return false;
    }
}
