import * as net from "node:net";
import { type BValue, decode, encode } from "./nrepl/bencode";
import { createLogger } from "./logger";

/**
 * Result of evaluating a ClojureScript expression via nREPL.
 */
export interface NreplEvalResult {
    /** the returned value(s) as strings */
    values: string[];
    /** captured stdout output */
    out: string;
    /** captured stderr output */
    err: string;
    /** the namespace after evaluation */
    ns: string;
}

/**
 * Full result of an `eval` or `load-file` request, which {@link NreplClient.evaluate} and
 * {@link NreplClient.loadFile} return whether or not the code threw.
 */
export interface NreplResult extends NreplEvalResult {
    /** stdout, stderr, and each value on a line of its own, in the order they arrived */
    transcript: string;
    /** the code threw: an `ex` reply, or an `error` status such as namespace-not-found */
    failed: boolean;
    /** the class name of the exception the code threw */
    ex?: string;
    /** the evaluation was interrupted */
    interrupted: boolean;
    /** a value was cut at the print quota */
    truncated: boolean;
    /** the namespace the request named does not exist */
    namespaceNotFound: boolean;
}

/** Nothing listens on the port, or the connection attempt timed out. */
export class NreplConnectError extends Error {}

/** The connection closed before the request finished: the JVM likely died. */
export class NreplClosedError extends Error {}

/** The request passed its deadline; the evaluation was sent an interrupt. */
export class NreplTimeoutError extends Error {}

/** The caller's abort signal fired; the evaluation was sent an interrupt. */
export class NreplCancelledError extends Error {}

/**
 * Options of a single request.
 */
export interface NreplRequestOptions {
    /** key of the cloned session to run in; one session is kept per key (default "default") */
    session?: string;
    /** namespace to evaluate in */
    ns?: string;
    /** deadline of the whole request, in milliseconds (default: the client's) */
    timeoutMs?: number;
    /** aborts the request: the evaluation is interrupted and the call rejects with NreplCancelledError */
    signal?: AbortSignal;
    /** cut each printed value at this many characters (nREPL's print quota) */
    quota?: number;
    /** called with each piece of stdout as it arrives */
    onOut?: (text: string) => void;
}

/**
 * Connection settings of an {@link NreplClient}.
 */
export interface NreplClientOptions {
    port: number;
    host?: string;
    /** default deadline of a request, in milliseconds */
    timeoutMs?: number;
    /** most sessions kept at once; the least recently used one is closed beyond it */
    maxSessions?: number;
}

type Reply = { [key: string]: BValue };

interface Pending {
    onReply: (reply: Reply) => void;
    onClose: (error: Error) => void;
}

/** How long an interrupt, a clone, or a session close may take. */
const CONTROL_TIMEOUT_MS = 5_000;
const CONNECT_TIMEOUT_MS = 10_000;

/**
 * A client of an nREPL server, such as shadow-cljs's (port 3447) or a JVM's started with nrepl.server.
 *
 * It keeps one connection, opened lazily and reopened after it closes. Each request runs in a cloned session
 * chosen by key, so `*1`, `*e`, and dynamic bindings survive between the calls that share a key, and a request
 * that passes its deadline can be interrupted. Sessions are dropped when the connection closes, since the server
 * behind it may be a new process.
 */
export class NreplClient {
    private readonly logger = createLogger("NreplClient");
    private readonly host: string;
    private readonly port: number;
    private readonly timeoutMs: number;
    private readonly maxSessions: number;

    private socket: net.Socket | null = null;
    private connecting: Promise<net.Socket> | null = null;
    private buffer: Buffer = Buffer.alloc(0);
    private readonly pending = new Map<string, Pending>();
    /** session key to the cloned session id; Map order is least recently used first */
    private readonly sessions = new Map<string, Promise<string>>();
    private nextId = 0;

    constructor(options: NreplClientOptions = { port: 3447 }) {
        this.port = options.port;
        this.host = options.host ?? "localhost";
        this.timeoutMs = options.timeoutMs ?? 30_000;
        this.maxSessions = options.maxSessions ?? 16;
    }

    /**
     * Evaluates Clojure code in the persistent default session.
     *
     * @throws Error when the code throws, with the exception and stderr in the message
     */
    async eval(code: string): Promise<NreplEvalResult> {
        this.logger.debug("Evaluating Clojure expression: %s", code);
        const result = await this.evaluate(code);
        if (result.ex !== undefined) {
            throw new Error(`nREPL evaluation error: ${result.ex}${result.err ? "\n" + result.err : ""}`);
        }
        return { values: result.values, out: result.out, err: result.err, ns: result.ns };
    }

