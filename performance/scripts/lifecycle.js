// Lifecycle Performance Test
//
// Simulates a realistic user lifecycle from registration through CRUD operations.
// Each VU performs the full flow independently, creating its own artifacts.
//
// setup() creates a user pool (one demo profile per VU) before measurements begin.
// Each VU picks its assigned user to login — no profile creation during the test.
//
// Flow:
//   1. Login (with pre-existing user from pool)
//   2. Get profile & teams
//   3. Create project
//   4. Create file
//   5. Get file
//   6. Update file (add a shape)
//   7. Upload images (direct + chunked)
//   8. Delete file
//   9. Delete project
//  10. Logout
//
// Usage:
//   k6 run scripts/lifecycle.js
//   k6 run --vus 100 --iterations 100 scripts/lifecycle.js
//   k6 run --env PENPOT_BASE_URL=http://localhost:6060 scripts/lifecycle.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";

// k6 options — smoke test defaults (1 VU, 1 iteration)
export const options = {
  scenarios: {
    lifecycle: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "2m",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<5000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:login-with-password}": ["p(95)<1000"],
    "http_req_duration{rpc_command:get-profile}": ["p(95)<500"],
    "http_req_duration{rpc_command:create-project}": ["p(95)<1000"],
    "http_req_duration{rpc_command:create-file}": ["p(95)<1000"],
    "http_req_duration{rpc_command:get-file}": ["p(95)<500"],
    "http_req_duration{rpc_command:update-file}": ["p(95)<2000"],
    "http_req_duration{rpc_command:delete-file}": ["p(95)<1000"],
  },
};

// ---------------------------------------------------------------------------
// Test Data
// ---------------------------------------------------------------------------

const testImageSmall = open("../../backend/test/backend_tests/test_files/sample.png", "b");
const testImageLarge = open("../../backend/test/backend_tests/test_files/sample.jpg", "b");

