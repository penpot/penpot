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

async function createFile(cookie, projectId, name = "E2E Media Test File") {
  const res = await rpcPost(
    "create-file",
    { name, projectId },
    { cookieToken: cookie }
  );
  assert.equal(res.status, 200, `create-file failed: ${JSON.stringify(res.body)}`);
  return res.body;
}

async function createUploadSession(cookie) {
  const res = await rpcPost(
    "create-upload-session",
    { totalChunks: 1 },
    { cookieToken: cookie }
  );
  assert.equal(
    res.status,
    200,
    `create-upload-session failed: ${JSON.stringify(res.body)}`
  );
  return res.body.sessionId ?? res.body["~:session-id"];
}

describe("upload-file-media path traversal", () => {
  let profile;
  let cookie;
  let fileId;

  before(async () => {
    const setup = await setupTestProfile();
    profile = setup.profile;
    cookie = setup.cookie;
    const file = await createFile(cookie, profile.defaultProjectId);
    fileId = file.id;
  });

  it("rejects a hand-forged :content path in a transit body", async () => {
    // ATTACK SCENARIO:
    // 1. The attacker has a valid session and a file they can edit.
    // 2. Instead of a real multipart upload, they hand-write a transit
    //    body (plain JSON, no transit library needed) with a forged
    //    :content map that points to an internal server file.
    //    The transit tag ["~#path", "/etc/passwd"] becomes a real
    //    java.nio.file.Path on the server (see app.common.transit
    //    read handlers) and passes media.v/schema:upload, which only
    //    checks the shape, not where the path came from. The attacker
    //    also controls :size and :mtype, so validate-media-type! and
    //    validate-media-size! pass with "image/png" and a small size.
    // 3. upload-file-media-object then reads (:path content) in
    //    process-image (media/run :info) and stores the bytes as a
    //    media object, which is later downloadable by URL.
    //
    // EXPECTED BEHAVIOR AFTER FIX: 400 validation/params-validation,
    // without touching the file. While vulnerable, the request passes
    // validation and fails later in media processing, proving the
    // file was opened.
    const transitBody = JSON.stringify({
      "~:file-id": `~u${fileId}`,
      "~:is-local": true,
      "~:name": "pwned",
      "~:content": {
        "~:filename": "evil.png",
        "~:size": 123,
        "~:mtype": "image/png",
        "~:path": ["~#path", "/etc/passwd"],
      },
    });

    const res = await rpcPost("upload-file-media-object", transitBody, {
      cookieToken: cookie,
      contentType: "application/transit+json",
      accept: "application/json",
    });

    assert.equal(
      res.status,
      400,
      `forged :content path must be rejected with 400, got ${res.status}: ${JSON.stringify(res.body).slice(0, 500)}`
    );
    assert.equal(
      errorType(res.body),
      "validation",
      `expected validation error, got: ${JSON.stringify(res.body).slice(0, 500)}`
    );
    assert.equal(
      errorCode(res.body),
      "params-validation",
      `forged :content path must fail at params validation, got: ${JSON.stringify(res.body).slice(0, 500)}`
    );
  });

  it("rejects a hand-forged chunk :content path in a transit body", async () => {
    // Same vector through upload-chunk: the forged path is read with
    // sto/content and stored as a chunk blob. While vulnerable this
    // returns 200 (the internal file bytes are stored under the
    // attacker session, retrievable via assemble) — proving the file
    // was read and kept. After the fix it must be 400
    // validation/params-validation.
    const sessionId = await createUploadSession(cookie);

    const transitBody = JSON.stringify({
      "~:session-id": `~u${sessionId}`,
      "~:index": 0,
      "~:content": {
        "~:filename": "c",
        "~:size": 123,
        "~:mtype": "application/octet-stream",
        "~:path": ["~#path", "/etc/passwd"],
      },
    });

    const res = await rpcPost("upload-chunk", transitBody, {
      cookieToken: cookie,
      contentType: "application/transit+json",
      accept: "application/json",
    });

    assert.equal(
      res.status,
      400,
      `forged chunk :content path must be rejected with 400, got ${res.status}: ${JSON.stringify(res.body).slice(0, 500)}`
    );
    assert.equal(
      errorType(res.body),
      "validation",
      `expected validation error, got: ${JSON.stringify(res.body).slice(0, 500)}`
    );
    assert.equal(
      errorCode(res.body),
      "params-validation",
      `forged chunk :content path must fail at params validation, got: ${JSON.stringify(res.body).slice(0, 500)}`
    );
  });

  it("plain JSON string path stays rejected", async () => {
    // Control case: without the transit "~#path" tag the path arrives
    // as a plain string and ::fs/path already rejects it. This test
    // documents that only the transit vector bypasses the check.
    const res = await rpcPost(
      "upload-file-media-object",
      {
        fileId,
        isLocal: true,
        name: "pwned",
        content: {
          filename: "evil.png",
          size: 123,
          mtype: "image/png",
          path: "/etc/passwd",
        },
      },
      { cookieToken: cookie }
    );

    assert.equal(res.status, 400);
    assert.equal(errorType(res.body), "validation");
    assert.equal(errorCode(res.body), "params-validation");
  });
});
