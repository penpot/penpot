import { describe, it, before } from "node:test";
import assert from "node:assert/strict";
import { setupTestProfile } from "./helpers/auth.mjs";
import { rpcPost } from "./helpers/client.mjs";
import { parseSSE, extractResult } from "./helpers/sse.mjs";

async function createFile(cookie, projectId, name = "E2E Test File") {
  const res = await rpcPost(
    "create-file",
    { name, projectId },
    { cookieToken: cookie }
  );
  assert.equal(res.status, 200, `create-file failed: ${JSON.stringify(res.body)}`);
  return res.body;
}

async function exportFile(cookie, fileId) {
  const res = await rpcPost(
    "export-binfile",
    {
      fileId,
      includeLibraries: false,
      embedAssets: true,
    },
    { cookieToken: cookie }
  );
  assert.equal(res.status, 200, `export-binfile failed: ${JSON.stringify(res.body)}`);

  const events = parseSSE(res.body);
  const assetUrl = extractResult(events);
  return assetUrl;
}

describe("export-binfile", () => {
  let profile, cookie;

  before(async () => {
    const setup = await setupTestProfile();
    profile = setup.profile;
    cookie = setup.cookie;
  });

  it("creates a file via API", async () => {
    const file = await createFile(cookie, profile.defaultProjectId);
    assert.ok(file.id, "file should have an id");
    assert.equal(file.name, "E2E Test File");
  });

  it("export returns an asset URL", async () => {
    const file = await createFile(cookie, profile.defaultProjectId);
    const assetUrl = await exportFile(cookie, file.id);
    assert.ok(
      typeof assetUrl === "string" && assetUrl.includes("/assets/by-id/"),
      `asset URL should contain /assets/by-id/, got: ${assetUrl}`
    );
    assert.match(assetUrl, /\/assets\/by-id\/[0-9a-f-]+$/);
  });

  it("export with invalid file-id returns error", async () => {
    const fakeId = "00000000-0000-0000-0000-000000000000";
    const res = await rpcPost(
      "export-binfile",
      {
        fileId: fakeId,
        includeLibraries: false,
        embedAssets: true,
      },
      { cookieToken: cookie }
    );
    assert.ok(
      res.body.type || res.status !== 200,
      "should return error for non-existent file"
    );
  });

  it("export requires authentication", async () => {
    const res = await rpcPost("export-binfile", {
      fileId: "00000000-0000-0000-0000-000000000000",
      includeLibraries: false,
      embedAssets: true,
    });
    assert.ok(
      res.body.type || res.status !== 200,
      "should require authentication"
    );
  });
});