// A minimal "add-obj" change payload for update-file.
function makeAddRectChange(pageId) {
  const shapeId = uuidv4();
  const x = 100;
  const y = 100;
  const w = 200;
  const h = 150;

  return {
    type: "add-obj",
    pageId: pageId,
    id: shapeId,
    frameId: pageId,
    parentId: pageId,
    obj: {
      id: shapeId,
      type: "rect",
      name: "Perf Test Rect",
      x: x, y: y, width: w, height: h,
      fillColor: "#ff0000", fillOpacity: 1,
      rotation: 0, hidden: false, locked: false,
      selrect: { x, y, width: w, height: h, x1: x, y1: y, x2: x + w, y2: y + h },
      points: [
        { x, y }, { x: x + w, y }, { x: x + w, y: y + h }, { x, y: y + h },
      ],
      transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      transformInverse: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      parentId: pageId, frameId: pageId,
    },
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function assertOk(res, label) {
  const ok = check(res, {
    [`${label} — status is 2xx`]: (r) => r.status >= 200 && r.status < 300,
  });
  if (!ok) {
    let bodyStr = "";
    try {
      if (res.raw && res.raw.body) {
        bodyStr = typeof res.raw.body === "string"
          ? res.raw.body.substring(0, 500)
          : JSON.stringify(res.raw.body).substring(0, 500);
      } else if (res.body) {
        bodyStr = JSON.stringify(res.body).substring(0, 500);
      }
    } catch (e) {
      bodyStr = "(could not read body)";
    }
    console.error(`FAIL: ${label} — status=${res.status} body=${bodyStr}`);
  }
  return ok;
}

// ---------------------------------------------------------------------------
// Setup — create user pool before VUs start
// ---------------------------------------------------------------------------

export function setup() {
  // Resolve VU count from options or CLI --vus flag
  const vuCount = (options.scenarios.lifecycle && options.scenarios.lifecycle.vus) || __ENV.K6_VUS || 1;

  console.log(`Penpot Lifecycle Test`);
  console.log(`  Base URL:  ${BASE_URL}`);
  console.log(`  VUs:       ${vuCount}`);
  console.log(`  Creating ${vuCount} demo profiles...`);
  console.log(``);

  const client = createClient(BASE_URL);

  // Verify backend is reachable
  const pingRes = client.getProfile();
  if (pingRes.status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  // Create one demo profile per VU
  const users = [];
  for (let i = 0; i < vuCount; i++) {
    const res = client.rpc("POST", "create-demo-profile", {});
    if (res.status !== 200) {
      fail(`Failed to create demo profile ${i + 1}/${vuCount}: status=${res.status}`);
    }
    users.push(res.json());
  }

  console.log(`  Created ${users.length} demo profiles`);
  return { baseUrl: BASE_URL, users };
}

// ---------------------------------------------------------------------------
// Main VU Function
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Pick user from pool
  const user = data.users[__VU - 1];
  if (!user) {
    fail(`No user for VU ${__VU} (pool size: ${data.users.length})`);
  }

  // ---- Step 1: Login ----
  const loginRes = client.login(user.email, user.password);
  if (!assertOk(loginRes, "login-with-password")) fail("Login failed");
  const profile = loginRes.body;
  const profileId = profile.id;

  sleep(1);

  // ---- Step 2: Get profile ----
  if (!assertOk(client.getProfile(), "get-profile")) fail("get-profile failed");

  sleep(0.5);

  // ---- Step 3: Get teams ----
  const teamsRes = client.getTeams();
  if (!assertOk(teamsRes, "get-teams")) fail("get-teams failed");
  const defaultTeamId = teamsRes.body[0].id;

  sleep(0.5);

  // ---- Step 4: Create a project ----
  const projectRes = client.createProject(defaultTeamId, `Perf Project ${uuidv4().substring(0, 8)}`);
  if (!assertOk(projectRes, "create-project")) fail("create-project failed");
  const projectId = projectRes.body.id;

  sleep(1);

  // ---- Step 5: Create a file ----
  const fileRes = client.createFile(projectId, `Perf File ${uuidv4().substring(0, 8)}`);
  if (!assertOk(fileRes, "create-file")) fail("create-file failed");
  const fileId = fileRes.body.id;

  sleep(1);

  // ---- Step 6: Get the file ----
  const getFileRes = client.getFile(fileId);
  if (!assertOk(getFileRes, "get-file")) fail("get-file failed");
  const fileData = getFileRes.body;
  const pageId = fileData.data.pages[0];

  sleep(1);

  // ---- Step 7: Update file (add a shape) ----
  if (pageId) {
    const changes = [makeAddRectChange(pageId)];
    const updateRes = client.updateFile(fileId, fileData.revn, fileData.vern, client.sessionId, changes);

    if (updateRes.status !== 200) {
      // Retry once on revn conflict
      const body = updateRes.body;
      const isConflict = body && (body.code === "revn-conflict" || body.type === "revn-conflict");
      if (isConflict) {
        const retryFile = client.getFile(fileId);
        if (retryFile.status === 200) {
          client.updateFile(fileId, retryFile.body.revn, retryFile.body.vern, client.sessionId, changes);
        }
      }
    }
  }

  sleep(1);

  // ---- Step 8: Upload images ----
  if (testImageSmall && testImageSmall.byteLength > 0) {
    assertOk(
      client.uploadFileMediaObject(fileId, testImageSmall, "sample.png", "image/png"),
      "upload (direct)"
    );
  }
  sleep(0.5);
  if (testImageLarge && testImageLarge.byteLength > 0) {
    assertOk(
      client.uploadFileMediaObject(fileId, testImageLarge, "sample.jpg", "image/jpeg"),
      "upload (chunked)"
    );
  }

  sleep(1);

  // ---- Step 9: Delete file ----
  assertOk(client.deleteFile(fileId), "delete-file");
  sleep(0.5);

  // ---- Step 10: Delete project ----
  assertOk(client.deleteProject(projectId), "delete-project");
  sleep(0.5);

  // ---- Step 11: Logout ----
  client.logout(profileId);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Lifecycle test complete.");
}