    /**
     * Evaluates a ClojureScript expression via the shadow-cljs CLJS eval API.
     *
     * The expression is wrapped in a call to `shadow.cljs.devtools.api/cljs-eval`
     * targeting the `:main` build, so it is evaluated in the browser runtime.
     *
     * @param cljsCode - the ClojureScript expression to evaluate
     * @returns the evaluation result
     */
    async evalCljs(cljsCode: string): Promise<NreplEvalResult> {
        // escape the CLJS code for embedding in a Clojure string
        const escapedCode = cljsCode.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
        const wrappedCode = `(shadow.cljs.devtools.api/cljs-eval :main "${escapedCode}" {})`;
        this.logger.debug("Evaluating CLJS expression via shadow-cljs: %s", cljsCode);
        return this.eval(wrappedCode);
    }

    /**
     * Evaluates code and returns the full result, also when the code throws.
     *
     * @throws NreplConnectError, NreplClosedError, NreplTimeoutError, or NreplCancelledError
     */
    async evaluate(code: string, options: NreplRequestOptions = {}): Promise<NreplResult> {
        return this.request({ op: "eval", code }, options);
    }

    /**
     * Loads a file's content with nREPL's `load-file` op, so compiler errors cite `filePath` and its lines,
     * whether or not the JVM can see that path.
     */
    async loadFile(
        content: string,
        filePath: string,
        fileName: string,
        options: NreplRequestOptions = {}
    ): Promise<NreplResult> {
        return this.request({ op: "load-file", file: content, "file-path": filePath, "file-name": fileName }, options);
    }

    /**
     * Closes the session kept under `key`, if any.
     */
    async closeSession(key: string): Promise<void> {
        const session = this.sessions.get(key);
        if (session === undefined) return;
        this.sessions.delete(key);
        await this.closeSessionId(await session.catch(() => null));
    }

    /**
     * Closes the connection and forgets every session, releasing all resources.
     */
    async close(): Promise<void> {
        if (this.socket) {
            this.logger.info("Closing nREPL connection to port %d", this.port);
            this.socket.end();
            this.socket.destroy();
        }
        this.resetConnection(new NreplClosedError("the client closed the connection"));
    }

    private async request(msg: Reply, options: NreplRequestOptions): Promise<NreplResult> {
        const deadline = Date.now() + (options.timeoutMs ?? this.timeoutMs);
        if (options.signal?.aborted) throw new NreplCancelledError("cancelled before it started");
        await this.connect();
        const session = await this.session(options.session ?? "default");

        const message: Reply = { ...msg, session };
        if (options.ns !== undefined) message.ns = options.ns;
        if (options.quota !== undefined) message["nrepl.middleware.print/quota"] = options.quota;

        const result: NreplResult = {
            values: [],
            out: "",
            err: "",
            ns: options.ns ?? "user",
            transcript: "",
            failed: false,
            interrupted: false,
            truncated: false,
            namespaceNotFound: false,
        };
        let atLineStart = true;
        const append = (text: string) => {
            result.transcript += text;
            if (text !== "") atLineStart = text.endsWith("\n");
        };

        const { id, done } = this.send(message, (reply) => {
            if (typeof reply.out === "string") {
                result.out += reply.out;
                append(reply.out);
                options.onOut?.(reply.out);
            }
            if (typeof reply.err === "string") {
                result.err += reply.err;
                append(reply.err);
            }
            if (typeof reply.value === "string") {
                result.values.push(reply.value);
                append((atLineStart ? "" : "\n") + reply.value + "\n");
            }
            if (typeof reply.ns === "string") result.ns = reply.ns;
            if (typeof reply.ex === "string") {
                result.ex = reply.ex;
                result.failed = true;
            }
            const status = Array.isArray(reply.status) ? reply.status.map(String) : [];
            if (status.includes("nrepl.middleware.print/truncated")) result.truncated = true;
            if (status.includes("interrupted")) result.interrupted = true;
            // nREPL reports namespace-not-found as an error status with no ex
            if (status.includes("error")) result.failed = true;
            if (status.includes("namespace-not-found")) result.namespaceNotFound = true;
        });

        let timer: NodeJS.Timeout | undefined;
        let onAbort: (() => void) | undefined;
        const stop = new Promise<"deadline" | "abort">((resolve) => {
            timer = setTimeout(() => resolve("deadline"), Math.max(0, deadline - Date.now()));
            onAbort = () => resolve("abort");
            options.signal?.addEventListener("abort", onAbort, { once: true });
        });
        try {
            const first = await Promise.race([done.then(() => "done" as const), stop]);
            if (first === "done") return result;
            await this.interrupt(session, id, done);
            if (first === "abort") throw new NreplCancelledError("cancelled; the evaluation was interrupted");
            throw new NreplTimeoutError(
                `no answer within ${Math.round((options.timeoutMs ?? this.timeoutMs) / 1000)} s; the evaluation was interrupted`
            );
        } finally {
            clearTimeout(timer);
            if (onAbort) options.signal?.removeEventListener("abort", onAbort);
            this.pending.delete(id);
        }
    }

    /** Sends an interrupt for request `id` and waits briefly for that request to end. */
    private async interrupt(session: string, id: string, done: Promise<void>): Promise<void> {
        try {
            const { done: interrupted } = this.send({ op: "interrupt", session, "interrupt-id": id }, () => undefined);
            await withTimeout(Promise.all([interrupted, done]), CONTROL_TIMEOUT_MS);
        } catch {
            // best effort: the connection may be gone, or the evaluation ignores the interrupt
        }
    }

