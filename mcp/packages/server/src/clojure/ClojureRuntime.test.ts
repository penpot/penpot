import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import { afterEach, beforeEach, describe, test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { NreplClient } from "../NreplClient";
import type { ToolResponse } from "../ToolResponse";
import { type BValue, decode, encode } from "../nrepl/bencode";
import { ClojureRuntime, harnessCall } from "./ClojureRuntime";

type Msg = { [key: string]: BValue };
type Reply = (fields: Msg, done?: boolean) => void;

const PASS_LINE = "[testjvm] 6 tests, 12 assertions passed, 0 failed, 0 errors; reloaded 0 namespaces; 0.3 s; seed 42";

/**
 * An nREPL server that stands in for the test JVM: it answers clone, close, and interrupt itself, and hands each
 * eval or load-file to the test's handler.
 */
class FakeJvm {
    readonly received: Msg[] = [];
    handle: (msg: Msg, reply: Reply, socket: net.Socket) => void = (_msg, reply) => reply({ value: "nil" }, true);
    onInterrupt: (msg: Msg) => void = () => undefined;
    private server: net.Server | null = null;
    private readonly sockets = new Set<net.Socket>();
    private sessions = 0;

    constructor(readonly port: number) {}

    get listening(): boolean {
        return this.server !== null;
    }

    async listen(): Promise<void> {
        const server = net.createServer((socket) => this.accept(socket));
        await new Promise<void>((resolve) => server.listen(this.port, "127.0.0.1", resolve));
        this.server = server;
    }

    async close(): Promise<void> {
        for (const socket of this.sockets) socket.destroy();
        const server = this.server;
        this.server = null;
        if (server) await new Promise<void>((resolve) => server.close(() => resolve()));
    }

    evals(pattern: RegExp): Msg[] {
        return this.received.filter((m) => typeof m.code === "string" && pattern.test(m.code));
    }

    private accept(socket: net.Socket): void {
        this.sockets.add(socket);
        socket.on("close", () => this.sockets.delete(socket));
        let buffer = Buffer.alloc(0);
        socket.on("data", (chunk: Buffer) => {
            buffer = Buffer.concat([buffer, chunk]);
            for (;;) {
                const decoded = decode(buffer);
                if (decoded === null) break;
                buffer = buffer.subarray(decoded.end);
                this.dispatch(decoded.value as Msg, socket);
            }
        });
    }

    private dispatch(msg: Msg, socket: net.Socket): void {
        this.received.push(msg);
        const reply: Reply = (fields, done = false) => {
            if (socket.destroyed) return;
            socket.write(
                encode({ id: msg.id, session: msg.session ?? "", ...fields, ...(done ? { status: ["done"] } : {}) })
            );
        };
        switch (msg.op) {
            case "clone":
                reply({ "new-session": `session-${++this.sessions}` }, true);
                return;
            case "close":
                reply({}, true);
                return;
            case "interrupt":
                this.onInterrupt(msg);
                reply({}, true);
                return;
            default:
                this.handle(msg, reply, socket);
        }
    }
}

async function freePort(): Promise<number> {
    const server = net.createServer();
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const { port } = server.address() as net.AddressInfo;
    await new Promise<void>((resolve) => server.close(() => resolve()));
    return port;
}

function text(response: ToolResponse): string {
    const [first] = response.content;
    assert.ok(first?.type === "text", "the result is one text item");
    return first.text;
}

let jvm: FakeJvm;
let runtime: ClojureRuntime;
let repoRoot: string;
let lifecycle: { starts: number; restarts: number };

beforeEach(async () => {
    jvm = new FakeJvm(await freePort());
    repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), "clojure-runtime-"));
    lifecycle = { starts: 0, restarts: 0 };
    const testJvm = {
        start: async () => {
            lifecycle.starts++;
            if (!jvm.listening) await jvm.listen();
        },
        restart: async () => {
            lifecycle.restarts++;
            await jvm.close();
            await jvm.listen();
        },
        readyPid: () => (jvm.listening ? 4242 : null),
        logTail: () => "Exception in thread main: OutOfMemoryError",
        logFile: "/tmp/penpot-testjvm/jvm.log",
    };
    runtime = new ClojureRuntime(
        { repoRoot, hostRoot: "/host/repo", testTimeoutS: 300, suiteTimeoutS: 1800, evalTimeoutS: 120 },
        testJvm,
        new NreplClient({ port: jvm.port, host: "127.0.0.1" }),
        new NreplClient({ port: jvm.port, host: "127.0.0.1" })
    );
});

