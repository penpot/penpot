import nreplClient from "nrepl-client";
import type { NreplConnection, NreplMessage } from "nrepl-client";
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
 * A client for communicating with a shadow-cljs nREPL server.
 *
 * This client maintains a persistent nREPL session, so that definitions,
 * requires, and other state are preserved across evaluations — providing
 * a full REPL experience.
 */
export class NreplClient {
    private static readonly NREPL_PORT = 3447;
    private static readonly NREPL_HOST = "localhost";
    private static readonly EVAL_TIMEOUT_MS = 30_000;

    private readonly logger = createLogger("NreplClient");

    /** the persistent connection to the nREPL server, established lazily */
    private connection: NreplConnection | null = null;

    /** the cloned session ID that persists state across evaluations */
    private sessionId: string | null = null;

    /**
     * Evaluates a Clojure expression on the nREPL server within the persistent session.
     *
     * @param code - the Clojure expression to evaluate
     * @returns the evaluation result
     */
    async eval(code: string): Promise<NreplEvalResult> {
        this.logger.debug("Evaluating Clojure expression: %s", code);
        const conn = await this.ensureConnection();
        const sessionId = await this.ensureSession(conn);

        return new Promise<NreplEvalResult>((resolve, reject) => {
            const timeout = setTimeout(() => {
                reject(new Error(`nREPL evaluation timed out after ${NreplClient.EVAL_TIMEOUT_MS}ms`));
            }, NreplClient.EVAL_TIMEOUT_MS);

            conn.send({ op: "eval", code, session: sessionId }, (err: Error | null, result: NreplMessage[]) => {
                clearTimeout(timeout);
                if (err) {
                    reject(err);
                    return;
                }
                try {
                    resolve(this.parseEvalResult(result));
                } catch (parseErr) {
                    reject(parseErr);
                }
            });
        });
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
     * Closes the persistent connection and session, releasing all resources.
     */
    async close(): Promise<void> {
        if (this.connection) {
            this.logger.info("Closing nREPL connection");
            this.connection.end();
            this.connection = null;
            this.sessionId = null;
        }
    }

    /**
     * Ensures a connection to the nREPL server is established, creating one if necessary.
     *
     * If the existing connection has been closed or errored, a new one is created.
     */
    private async ensureConnection(): Promise<NreplConnection> {
        if (this.connection && !this.connection.destroyed) {
            return this.connection;
        }

        // reset state since the old connection is gone
        this.connection = null;
        this.sessionId = null;

        this.logger.info("Connecting to nREPL server at %s:%d", NreplClient.NREPL_HOST, NreplClient.NREPL_PORT);

        return new Promise<NreplConnection>((resolve, reject) => {
            const conn = nreplClient.connect({
                port: NreplClient.NREPL_PORT,
                host: NreplClient.NREPL_HOST,
            });

            conn.once("connect", () => {
                this.connection = conn;

                // handle unexpected disconnects so the next eval reconnects
                conn.once("close", () => {
                    this.logger.warn("nREPL connection closed unexpectedly");
                    this.connection = null;
                    this.sessionId = null;
                });

                conn.once("error", (err: Error) => {
                    this.logger.error("nREPL connection error: %s", err);
                    this.connection = null;
                    this.sessionId = null;
                });

                resolve(conn);
            });

            conn.once("error", (err: Error) => {
                reject(
                    new Error(
                        `Failed to connect to nREPL server at ${NreplClient.NREPL_HOST}:${NreplClient.NREPL_PORT}: ${err.message}`
                    )
                );
            });
        });
    }

    /**
     * Ensures a persistent nREPL session exists, cloning one from the server if necessary.
     *
     * A cloned session maintains its own state (namespace bindings, definitions, etc.)
     * independently of other sessions.
     */
    private async ensureSession(conn: NreplConnection): Promise<string> {
        if (this.sessionId) {
            return this.sessionId;
        }

        this.logger.info("Cloning new nREPL session");

        return new Promise<string>((resolve, reject) => {
            conn.clone((err: Error | null, result: NreplMessage[]) => {
                if (err) {
                    reject(new Error(`Failed to clone nREPL session: ${err.message}`));
                    return;
                }

                const sessionMsg = result.find((msg) => msg["new-session"] !== undefined) as any;
                if (!sessionMsg) {
                    reject(new Error("nREPL clone response did not contain a new session ID"));
                    return;
                }

                this.sessionId = sessionMsg["new-session"];
                this.logger.info("Cloned nREPL session: %s", this.sessionId);
                resolve(this.sessionId!);
            });
        });
    }

    /**
     * Parses the raw nREPL response messages into a structured result.
     */
    private parseEvalResult(messages: NreplMessage[]): NreplEvalResult {
        const values: string[] = [];
        const outParts: string[] = [];
        const errParts: string[] = [];
        let ns = "user";

        for (const msg of messages) {
            if (msg.value !== undefined) {
                values.push(msg.value);
            }
            if (msg.out) {
                outParts.push(msg.out);
            }
            if (msg.err) {
                errParts.push(msg.err);
            }
            if (msg.ns) {
                ns = msg.ns;
            }
            if (msg.ex) {
                throw new Error(`nREPL evaluation error: ${msg.ex}${msg.err ? "\n" + msg.err : ""}`);
            }
        }

        return {
            values,
            out: outParts.join(""),
            err: errParts.join(""),
            ns,
        };
    }
}
