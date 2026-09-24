import assert from "node:assert/strict";
import test from "node:test";
import { WS_CLOSE_POLICY_VIOLATION, shouldReconnectAfterClose } from "./ReconnectPolicy.ts";

test("names the policy violation close code used by the MCP server", () => {
    assert.equal(WS_CLOSE_POLICY_VIOLATION, 1008);
});

test("does not reconnect after a policy violation close", () => {
    assert.equal(shouldReconnectAfterClose(WS_CLOSE_POLICY_VIOLATION), false);
});

test("reconnects after transient close codes", () => {
    const transientCodes = [
        { code: 1000, meaning: "normal closure" },
        { code: 1001, meaning: "going away" },
        { code: 1005, meaning: "no status received" },
        { code: 1006, meaning: "abnormal closure" },
    ];

    for (const { code, meaning } of transientCodes) {
        assert.equal(shouldReconnectAfterClose(code), true, `expected reconnect for ${code} (${meaning})`);
    }
});
