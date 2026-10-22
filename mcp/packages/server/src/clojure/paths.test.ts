import assert from "node:assert/strict";
import { test } from "node:test";
import { containerPath } from "./paths";

const HOST = "/home/dev/Worktrees/penpot-x";

test("a host path inside the repository maps into the container", () => {
    assert.deepEqual(containerPath(`${HOST}/tmp/probe.clj`, HOST), { path: "/home/penpot/penpot/tmp/probe.clj" });
});

test("container paths and paths relative to the repository root are taken as they are", () => {
    assert.deepEqual(containerPath("/home/penpot/penpot/backend/src/app/x.clj", HOST), {
        path: "/home/penpot/penpot/backend/src/app/x.clj",
    });
    assert.deepEqual(containerPath("tmp/probe.clj", HOST), { path: "/home/penpot/penpot/tmp/probe.clj" });
});

test("a path outside the repository is refused with what to do instead", () => {
    const outside = containerPath("/tmp/probe.clj", HOST);
    assert.ok("refusal" in outside);
    assert.match(outside.refusal, /file_content/);
    assert.match(outside.refusal, new RegExp(`inside the repository \\(${HOST}\\)`));
});

test("a sibling directory that shares the repository's prefix is outside", () => {
    assert.ok("refusal" in containerPath(`${HOST}-other/x.clj`, HOST));
});

test(".. cannot leave the repository", () => {
    assert.ok("refusal" in containerPath(`${HOST}/../secrets.clj`, HOST));
    assert.ok("refusal" in containerPath("../secrets.clj", HOST));
    assert.ok("refusal" in containerPath("/home/penpot/penpot/../x.clj", HOST));
});

test("without a known host root, only container and relative paths work", () => {
    assert.ok("refusal" in containerPath(`${HOST}/tmp/probe.clj`, undefined));
    assert.deepEqual(containerPath("tmp/p.clj", undefined), { path: "/home/penpot/penpot/tmp/p.clj" });
});
