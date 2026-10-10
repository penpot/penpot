import { z } from "zod";
import "reflect-metadata";
import { Tool, type ToolCallContext } from "../Tool";
import type { ToolResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import type { ClojureRuntime } from "../clojure/ClojureRuntime";

/**
 * Arguments for the ClojureTestTool.
 */
export class ClojureTestArgs {
    static schema = {
        ids: z
            .array(z.string().min(1))
            .optional()
            .describe(
                "kaocha --focus ids: namespaces, ns/vars, or suite keywords such as :unit. Omit for the whole suite."
            ),
        seed: z
            .number()
            .int()
            .min(0)
            .max(2147483647)
            .optional()
            .describe("kaocha seed: replays the test order of an earlier run with the same ids."),
        timeout_s: z
            .number()
            .positive()
            .optional()
            .describe("Seconds before the run stops (default 300 with ids, 1800 without)."),
        restart: z
            .boolean()
            .optional()
            .describe("Restart the test JVM before running, for example after a deps.edn change."),
    };

    ids?: string[];
    seed?: number;
    timeout_s?: number;
    restart?: boolean;
}

/**
 * Runs backend Clojure tests in the persistent test JVM.
 */
export class ClojureTestTool extends Tool<ClojureTestArgs> {
    constructor(
        mcpServer: PenpotMcpServer,
        private readonly runtime: ClojureRuntime
    ) {
        super(mcpServer, ClojureTestArgs.schema);
    }

    public getToolName(): string {
        return "clojure_test";
    }

    public getToolDescription(): string {
        const d = this.runtime.defaults;
        return [
            "Run backend Clojure tests (kaocha) in the devenv's persistent test JVM: a warm run takes under a second, a cold `clojure -M:dev:test --focus …` 16 to 18 s. " +
                "Each run first reloads the namespaces whose files changed on disk, so edit and run again. " +
                "The first call starts the JVM (about 15 s); it stops after 2 h without a call.",
            `ids: what kaocha's --focus takes, a namespace (backend-tests.rpc-doc-test), a var (backend-tests.rpc-doc-test/some-test), or a suite keyword (:unit). ` +
                `timeout_s: default ${d.testTimeoutS} with ids, ${d.suiteTimeoutS} without; no test starts after it. ` +
                "seed: the first line shows each run's seed, and the same seed with the same ids replays the order. restart: restart the test JVM first.",
            "The first line is the verdict with the exit code, the time, and the seed: PASS (0). FAIL (1): failures follow, first ones first. NO MATCH (1). " +
                "RELOAD FAILED (2): a file did not load and no test ran. JVM DIED (3): the JVM's log tail follows. BUSY (4): another call holds the test JVM. " +
                "TIMEOUT (4): results so far follow. CANCELLED (4). ERROR: the line says why. A stopped JVM is started, and a stale one restarted, once per call.",
            "For a whole-suite run before a commit, run the suite cold as CI does, in the devenv container: `cd ~/penpot && scripts/ci --test backend`. " +
                "It shares the test database with this JVM, so not while a clojure_test call runs.",
        ].join("\n\n");
    }

    protected async executeCore(args: ClojureTestArgs, context: ToolCallContext): Promise<ToolResponse> {
        return this.runtime.runTests(args, context);
    }
}
