import assert from "node:assert/strict";
import test from "node:test";
import { logTransportTargets } from "./logger";

test("test processes log to stdout instead of a transport worker thread", () => {
    assert.deepEqual(logTransportTargets({ NODE_TEST_CONTEXT: "child-v8" }), []);
});

test("regular processes keep the configured pino transports", () => {
    assert.deepEqual(
        logTransportTargets({}).map((target) => target.target),
        ["pino-pretty"]
    );
});