afterEach(async () => {
    await runtime.close();
    await jvm.close();
    fs.rmSync(repoRoot, { recursive: true, force: true });
});

describe("clojure_test", () => {
    test("a passing run reports the verdict, the exit status, and the seed first", async () => {
        await jvm.listen();
        jvm.handle = (_msg, reply) => {
            reply({ out: `[(......)]\n${PASS_LINE}\n` });
            reply({ value: "0" }, true);
        };
        const response = await runtime.runTests({ ids: ["backend-tests.passwords-test"] }, {});
        assert.match(text(response), /^PASS \(0\); \d+\.\d s; test JVM; seed 42\n/);
        assert.equal(response.isError, undefined);
        assert.match(String(jvm.evals(/cli-run/)[0].code), /:token "[^"]+"\} 'backend-tests\.passwords-test\)/);
    });

    test("a JVM that is not running is started, and the run goes again", async () => {
        jvm.handle = (_msg, reply) => {
            reply({ out: `${PASS_LINE}\n` });
            reply({ value: "0" }, true);
        };
        const response = await runtime.runTests({ ids: [":unit"] }, {});
        assert.equal(lifecycle.starts, 1);
        assert.match(text(response), /^PASS \(0\)/);
        assert.match(text(response), /not running; started it and ran again/);
    });

    test("a stale JVM is restarted unless another call holds it", async () => {
        await jvm.listen();
        let runs = 0;
        jvm.handle = (msg, reply) => {
            if (/holder-line/.test(String(msg.code))) return reply({ value: "nil" }, true);
            if (++runs === 1) {
                reply({ out: "[testjvm] restart needed: backend/deps.edn changed since the JVM started\n" });
                return reply({ value: "5" }, true);
            }
            reply({ out: `${PASS_LINE}\n` });
            reply({ value: "0" }, true);
        };
        const response = await runtime.runTests({ ids: ["a-test"] }, {});
        assert.equal(lifecycle.restarts, 1);
        assert.match(text(response), /^PASS \(0\)/);
        assert.match(text(response), /stale \(backend\/deps\.edn changed since the JVM started\); restarted it/);
    });

    test("a JVM that dies during the run is reported with its log, not retried", async () => {
        await jvm.listen();
        jvm.handle = (_msg, _reply, socket) => socket.destroy();
        const response = await runtime.runTests({ ids: ["a-test"] }, {});
        assert.match(text(response), /^JVM DIED \(3\)/);
        assert.match(text(response), /OutOfMemoryError/);
        assert.equal(response.isError, true);
        assert.equal(lifecycle.starts, 0);
    });

    test("cancelling the call cancels this call's run, by its token, and reports what did not run", async () => {
        await jvm.listen();
        let finishRun: Reply | undefined;
        jvm.handle = (msg, reply) => {
            const code = String(msg.code);
            if (/cli-run/.test(code)) {
                finishRun = reply;
                return;
            }
            if (/cancel!/.test(code)) {
                reply({ value: "0" }, true);
                finishRun?.({ out: `${PASS_LINE}\n[testjvm] cancelled; 3 tests not run\n` });
                finishRun?.({ value: "4" }, true);
            }
        };
        const controller = new AbortController();
        const pending = runtime.runTests({ ids: ["a-test"] }, { signal: controller.signal });
        while (finishRun === undefined) await sleep(5);
        controller.abort();
        const response = await pending;
        assert.match(text(response), /^CANCELLED \(4\)/);
        assert.match(text(response), /3 tests did not run/);
        const token = /:token "([^"]+)"/.exec(String(jvm.evals(/cli-run/)[0].code))?.[1];
        assert.ok(token);
        assert.equal(jvm.evals(/cancel!/)[0].code, harnessCall("cancel!", `"${token}"`));
    });

    test("an id that is not a namespace, var, or keyword never reaches the JVM", async () => {
        await jvm.listen();
        const response = await runtime.runTests({ ids: ["(System/exit 0)"] }, {});
        assert.equal(response.isError, true);
        assert.equal(jvm.received.length, 0);
    });
});

