import { Task, TaskHandler } from "../TaskHandler";
import { ExecuteCodeTaskParams, ExecuteCodeTaskResultData } from "../../../common/src";
import { PenpotUtils } from "../PenpotUtils.ts";

/**
 * Console implementation that captures all log output for code execution.
 *
 * Provides the same interface as the native console object but appends
 * all output to an internal log string that can be retrieved.
 */
class ExecuteCodeTaskConsole {
    /**
     * Accumulated log output from all console method calls.
     */
    private logOutput: string = "";

    /**
     * Resets the accumulated log output to empty string.
     * Should be called before each code execution to start with clean logs.
     */
    resetLog(): void {
        this.logOutput = "";
    }

    /**
     * Gets the accumulated log output from all console method calls.
     * @returns The complete log output as a string
     */
    getLog(): string {
        return this.logOutput;
    }

    /**
     * Appends a formatted message to the log output.
     * @param level - Log level prefix (e.g., "LOG", "WARN", "ERROR")
     * @param args - Arguments to log, will be stringified and joined
     */
    private appendToLog(level: string, ...args: any[]): void {
        const message = args
            .map((arg) => (typeof arg === "object" ? JSON.stringify(arg, null, 2) : String(arg)))
            .join(" ");
        this.logOutput += `[${level}] ${message}\n`;
    }

    /**
     * Logs a message to the captured output.
     */
    log(...args: any[]): void {
        this.appendToLog("LOG", ...args);
    }

    /**
     * Logs a warning message to the captured output.
     */
    warn(...args: any[]): void {
        this.appendToLog("WARN", ...args);
    }

    /**
     * Logs an error message to the captured output.
     */
    error(...args: any[]): void {
        this.appendToLog("ERROR", ...args);
    }

    /**
     * Logs an informational message to the captured output.
     */
    info(...args: any[]): void {
        this.appendToLog("INFO", ...args);
    }

    /**
     * Logs a debug message to the captured output.
     */
    debug(...args: any[]): void {
        this.appendToLog("DEBUG", ...args);
    }

    /**
     * Logs a message with trace information to the captured output.
     */
    trace(...args: any[]): void {
        this.appendToLog("TRACE", ...args);
    }

    /**
     * Logs a table to the captured output (simplified as JSON).
     */
    table(data: any): void {
        this.appendToLog("TABLE", data);
    }

    /**
     * Starts a timer (simplified implementation that just logs).
     */
    time(label?: string): void {
        this.appendToLog("TIME", `Timer started: ${label || "default"}`);
    }

    /**
     * Ends a timer (simplified implementation that just logs).
     */
    timeEnd(label?: string): void {
        this.appendToLog("TIME_END", `Timer ended: ${label || "default"}`);
    }

    /**
     * Logs messages in a group (simplified to just log the label).
     */
    group(label?: string): void {
        this.appendToLog("GROUP", label || "");
    }

    /**
     * Logs messages in a collapsed group (simplified to just log the label).
     */
    groupCollapsed(label?: string): void {
        this.appendToLog("GROUP_COLLAPSED", label || "");
    }

    /**
     * Ends the current group (simplified implementation).
     */
    groupEnd(): void {
        this.appendToLog("GROUP_END", "");
    }

    /**
     * Clears the console (no-op in this implementation since we want to capture logs).
     */
    clear(): void {
        // intentionally empty - we don't want to clear captured logs
    }

    /**
     * Counts occurrences of calls with the same label (simplified implementation).
     */
    count(label?: string): void {
        this.appendToLog("COUNT", label || "default");
    }

    /**
     * Resets the count for a label (simplified implementation).
     */
    countReset(label?: string): void {
        this.appendToLog("COUNT_RESET", label || "default");
    }

    /**
     * Logs an assertion (simplified to just log if condition is false).
     */
    assert(condition: boolean, ...args: any[]): void {
        if (!condition) {
            this.appendToLog("ASSERT", ...args);
        }
    }
}

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

        let result: any = await (async (ctx) => {
            const fn = new Function(...Object.keys(ctx), `return (async () => { ${code} })();`);
            return fn(...Object.values(ctx));
        })(context);

        console.log("Code execution result:", result);

        // return result and captured log
        let resultData: ExecuteCodeTaskResultData<any> = {
            result: result,
            log: this.context.console.getLog(),
        };
        task.sendSuccess(resultData);
    }
}
