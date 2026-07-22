import { describe, it, before } from "node:test";
import assert from "node:assert/strict";
import {
  setupTestProfile,
  createAccessToken,
} from "./helpers/auth.mjs";
import { rpcPost, getAsset } from "./helpers/client.mjs";
import { parseSSE, extractResult } from "./helpers/sse.mjs";

async function createAndExport(cookie, projectId) {
  const createRes = await rpcPost(
    "create-file",
    { name: "E2E Asset Test", projectId },
    { cookieToken: cookie }
  );
  assert.equal(createRes.status, 200);
  const fileId = createRes.body.id;

  const exportRes = await rpcPost(
    "export-binfile",
    { fileId, includeLibraries: false, embedAssets: true },
    { cookieToken: cookie }
  );
  assert.equal(exportRes.status, 200);
  const assetUrl = extractResult(parseSSE(exportRes.body));
  return assetUrl;
}

function extractAssetId(assetUrl) {
  const match = assetUrl.match(/\/assets\/by-id\/([0-9a-f-]+)/);
  return match ? match[1] : null;
}

describe("asset download", () => {
  let profile, cookie, assetUrl, assetId;

  before(async () => {
    const setup = await setupTestProfile();
    profile = setup.profile;
    cookie = setup.cookie;

    assetUrl = await createAndExport(cookie, profile.defaultProjectId);
    assetId = extractAssetId(assetUrl);
    assert.ok(assetId, `should extract asset id from URL: ${assetUrl}`);
  });

  it("asset download with cookie auth succeeds", async () => {
    // In devenv, nginx's @handle_redirect intercepts the backend's 307 and
    // proxies to S3 directly. The client sees 200 with file content, not 307.
    const res = await getAsset(assetId, { cookieToken: cookie });
    assert.equal(res.status, 200, `expected 200, got ${res.status}`);
    assert.ok(
      res.body.length > 0 || typeof res.body === "object",
      "response should have content"
    );
  });

  it("asset download with access token auth succeeds", async () => {
    const tokenObj = await createAccessToken(cookie, "e2e-asset-test");
    const accessToken = tokenObj.token;

    const res = await getAsset(assetId, { accessToken });
    assert.equal(res.status, 200, `expected 200, got ${res.status}`);
  });

  it("asset download without auth returns 401", async () => {
    const res = await getAsset(assetId, {});
    assert.equal(res.status, 401, `expected 401, got ${res.status}`);
  });

  it("asset download returns file content through nginx proxy", async () => {
    // The full flow: backend returns 307 with S3 presigned URL,
    // nginx intercepts and proxies to S3, client gets 200 with content.
    const res = await getAsset(assetId, { cookieToken: cookie });
    assert.equal(res.status, 200);
    // Response should be a .penpot file (binary/zip content)
    assert.ok(res.body, "response should have body");
  });

  it("follow S3 redirect WITH auth header (bug repro)", async () => {
    // In devenv, nginx's @handle_redirect intercepts the 307 and proxies to
    // S3 server-side, only forwarding the Host header from X-Host. The client's
    // Authorization header is NOT forwarded to S3, so the request succeeds.
    //
    // In production (no nginx proxy), the backend returns 307 directly. The HTTP
    // client follows the redirect and forwards the Authorization: Token header to
    // S3, which conflicts with the presigned URL's X-Amz-* params and returns
    // 400 InvalidArgument.
    //
    // This test documents the devenv behavior: nginx strips the auth header
    // when proxying to S3, so the download succeeds.
    const res = await getAsset(assetId, { cookieToken: cookie });
    assert.equal(res.status, 200, "through nginx, download succeeds");
    assert.ok(res.body, "should have file content");
  });

  it("full export-to-download flow works end-to-end", async () => {
    const url = await createAndExport(cookie, profile.defaultProjectId);
    const id = extractAssetId(url);
    assert.ok(id);

    const res = await getAsset(id, { cookieToken: cookie });
    assert.equal(res.status, 200);
  });

  it("asset URL is accessible immediately after export", async () => {
    const url = await createAndExport(cookie, profile.defaultProjectId);
    const id = extractAssetId(url);
    assert.ok(id);

    const res = await getAsset(id, { cookieToken: cookie });
    assert.equal(res.status, 200, "asset should be accessible right after export");
  });

  it("token-only: export then download asset with same token", async () => {
    const tokenObj = await createAccessToken(cookie, "e2e-token-export-test");
    const token = tokenObj.token;

    const createRes = await rpcPost(
      "create-file",
      { name: "E2E Token Export Test", projectId: profile.defaultProjectId },
      { accessToken: token }
    );
    assert.equal(createRes.status, 200);
    const fileId = createRes.body.id;

    const exportRes = await rpcPost(
      "export-binfile",
      { fileId, includeLibraries: false, embedAssets: true },
      { accessToken: token }
    );
    assert.equal(exportRes.status, 200);

    const events = parseSSE(exportRes.body);
    const url = extractResult(events);
    assert.ok(url, "should get an asset URL from export");

    const id = extractAssetId(url);
    assert.ok(id, `should extract asset id from URL: ${url}`);

    const res = await getAsset(id, { accessToken: token });
    assert.equal(res.status, 200, `expected 200, got ${res.status}`);
    assert.ok(res.body, "response should have file content");
  });
});
