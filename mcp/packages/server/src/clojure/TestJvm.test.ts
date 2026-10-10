import assert from "node:assert/strict";
import { type ChildProcess, spawn } from "node:child_process";
import { after, test } from "node:test";
import { isTestJvmProcess } from "./TestJvm";

const children: ChildProcess[] = [];

/** A child process that stays alive until `after` kills it; the timer runs in the child, and no test waits on it. */
function child(...args: string[]): number {
    const proc = spawn(process.execPath, ["-e", "setTimeout(() => {}, 30000)", ...args], { stdio: "ignore" });
    children.push(proc);
    assert.ok(proc.pid);
    return proc.pid;
}

after(() => {
    for (const proc of children) proc.kill("SIGKILL");
});

test("a live process that runs another program is not the test JVM", () => {
    assert.equal(isTestJvmProcess(child()), false);
});

test("a live process whose arguments name the test JVM script is the test JVM", () => {
    assert.equal(isTestJvmProcess(child("-i", "/home/penpot/penpot/backend/scripts/testjvm.clj")), true);
});

test("a pid with no process is not the test JVM", async () => {
    const proc = spawn(process.execPath, ["-e", "", "scripts/testjvm.clj"], { stdio: "ignore" });
    await new Promise((resolve) => proc.on("exit", resolve));
    assert.equal(isTestJvmProcess(proc.pid!), false);
});
