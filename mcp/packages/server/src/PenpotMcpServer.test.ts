import assert from "node:assert/strict";
import test from "node:test";
import { shouldRegisterDeveloperTools } from "./PenpotMcpServer";

test("registers developer tools in local devenv mode", () => {
    assert.equal(shouldRegisterDeveloperTools(true, false), true);
});

test("does not register developer tools in multi-user devenv mode", () => {
    assert.equal(shouldRegisterDeveloperTools(true, true), false);
});

test("does not register developer tools when devenv mode is disabled", () => {
    assert.equal(shouldRegisterDeveloperTools(false, false), false);
});
