import assert from "node:assert/strict";
import test from "node:test";
import { formatTaskError } from "./ErrorUtils.ts";

test("includes ClojureScript exception data in the task error message", () => {
    const keyword = (name: string) => ({ name, fqn: name });
    const entries = [
        { key: keyword("type"), val: keyword("validation") },
        { key: keyword("code"), val: keyword("request-body-too-large") },
        { key: keyword("status"), val: 413 },
    ];
    const data = {
        *[Symbol.iterator]() {
            yield* entries;
        },
    };
    const error = Object.assign(new Error("http error"), { data });

    assert.equal(formatTaskError(error), "http error (type: validation, code: request-body-too-large, status: 413)");
});

test("falls back to the printed representation when map internals are renamed by the Closure compiler", () => {
    // In release builds MapEntry/Keyword field names are minified, so entry
    // extraction finds nothing; only toString keeps the readable CLJS form.
    const entries = [{ a7: { Eb: "type" }, gb: { Eb: "validation" } }];
    const data = {
        *[Symbol.iterator]() {
            yield* entries;
        },
        toString: () => "{:type :validation, :code :request-body-too-large}",
    };
    const error = Object.assign(new Error("http error"), { data });

    assert.equal(formatTaskError(error), "http error ({:type :validation, :code :request-body-too-large})");
});

test("formats plain object data and nested printable values", () => {
    const data = {
        status: 413,
        uri: "https://example.test/api/export",
        detail: { toString: () => "{:code :too-large}" },
    };
    const error = Object.assign(new Error("http error"), { data });

    assert.equal(
        formatTaskError(error),
        "http error (status: 413, uri: https://example.test/api/export, detail: {:code :too-large})"
    );
});

test("keeps string values verbatim", () => {
    const error = Object.assign(new Error("http error"), { data: { hint: ":not-a-keyword" } });

    assert.equal(formatTaskError(error), "http error (hint: :not-a-keyword)");
});

test("returns the original message when no structured data is available", () => {
    assert.equal(formatTaskError(new Error("export timed out")), "export timed out");
});

test("formats non-Error values", () => {
    assert.equal(formatTaskError("connection closed"), "connection closed");
});
