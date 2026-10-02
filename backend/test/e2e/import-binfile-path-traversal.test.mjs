import { describe, it, before } from "node:test";
import assert from "node:assert/strict";
import { setupTestProfile } from "./helpers/auth.mjs";
import { rpcPost } from "./helpers/client.mjs";

function errorCode(body) {
  if (body == null) return undefined;
  if (typeof body === "string") {
    try {
      return errorCode(JSON.parse(body));
    } catch {
      return undefined;
    }
  }
  return body.code ?? body["~:code"];
}

function errorType(body) {
  if (body == null) return undefined;
  if (typeof body === "string") {
    try {
      return errorType(JSON.parse(body));
    } catch {
      return undefined;
    }
  }
  return body.type ?? body["~:type"];
}

describe("import-binfile path traversal", () => {
  let profile;
  let cookie;

  before(async () => {
    const setup = await setupTestProfile();
    profile = setup.profile;
    cookie = setup.cookie;
  });

  it("rejects a hand-forged :file path in a transit body", async () => {
    // ATTACK SCENARIO:
    // 1. The attacker has a valid session and a valid project-id.
    // 2. Instead of a real multipart upload, they hand-write a transit
    //    body (plain JSON, no transit library needed) with a forged
    //    :file map that points to an internal server file.
    //    The transit tag ["~#path", "/etc/passwd"] becomes a real
    //    java.nio.file.Path on the server (see app.common.transit
    //    read handlers) and passes media.v/schema:upload, which only
    //    checks the shape, not where the path came from.
    // 3. import-binfile then uses (:path file) directly as input-path
    //    (parse-file-format / get-manifest / import-files!).
    //
    // EXPECTED BEHAVIOR AFTER FIX: 400 validation/params-validation,
    // without touching the file. While vulnerable, the request passes
    // validation and fails later (zip/manifest error) or hangs in SSE,
    // proving the file was opened.
    const transitBody = JSON.stringify({
      "~:project-id": `~u${profile.defaultProjectId}`,
      "~:name": "pwned",
      "~:version": 3,
      "~:file": {
        "~:filename": "evil.zip",
        "~:size": 123,
        "~:path": ["~#path", "/etc/passwd"],
      },
    });

    const res = await rpcPost("import-binfile", transitBody, {
      cookieToken: cookie,
      contentType: "application/transit+json",
      accept: "application/json",
    });

    assert.equal(
      res.status,
      400,
      `forged :file path must be rejected with 400, got ${res.status}: ${JSON.stringify(res.body).slice(0, 500)}`
    );
    assert.equal(
      errorType(res.body),
      "validation",
      `expected validation error, got: ${JSON.stringify(res.body).slice(0, 500)}`
    );
    assert.equal(
      errorCode(res.body),
      "params-validation",
      `forged :file path must fail at params validation, got: ${JSON.stringify(res.body).slice(0, 500)}`
    );
  });

  it("plain JSON string path stays rejected", async () => {
    // Control case: without the transit "~#path" tag the path arrives
    // as a plain string and ::fs/path already rejects it. This test
    // documents that only the transit vector bypasses the check.
    const res = await rpcPost(
      "import-binfile",
      {
        projectId: profile.defaultProjectId,
        name: "pwned",
        version: 3,
        file: { filename: "evil.zip", size: 123, path: "/etc/passwd" },
      },
      { cookieToken: cookie }
    );

    assert.equal(res.status, 400);
    assert.equal(errorType(res.body), "validation");
    assert.equal(errorCode(res.body), "params-validation");
  });
});
