import nreplClient from "nrepl-client";
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
 * This client wraps the nrepl-client library, providing a typed, promise-based
 * interface for evaluating Clojure and ClojureScript expressions.
 */
export class NreplClient {
    private static readonly NREPL_PORT = 3447;
    private static readonly NREPL_HOST = "localhost";
    private static readonly EVAL_TIMEOUT_MS = 30_000;

    private readonly logger = createLogger("NreplClient");

    /**
     * Evaluates a Clojure expression on the nREPL server.
     *
     * A new connection is established for each evaluation and closed afterwards.
     *
     * @param code - the Clojure expression to evaluate
     * @returns the evaluation result
     */
    async eval(code: string): Promise<NreplEvalResult> {
        this.logger.debug("Evaluating Clojure expression: %s", code);
        return this.withConnection((conn) => {
            return new Promise<NreplEvalResult>((resolve, reject) => {
                const timeout = setTimeout(() => {
                    reject(new Error(`nREPL evaluation timed out after ${NreplClient.EVAL_TIMEOUT_MS}ms`));
                }, NreplClient.EVAL_TIMEOUT_MS);

                conn.eval(code, (err: Error | null, result: any[]) => {
                    clearTimeout(timeout);
                    if (err) {
                        reject(err);
                        return;
                    }
                    resolve(this.parseEvalResult(result));
                });
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
     * Opens a connection, executes the given operation, and ensures the connection is closed afterwards.
     */
    private async withConnection<T>(operation: (conn: any) => Promise<T>): Promise<T> {
        const conn = nreplClient.connect({
            port: NreplClient.NREPL_PORT,
            host: NreplClient.NREPL_HOST,
        });

        return new Promise<T>((resolve, reject) => {
            conn.once("connect", async () => {
                try {
                    const result = await operation(conn);
                    resolve(result);
                } catch (err) {
                    reject(err);
                } finally {
                    conn.end();
                }
            });

            conn.once("error", (err: Error) => {
                this.logger.error("nREPL connection error: %s", err);
                reject(
                    new Error(
                        `Failed to connect to nREPL server at ${NreplClient.NREPL_HOST}:${NreplClient.NREPL_PORT}: ${err.message}`
                    )
                );
            });
        });
    }

    /**
     * Parses the raw nREPL response messages into a structured result.
     */
    private parseEvalResult(messages: any[]): NreplEvalResult {
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
