import { Task, TaskHandler } from "../TaskHandler";
import { ExecuteCodeTaskParams, ExecuteCodeTaskResultData } from "../../../common/src";
import { PenpotUtils } from "../PenpotUtils.ts";
import { ExecuteCodeTaskConsole } from "../ExecuteCodeTaskConsole.ts";

/**
 * Task handler for executing JavaScript code in the plugin context.
 *
 * Maintains a persistent context object that preserves state between code executions
 * and captures all console output during execution.
 */
export class ExecuteCodeTaskHandler extends TaskHandler<ExecuteCodeTaskParams> {
    readonly taskType = "executeCode";

    /**
     * Persistent context object that maintains state between code executions.
     * Contains the penpot API, storage object, and custom console implementation.
     */
    private readonly context: any;

    constructor() {
        super();

        // initialize context, making penpot, penpotUtils, storage and the custom console available
        this.context = {
            penpot: penpot,
            penpotMgmt: typeof penpotMgmt === "undefined" ? null : penpotMgmt,
            storage: {},
            console: new ExecuteCodeTaskConsole(),
            penpotUtils: PenpotUtils,
        };
    }

    async handle(task: Task<ExecuteCodeTaskParams>): Promise<void> {
        if (!task.params.code) {
            task.sendError("executeCode task requires 'code' parameter");
            return;
        }

        this.context.console.resetLog();

        const context = this.context;
        const code = task.params.code;

        // set the flags naturalChildOrdering and throwValidationErrors to true during code execution.
        let originalNaturalChildOrdering: any, originalThrowValidationErrors: any;
        if (penpot.flags) {
            originalNaturalChildOrdering = penpot.flags.naturalChildOrdering;
            penpot.flags.naturalChildOrdering = true;
            originalThrowValidationErrors = penpot.flags.throwValidationErrors;
            penpot.flags.throwValidationErrors = true;
        } else {
            throw new Error(
                "You are using a version of the Penpot MCP server which is incompatible " +
                    "with the connected Penpot version. " +
                    "Read the documentation for instructions on which version to use: " +
                    "https://github.com/penpot/penpot/tree/develop/mcp\n" +
                    "If you are an LLM, tell the user about this!"
            );
        }

        let result: any;
        try {
            // wait for layout updates prior to executing the supplied code (if method is available)
            try {
                const management = this.context.penpotMgmt;
                // @ts-ignore - TODO Penpot.waitForLayoutUpdate is not yet in the released types
                if ((!management || management.workspace.status === "ready") && penpot.waitForLayoutUpdate) {
                    // @ts-ignore
                    await penpot.waitForLayoutUpdate();
                }
            } catch (e) {
                console.error("Error waiting for layout update:", e);
            }

            // execute the code in an async function with the context variables as parameters
            result = await (async (ctx) => {
                const fn = new Function(...Object.keys(ctx), `return (async () => { ${code} })();`);
                return fn(...Object.values(ctx));
            })(context);
        } finally {
            // restore the original value of the flags
            penpot.flags.naturalChildOrdering = originalNaturalChildOrdering;
            penpot.flags.throwValidationErrors = originalThrowValidationErrors;
        }

        console.log("Code execution result:", result);

        // transform a top-level Uint8Array result into a compact base64 envelope to avoid the
        // ~10x JSON expansion that occurs when JSON.stringify serializes typed arrays as objects
        // with numeric string keys (see penpot/penpot#9420)
        if (result instanceof Uint8Array) {
            result = ExecuteCodeTaskHandler.encodeBytesAsBase64Envelope(result);
        }

        // return result and captured log
        let resultData: ExecuteCodeTaskResultData<any> = {
            result: result,
            log: this.context.console.getLog(),
        };
        task.sendSuccess(resultData);
    }

    /**
     * Base64-encodes the given bytes and wraps the result in a tagged envelope that the
     * server side recognizes (see `ImageContent.byteData`).
     *
     * @param bytes - the raw binary data to encode
     * @returns an envelope of the form `{ __type: "base64", data: <base64 string> }`
     */
    private static encodeBytesAsBase64Envelope(bytes: Uint8Array): { __type: "base64"; data: string } {
        // build the binary string in chunks; calling `String.fromCharCode(...bytes)` directly
        // would overflow the call stack for large arrays
        const chunkSize = 0x8000;
        let binary = "";
        for (let i = 0; i < bytes.length; i += chunkSize) {
            const chunk = bytes.subarray(i, i + chunkSize);
            binary += String.fromCharCode.apply(null, chunk as unknown as number[]);
        }
        return { __type: "base64", data: btoa(binary) };
    }
}