    /**
     * Writes one message. `done` resolves on the reply whose status contains "done", and rejects with
     * NreplClosedError when the connection closes first.
     */
    private send(message: Reply, onReply: (reply: Reply) => void): { id: string; done: Promise<void> } {
        const socket = this.socket;
        const id = `mcp-${process.pid}-${++this.nextId}`;
        const done = new Promise<void>((resolve, reject) => {
            if (socket === null || socket.destroyed) {
                reject(new NreplClosedError("the connection closed before the request was sent"));
                return;
            }
            this.pending.set(id, {
                onReply: (reply) => {
                    onReply(reply);
                    const status = Array.isArray(reply.status) ? reply.status.map(String) : [];
                    if (status.includes("done")) {
                        this.pending.delete(id);
                        resolve();
                    }
                },
                onClose: reject,
            });
            socket.write(encode({ ...message, id }));
        });
        // a rejection nobody awaits yet (the request awaits `done` only after racing) must not crash the process
        done.catch(() => undefined);
        return { id, done };
    }

    /** The session id kept under `key`, cloning one when there is none. */
    private async session(key: string): Promise<string> {
        let session = this.sessions.get(key);
        if (session !== undefined) {
            this.sessions.delete(key); // re-inserted below, as most recently used
        } else {
            session = this.clone();
            session.catch(() => this.sessions.delete(key));
        }
        this.sessions.set(key, session);
        while (this.sessions.size > this.maxSessions) {
            const [oldest] = this.sessions.keys();
            void this.closeSession(oldest);
        }
        return session;
    }

    private async clone(): Promise<string> {
        let session: string | undefined;
        const { done } = this.send({ op: "clone" }, (reply) => {
            if (typeof reply["new-session"] === "string") session = reply["new-session"];
        });
        await withTimeout(done, CONTROL_TIMEOUT_MS);
        if (session === undefined) throw new Error("nREPL clone response did not contain a new session id");
        this.logger.info("Cloned nREPL session %s on port %d", session, this.port);
        return session;
    }

    private async closeSessionId(session: string | null): Promise<void> {
        if (session === null || this.socket === null) return;
        try {
            const { done } = this.send({ op: "close", session }, () => undefined);
            await withTimeout(done, CONTROL_TIMEOUT_MS);
        } catch {
            // best effort
        }
    }

    /** Opens the connection unless it is open. */
    private async connect(): Promise<net.Socket> {
        if (this.socket && !this.socket.destroyed) return this.socket;
        if (this.connecting) return this.connecting;
        this.logger.info("Connecting to nREPL server at %s:%d", this.host, this.port);
        this.connecting = new Promise<net.Socket>((resolve, reject) => {
            const socket = net.connect({ host: this.host, port: this.port });
            const fail = (error: Error) => {
                socket.destroy();
                reject(new NreplConnectError(`nothing listens on ${this.host}:${this.port} (${error.message})`));
            };
            socket.setTimeout(CONNECT_TIMEOUT_MS, () => fail(new Error("timed out")));
            socket.once("error", fail);
            socket.once("connect", () => {
                socket.setTimeout(0);
                socket.removeListener("error", fail);
                socket.on("error", (error) => this.logger.warn("nREPL connection error: %s", error.message));
                socket.on("data", (chunk: Buffer) => this.receive(chunk));
                socket.once("close", () => {
                    if (this.socket === socket) {
                        this.logger.warn("nREPL connection to port %d closed", this.port);
                        this.resetConnection(
                            new NreplClosedError("the connection closed before the evaluation finished")
                        );
                    }
                });
                this.socket = socket;
                this.buffer = Buffer.alloc(0);
                resolve(socket);
            });
        }).finally(() => {
            this.connecting = null;
        });
        return this.connecting;
    }

    private receive(chunk: Buffer): void {
        this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
        let offset = 0;
        try {
            for (;;) {
                const decoded = decode(this.buffer, offset);
                if (decoded === null) break;
                offset = decoded.end;
                const reply = decoded.value as Reply;
                const handler = typeof reply.id === "string" ? this.pending.get(reply.id) : undefined;
                handler?.onReply(reply);
            }
        } catch (error) {
            this.logger.error("Cannot decode nREPL reply: %s", error);
            this.socket?.destroy();
            return;
        }
        this.buffer = this.buffer.subarray(offset);
    }

    private resetConnection(error: Error): void {
        this.socket = null;
        this.buffer = Buffer.alloc(0);
        this.sessions.clear();
        const pending = [...this.pending.values()];
        this.pending.clear();
        for (const p of pending) p.onClose(error);
    }
}

async function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    try {
        return await Promise.race([
            promise,
            new Promise<never>((_, reject) => {
                timer = setTimeout(() => reject(new Error(`no answer within ${ms} ms`)), ms);
            }),
        ]);
    } finally {
        clearTimeout(timer);
    }
}
