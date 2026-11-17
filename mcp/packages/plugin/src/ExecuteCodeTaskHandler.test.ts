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

function installPenpot(options: { waitForSave?: (() => Promise<void>) | null; workspaceStatus?: string }): {
    messages: TaskResponse[];
} {
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
    (globalThis as any).penpotMgmt =
        options.workspaceStatus === undefined ? undefined : { workspace: { status: options.workspaceStatus } };
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
    await assert.rejects(handler.handle(task), /could not be saved/i);
    assert.equal(messages.length, 0, "must not report success");
});

test("hard-fails when waitForSave is missing", async () => {
    const { messages } = installPenpot({ waitForSave: null });

    const { ExecuteCodeTaskHandler, Task } = loadHandler();
    const handler = new ExecuteCodeTaskHandler();
    const task = new Task("req-3", "executeCode", { code: "return 1;" });
    await assert.rejects(handler.handle(task), /waitForSave/);
    assert.equal(messages.length, 0, "must not report success");
});

for (const workspaceStatus of ["none", "loading"]) {
    test(`skips waitForSave when the workspace is ${workspaceStatus}`, async () => {
        let waitForSaveCalls = 0;
        const { messages } = installPenpot({
            workspaceStatus,
            waitForSave: async () => {
                waitForSaveCalls += 1;
                throw new Error("Changes could not be saved");
            },
        });

        const { ExecuteCodeTaskHandler, Task } = loadHandler();
        const handler = new ExecuteCodeTaskHandler();
        const task = new Task("req-4", "executeCode", { code: "return 7;" });
        await handler.handle(task);

        assert.equal(waitForSaveCalls, 0, "no workspace edit can be pending");
        const response = lastResponse(messages);
        assert.equal(response.success, true);
        assert.equal((response.data as any).result, 7);
    });
}

test("awaits waitForSave when the workspace is ready", async () => {
    const { messages } = installPenpot({
        workspaceStatus: "ready",
        waitForSave: async () => {
            throw new Error("Changes could not be saved");
        },
    });

    const { ExecuteCodeTaskHandler, Task } = loadHandler();
    const handler = new ExecuteCodeTaskHandler();
    const task = new Task("req-5", "executeCode", { code: "return 1;" });
    await assert.rejects(handler.handle(task), /could not be saved/i);
    assert.equal(messages.length, 0, "must not report success");
});
