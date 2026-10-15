import assert from "node:assert/strict";
import { type ChildProcess, spawn } from "node:child_process";
import { once } from "node:events";
import { after, test } from "node:test";
import { isTestJvmProcess } from "./TestJvm";

const SCRIPT = "/home/penpot/penpot/backend/scripts/testjvm.clj";
const children: ChildProcess[] = [];

/** A child process that reads its stdin, so it stays alive until `after` closes the pipe. */
function child(...args: string[]): ChildProcess {
    const proc = spawn(process.execPath, ["-e", "process.stdin.resume()", ...args], {
        stdio: ["pipe", "ignore", "ignore"],
    });
    children.push(proc);
    return proc;
}

after(() => {
    for (const proc of children) proc.stdin?.end();
});

test("a live process that runs another program is not the test JVM", () => {
    assert.equal(isTestJvmProcess(child().pid!, SCRIPT), false);
});

test("a live process whose arguments name this tree's script is the test JVM", () => {
    assert.equal(isTestJvmProcess(child("-i", SCRIPT).pid!, SCRIPT), true);
});

test("a test JVM of another tree is not this tree's test JVM", () => {
    const other = child("-i", "/home/penpot/other-tree/backend/scripts/testjvm.clj");
    assert.equal(isTestJvmProcess(other.pid!, SCRIPT), false);
});

test("a pid with no process is not the test JVM", async () => {
    const proc = child("-i", SCRIPT);
    proc.stdin!.end();
    await once(proc, "exit");
    assert.equal(isTestJvmProcess(proc.pid!, SCRIPT), false);
});
