import assert from "node:assert/strict";
import test from "node:test";
import { extractMinorVersion, isCompatibleVersion } from "./VersionCompatibility.ts";

test("extracts major.minor from release and pre-release versions", () => {
    assert.equal(extractMinorVersion("2.18.1"), "2.18");
    assert.equal(extractMinorVersion("2.17.0"), "2.17");
    assert.equal(extractMinorVersion("2.15.0-rc.1.116"), "2.15");
    assert.equal(extractMinorVersion("2.18.1-dev.140"), "2.18");
    assert.equal(extractMinorVersion("2.18"), "2.18");
});

test("returns non-matching versions unchanged", () => {
    assert.equal(extractMinorVersion("<2.15"), "<2.15");
    assert.equal(extractMinorVersion("develop"), "develop");
});

test("treats patch releases of the same minor version as compatible", () => {
    assert.equal(isCompatibleVersion(extractMinorVersion("2.17.2"), extractMinorVersion("2.17.0")), true);
});

test("reports a different minor version as incompatible", () => {
    assert.equal(isCompatibleVersion(extractMinorVersion("2.18.1"), extractMinorVersion("2.17.0")), false);
});

test("treats local development builds of Penpot as compatible", () => {
    assert.equal(isCompatibleVersion(extractMinorVersion("0.0.0"), extractMinorVersion("2.17.0")), true);
});
