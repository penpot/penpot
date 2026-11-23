import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createContext, runInContext, type Context } from "node:vm";
import ts from "typescript";
import * as ErrorUtils from "./ErrorUtils.ts";
import * as VersionCompatibility from "./VersionCompatibility.ts";

/** Compiles a plugin source file for a simulated plugin context. */
function compile(name: string) {
    return ts.transpileModule(readFileSync(new URL(name, import.meta.url), "utf8"), {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText;
}

/** Exports of a compiled module run in the given context, which it shares with the plugin bundle. */
function compiledModule(name: string, context: Context) {
    const exports = {};
    context.exports = exports;
    runInContext(compile(name), context);
    return exports;
}

/**
 * Plugin task request handling with a task that waits until the test releases it.
 *
 * Closing the plugin replaces the plugin globals with `undefined`, as the plugin runtime does
 * when it closes a plugin: SES reads a removed global as `undefined` instead of throwing.
 */
function pluginWithPendingTask(outcome: "success" | "failure") {
    const responses: any[] = [];
    const errors: unknown[][] = [];
    let release!: () => void;
    const released = new Promise<void>((resolve) => {
        release = resolve;
    });
    let onMessage!: (message: any) => void;
    const context: Context = createContext({
        console: {
            log() {},
            error: (...args: unknown[]) => {
                errors.push(args);
            },
            warn() {},
            info() {},
        },
        penpot: {
            theme: "dark",
            ui: {
                open() {},
                onMessage: (handler: (message: any) => void) => {
                    onMessage = handler;
                },
                sendMessage: (message: any) => {
                    if (message.type === "task-response") {
                        responses.push(message.response);
                    }
                },
            },
            on() {},
        },
        mcp: undefined,
    });
    const taskModule = compiledModule("TaskHandler.ts", context);
    context.require = (name: string) => {
        if (name === "./task-handlers/ExecuteCodeTaskHandler") {
            return {
                ExecuteCodeTaskHandler: class {
                    isApplicableTo(task: any) {
                        return task.taskType === "executeCode";
                    }
                    async handle(task: any) {
                        await released;
                        if (outcome === "failure") {
                            throw new Error("code failed");
                        }
                        task.sendSuccess({ result: 1 });
                    }
                },
            };
        }
        if (name === "./TaskHandler") return taskModule;
        if (name === "./ErrorUtils") return ErrorUtils;
        if (name === "./VersionCompatibility") return VersionCompatibility;
        if (name === "./RollbackLog") {
            return {
                LocalStorageRollbackStore: class {},
                RollbackLog: class {
                    listen() {}
                    pending() {
                        return [];
                    }
                    acknowledge() {}
                },
            };
        }
        throw new Error(`Unexpected import: ${name}`);
    };
    context.exports = {};
    runInContext(compile("plugin.ts"), context);
    return {
        responses,
        errors,
        start: () => onMessage({ id: "task-1", task: "executeCode", params: { code: "" } }),
        close: () => {
            for (const name of ["console", "penpot", "penpotMgmt", "mcp"]) {
                context[name] = undefined;
            }
        },
        release,
    };
}

/** Unhandled rejections raised while the given action and its follow-up work run. */
async function unhandledRejections(action: () => void | Promise<void>): Promise<unknown[]> {
    const rejections: unknown[] = [];
    const listener = (reason: unknown) => rejections.push(reason);
    process.on("unhandledRejection", listener);
    try {
        await action();
        await new Promise((resolve) => setTimeout(resolve, 20));
    } finally {
        process.off("unhandledRejection", listener);
    }
    return rejections;
}

for (const outcome of ["success", "failure"] as const) {
    test(`a task ending in ${outcome} after the plugin closed raises no error`, async () => {
        const plugin = pluginWithPendingTask(outcome);
        const rejections = await unhandledRejections(() => {
            plugin.start();
            plugin.close();
            plugin.release();
        });
        assert.deepEqual(rejections, []);
        assert.deepEqual(plugin.responses, []);
        assert.deepEqual(plugin.errors, []);
    });
}

test("a task failing while the plugin is open sends an error response", async () => {
    const plugin = pluginWithPendingTask("failure");
    const rejections = await unhandledRejections(() => {
        plugin.start();
        plugin.release();
    });
    assert.deepEqual(rejections, []);
    assert.equal(plugin.responses.length, 1);
    assert.equal(plugin.responses[0].success, false);
    assert.match(plugin.responses[0].error, /code failed/);
});
