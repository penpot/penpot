import { z } from "zod";
import { Tool } from "../../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../../ToolResponse";
import { TextResponse } from "../../ToolResponse";
import type { PenpotMcpServer } from "../../PenpotMcpServer";
import { REPO_ROOT, ScriptRunner, TEST_REPL_SCRIPT } from "./runScript";

/**
 * Arguments for the CljTestTool.
 */
export class CljTestArgs {
    static schema = {
        ids: z
            .array(z.string().min(1))
            .optional()
            .describe(
                "Namespace or namespace/var symbols, e.g. backend-tests.db-test or " +
                    "backend-tests.db-test/some-test. Omit to run the whole backend suite."
            ),
    };

    /**
     * Test targets: namespaces or namespace/var symbols; empty runs the whole suite.
     */
    ids?: string[];
}

/**
 * Runs backend tests in the warm test JVM.
 *
 * The tool shells out to `backend/scripts/test-repl` and reports its exit status
 * with the captured output. It keeps no JVM of its own.
 */
export class CljTestTool extends Tool<CljTestArgs> {
    private readonly runner = new ScriptRunner(REPO_ROOT);

    /**
     * @param mcpServer - the MCP server instance
     */
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, CljTestArgs.schema);
    }

    public getToolName(): string {
        return "clj_test";
    }

    public getToolDescription(): string {
        return (
            "Runs backend tests in the warm test JVM, reloading changed namespaces first. " +
            "Use this instead of `clojure -M:dev:test` for the edit-and-rerun loop: runs take turns " +
            "on the shared test database. For CI parity (random order, seed replay) use " +
            "`scripts/ci backend --test`. Without ids it runs the whole backend suite."
        );
    }

    protected async executeCore(args: CljTestArgs): Promise<ToolResponse> {
        const result = await this.runner.run(TEST_REPL_SCRIPT, ["test", ...(args.ids ?? [])]);

        return new TextResponse(this.runner.format(result, { success: "PASS", failure: "FAIL" }));
    }
}
