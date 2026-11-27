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
    const pendingRollbacks = [{ id: "rollback-1" }];
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
                        return [...pendingRollbacks];
                    }
                    acknowledge(count: number) {
                        pendingRollbacks.splice(0, count);
                    }
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
        start: (id = "task-1") => onMessage({ id, task: "executeCode", params: { code: "" } }),
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

/** Lets the follow-up work of the tasks run. */
function settle() {
    return new Promise((resolve) => setTimeout(resolve, 20));
}

test("a task received while another task runs is denied without running", async () => {
    const plugin = pluginWithPendingTask("success");
    plugin.start("task-1");
    plugin.start("task-2");
    assert.equal(plugin.responses.length, 1);
    assert.equal(plugin.responses[0].id, "task-2");
    assert.equal(plugin.responses[0].success, false);
    assert.match(plugin.responses[0].error, /did not run/);
    assert.match(plugin.responses[0].error, /separate Penpot tab for each agent/);
    // the rollbacks stay with the running task
    assert.equal(plugin.responses[0].rollbacks, undefined);

    plugin.release();
    await settle();
    assert.equal(plugin.responses.length, 2);
    assert.equal(plugin.responses[1].id, "task-1");
    assert.equal(plugin.responses[1].success, true);
    assert.deepEqual(plugin.responses[1].rollbacks, [{ id: "rollback-1" }]);
});

for (const outcome of ["success", "failure"] as const) {
    test(`a task ending in ${outcome} frees the tab for the next task`, async () => {
        const plugin = pluginWithPendingTask(outcome);
        plugin.start("task-1");
        plugin.release();
        await settle();
        plugin.start("task-2");
        await settle();
        assert.deepEqual(
            plugin.responses.map((response) => [response.id, response.success]),
            [
                ["task-1", outcome === "success"],
                ["task-2", outcome === "success"],
            ]
        );
        assert.doesNotMatch(plugin.responses[1].error ?? "", /did not run/);
    });
}
