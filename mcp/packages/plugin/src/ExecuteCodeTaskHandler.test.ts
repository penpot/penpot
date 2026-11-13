import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";
import * as PenpotUtils from "./PenpotUtils.ts";
import { ExecuteCodeTaskConsole } from "./ExecuteCodeTaskConsole.ts";

type TaskResponse = {
    type: string;
    response: {
        id: string;
        success: boolean;
        data?: unknown;
        error?: string;
    };
};

/** Compiles a TypeScript source file for a simulated plugin context. */
function compile(name: string) {
    return ts.transpileModule(readFileSync(new URL(name, import.meta.url), "utf8"), {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText;
}

/**
 * Loads Task + ExecuteCodeTaskHandler with `penpot` / `penpotMgmt` resolved from
 * the outer globalThis (so installPenpot can configure them before construction).
 */
function loadHandler(): { ExecuteCodeTaskHandler: any; Task: any } {
    const taskExports: Record<string, unknown> = {};
    const handlerExports: Record<string, unknown> = {};

    const context: Record<string, unknown> = {
        console,
        exports: taskExports,
        require: (id: string) => {
            if (id === "../TaskHandler" || id.endsWith("/TaskHandler")) {
                return taskExports;
            }
            if (id.includes("common/src")) {
                return {};
            }
            if (id.includes("PenpotUtils")) {
                return PenpotUtils;
            }
            if (id.includes("ExecuteCodeTaskConsole")) {
                return { ExecuteCodeTaskConsole };
            }
            throw new Error(`Unexpected import: ${id}`);
        },
    };
    Object.defineProperty(context, "penpot", {
        enumerable: true,
        get: () => (globalThis as any).penpot,
    });
    Object.defineProperty(context, "penpotMgmt", {
        enumerable: true,
        get: () => (globalThis as any).penpotMgmt,
    });

    runInNewContext(compile("TaskHandler.ts"), context);

    context.exports = handlerExports;
    runInNewContext(compile("task-handlers/ExecuteCodeTaskHandler.ts"), context);

    return {
        ExecuteCodeTaskHandler: handlerExports.ExecuteCodeTaskHandler,
        Task: taskExports.Task,
    };
}

function installPenpot(options: { waitForSave?: (() => Promise<void>) | null }): { messages: TaskResponse[] } {
    const messages: TaskResponse[] = [];
    const penpot: Record<string, unknown> = {
        flags: {
            naturalChildOrdering: false,
            throwValidationErrors: false,
        },
        waitForLayoutUpdate: async () => undefined,
        ui: {
            sendMessage: (message: TaskResponse) => {
                messages.push(message);
            },
        },
    };
    if (options.waitForSave !== null && options.waitForSave !== undefined) {
        penpot.waitForSave = options.waitForSave;
    }
    (globalThis as any).penpot = penpot;
    (globalThis as any).penpotMgmt = undefined;
    return { messages };
}

function lastResponse(messages: TaskResponse[]) {
    assert.ok(messages.length > 0, "expected a task response");
    return messages[messages.length - 1].response;
}

test("awaits waitForSave before reporting success", async () => {
    let resolveSave!: () => void;
    let waitForSaveCalls = 0;
    const saveGate = new Promise<void>((resolve) => {
        resolveSave = resolve;
    });
    const { messages } = installPenpot({
        waitForSave: async () => {
            waitForSaveCalls += 1;
            return saveGate;
        },
    });

    const { ExecuteCodeTaskHandler, Task } = loadHandler();
    const handler = new ExecuteCodeTaskHandler();
    const task = new Task("req-1", "executeCode", { code: "return 42;" });
    const done = handler.handle(task);

    await new Promise((r) => setTimeout(r, 20));
    assert.equal(messages.length, 0, "must not succeed before save settles");
    assert.equal(waitForSaveCalls, 1, "must call waitForSave after the code runs");

    resolveSave();
    await done;

    const response = lastResponse(messages);
    assert.equal(response.success, true);
    assert.equal((response.data as any).result, 42);
});

test("reports an error when waitForSave rejects", async () => {
    const { messages } = installPenpot({
        waitForSave: async () => {
            throw new Error("Changes could not be saved");
        },
    });

    const { ExecuteCodeTaskHandler, Task } = loadHandler();
    const handler = new ExecuteCodeTaskHandler();
    const task = new Task("req-2", "executeCode", { code: "return 1;" });
    try {
        await handler.handle(task);
        assert.fail("expected handle to throw");
    } catch (error) {
        task.sendError(`Error handling task: ${error instanceof Error ? error.message : String(error)}`);
    }

    const response = lastResponse(messages);
    assert.equal(response.success, false);
    assert.match(String(response.error), /could not be saved/i);
});

test("hard-fails when waitForSave is missing", async () => {
    const { messages } = installPenpot({ waitForSave: null });

    const { ExecuteCodeTaskHandler, Task } = loadHandler();
    const handler = new ExecuteCodeTaskHandler();
    const task = new Task("req-3", "executeCode", { code: "return 1;" });
    try {
        await handler.handle(task);
        assert.fail("expected handle to throw");
    } catch (error) {
        task.sendError(`Error handling task: ${error instanceof Error ? error.message : String(error)}`);
    }

    const response = lastResponse(messages);
    assert.equal(response.success, false);
    assert.match(String(response.error), /waitForSave|Penpot build|upgrade Penpot/i);
});