describe("clojure_eval", () => {
    test("an in-JVM restart is refused before it reaches the JVM", async () => {
        await jvm.listen();
        for (const code of ["(app.main/restart)", "(do (restart))", "(repl/refresh-all)", "(user/restart!)"]) {
            const response = await runtime.evaluate({ code }, {});
            assert.equal(response.isError, true, code);
            assert.match(text(response), /^Not evaluated: \(\S+ restarts the system inside the JVM/, code);
        }
        assert.equal(jvm.received.length, 0);
    });

    test("code with unbalanced delimiters is refused with the line and column", async () => {
        await jvm.listen();
        const response = await runtime.evaluate({ code: "(let [x 1]\n  (inc x)" }, {});
        assert.equal(response.isError, true);
        assert.equal(text(response), "Not evaluated: unclosed '(' opened at 1:1. Fix the delimiters and call again.");
        assert.equal(jvm.received.length, 0);
    });

    test("the test target reloads first, in the same session as the code", async () => {
        await jvm.listen();
        jvm.handle = (msg, reply) => reply({ value: /reload-cli/.test(String(msg.code)) ? "0" : "3" }, true);
        const response = await runtime.evaluate({ code: "(+ 1 2)" }, { sessionId: "client-a" });
        assert.match(text(response), /^OK \(0\); \d+\.\d s; test JVM\n\n3$/);
        const [reload, code] = jvm.received.filter((m) => m.op === "eval");
        assert.match(String(reload.code), /reload-cli/);
        assert.equal(reload.session, code.session);
        assert.equal(code["nrepl.middleware.print/quota"], 16000);
    });

    test("a file named by its host path is loaded under that path, so errors cite it", async () => {
        await jvm.listen();
        fs.mkdirSync(path.join(repoRoot, "tmp"));
        fs.writeFileSync(path.join(repoRoot, "tmp/probe.clj"), "(+ 40 2)\n");
        jvm.handle = (msg, reply) => reply({ value: msg.op === "load-file" ? "42" : "0" }, true);
        const response = await runtime.evaluate({ file: "/host/repo/tmp/probe.clj" }, {});
        assert.match(text(response), /^OK \(0\)/);
        const load = jvm.received.find((m) => m.op === "load-file")!;
        assert.equal(load.file, "(+ 40 2)\n");
        assert.equal(load["file-path"], "/host/repo/tmp/probe.clj");
        assert.equal(load["file-name"], "probe.clj");
    });

    test("a file outside the repository is refused, pointing at file_content", async () => {
        await jvm.listen();
        const response = await runtime.evaluate({ file: "/tmp/elsewhere.clj" }, {});
        assert.equal(response.isError, true);
        assert.match(text(response), /^Not loaded: .*file_content/);
        assert.equal(jvm.received.length, 0);
    });

    test("cancelling the call interrupts the evaluation", async () => {
        await jvm.listen();
        let pending: Reply | undefined;
        jvm.handle = (msg, reply) => {
            if (/reload-cli/.test(String(msg.code))) return reply({ value: "0" }, true);
            pending = reply;
        };
        jvm.onInterrupt = () => {
            pending?.({ status: ["interrupted"] });
            pending?.({}, true);
        };
        const controller = new AbortController();
        const call = runtime.evaluate({ code: "(Thread/sleep 100000)" }, { signal: controller.signal });
        while (pending === undefined) await sleep(5);
        controller.abort();
        const response = await call;
        assert.match(text(response), /^CANCELLED \(4\)/);
        assert.equal(jvm.received.filter((m) => m.op === "interrupt").length, 1);
    });

    test("a call past its timeout is interrupted and reported as a timeout", async () => {
        await jvm.listen();
        jvm.handle = (msg, reply) => {
            if (/reload-cli/.test(String(msg.code))) reply({ value: "0" }, true);
        };
        const response = await runtime.evaluate({ code: "(Thread/sleep 100000)", timeout_s: 1 }, {});
        assert.match(text(response), /^TIMEOUT \(4\)/);
        assert.equal(jvm.received.filter((m) => m.op === "interrupt").length, 1);
    });
});
