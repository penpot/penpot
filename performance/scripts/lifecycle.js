// Lifecycle Performance Test
//
// Simulates a realistic user lifecycle from registration through CRUD operations.
// Each VU performs the full flow independently, creating its own artifacts.
//
// Flow:
//   1. Register (via demo profile or prepare+register)
//   2. Login
//   3. Get profile & teams
//   4. Create project
//   5. Create file
//   6. Get file
//   7. Update file (add a shape)
//   8. Upload image to file
//   9. Delete file
//  10. Delete project
//  11. Delete team
//
// Usage:
//   k6 run scripts/lifecycle.js
//   k6 run --env PENPOT_BASE_URL=http://localhost:6060 scripts/lifecycle.js
//   k6 run --env PENPOT_REGISTER_MODE=register scripts/lifecycle.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";
// "demo" = use create-demo-profile (requires demo-users flag)
// "register" = use prepare-register-profile + register-profile
const REGISTER_MODE = __ENV.PENPOT_REGISTER_MODE || "demo";

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

// Load test PNG fixtures — small uses direct upload, large uses chunked upload
const testImageSmall = open("../fixtures/test-small.png", "b");
const testImageLarge = open("../fixtures/test-large.png", "b");

// A minimal "add-obj" change payload for update-file.
// This adds a simple rectangle shape to the first page.
// All object properties use camelCase — the backend's JSON parser
// (json/read-kebab-key) converts camelCase to kebab-case keywords automatically.
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
    frameId: pageId, // required: the frame this shape belongs to
    parentId: pageId, // root frame is the page itself
    obj: {
      id: shapeId,
      type: "rect",
      name: "Perf Test Rect",
      x: x,
      y: y,
      width: w,
      height: h,
      fillColor: "#ff0000",
      fillOpacity: 1,
      rotation: 0,
      hidden: false,
      locked: false,
      // Required base attrs
      selrect: {
        x: x,
        y: y,
        width: w,
        height: h,
        x1: x,
        y1: y,
        x2: x + w,
        y2: y + h,
      },
      points: [
        { x: x, y: y },
        { x: x + w, y: y },
        { x: x + w, y: y + h },
        { x: x, y: y + h },
      ],
      transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      transformInverse: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      parentId: pageId,
      frameId: pageId,
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
    // Try to get the response body for debugging
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
    console.error(
      `FAIL: ${label} — status=${res.status} body=${bodyStr}`
    );
  }
  return ok;
}

// ---------------------------------------------------------------------------
// Setup — runs once before VUs start
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`Penpot Lifecycle Test`);
  console.log(`  Base URL:       ${BASE_URL}`);
  console.log(`  Register mode:  ${REGISTER_MODE}`);
  console.log(``);

  // Verify the backend is reachable
  const client = createClient(BASE_URL);
  const res = client.getProfile();
  // We expect 401/403 (not logged in) — anything else means the backend is down
  if (res.status === 0) {
    fail(`Backend unreachable at ${BASE_URL}`);
  }

  return { baseUrl: BASE_URL, registerMode: REGISTER_MODE };
}

// ---------------------------------------------------------------------------
// Main VU Function
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // ---- Step 0: Create a user account ----
  let email, password;

  if (data.registerMode === "demo") {
    // Demo mode: create-demo-profile returns email + password
    const demoRes = client.rpc("POST", "create-demo-profile", {});
    if (!assertOk(demoRes, "create-demo-profile")) {
      fail("Failed to create demo profile");
    }
    const demoBody = demoRes.json();
    email = demoBody.email;
    password = demoBody.password;
    console.log(`VU ${__VU}: Created demo profile: ${email}`);
  } else {
    // Register mode: prepare + register
    email = `perf-${uuidv4()}@test.local`;
    password = "PerfTest1234!";
    const fullname = `Perf User ${__VU}`;

    const prepareRes = client.rpc("POST", "prepare-register-profile", {
      fullname,
      email,
      password,
    });
    if (!assertOk(prepareRes, "prepare-register-profile")) {
      fail("Failed to prepare registration");
    }
    const prepareBody = prepareRes.json();
    const token = prepareBody.token;

    const registerRes = client.rpc("POST", "register-profile", {
      token,
    });
    if (!assertOk(registerRes, "register-profile")) {
      fail("Failed to register profile");
    }
    console.log(`VU ${__VU}: Registered profile: ${email}`);
  }

  sleep(1);

  // ---- Step 1: Login ----
  const loginRes = client.login(email, password);
  if (!assertOk(loginRes, "login-with-password")) {
    fail("Login failed");
  }
  const profile = loginRes.body;
  const profileId = profile.id;
  console.log(`VU ${__VU}: Logged in, profile-id=${profileId}`);

  sleep(1);

  // ---- Step 2: Get profile ----
  const profileRes = client.getProfile();
  if (!assertOk(profileRes, "get-profile")) {
    fail("get-profile failed");
  }

  sleep(0.5);

  // ---- Step 3: Get teams ----
  const teamsRes = client.getTeams();
  if (!assertOk(teamsRes, "get-teams")) {
    fail("get-teams failed");
  }
  const teams = teamsRes.body;
  // teams is an array; the user has a default team from registration
  const defaultTeamId = Array.isArray(teams) && teams.length > 0
    ? teams[0].id
    : null;

  if (!defaultTeamId) {
    fail("No default team found after registration");
  }

  sleep(0.5);

  // ---- Step 4: Create a project ----
  const projectName = `Perf Project ${uuidv4().substring(0, 8)}`;
  const projectRes = client.createProject(defaultTeamId, projectName);
  if (!assertOk(projectRes, "create-project")) {
    fail("create-project failed");
  }
  const project = projectRes.body;
  const projectId = project.id;
  console.log(`VU ${__VU}: Created project: ${projectId}`);

  sleep(1);

  // ---- Step 5: Create a file ----
  const fileName = `Perf File ${uuidv4().substring(0, 8)}`;
  const fileRes = client.createFile(projectId, fileName);
  if (!assertOk(fileRes, "create-file")) {
    fail("create-file failed");
  }
  const file = fileRes.body;
  const fileId = file.id;
  console.log(`VU ${__VU}: Created file: ${fileId}`);

  sleep(1);

  // ---- Step 6: Get the file (to read revn, vern, page-id) ----
  const getFileRes = client.getFile(fileId);
  if (!assertOk(getFileRes, "get-file")) {
    fail("get-file failed");
  }
  const fileData = getFileRes.body;
  const revn = fileData.revn;
  const vern = fileData.vern;

  // Extract the first page-id from the file data
  // fileData.data.pages is an array of page UUIDs
  // fileData.data.pages-index is a map of page-id -> page objects
  let pageId = null;
  if (fileData.data && fileData.data.pages && fileData.data.pages.length > 0) {
    pageId = fileData.data.pages[0];
  }

  if (!pageId) {
    console.warn(`VU ${__VU}: Could not find page-id in file data, skipping update-file`);
  }

  sleep(1);

  // ---- Step 7: Update file (add a rectangle shape) ----
  let updateOk = false;
  if (pageId) {
    const changes = [makeAddRectChange(pageId)];
    const updateRes = client.updateFile(fileId, revn, vern, client.sessionId, changes);

    if (updateRes.status === 200) {
      updateOk = true;
      console.log(`VU ${__VU}: Updated file successfully`);
    } else {
      // Check for revn conflict — retry once
      const body = updateRes.body;
      console.error(`VU ${__VU}: update-file failed: status=${updateRes.status} body=${JSON.stringify(body).substring(0, 500)}`);
      const isRevnConflict =
        body && (body.code === "revn-conflict" || body.type === "revn-conflict");

      if (isRevnConflict) {
        console.log(`VU ${__VU}: Revn conflict, retrying...`);
        // Fetch latest file state
        const retryFileRes = client.getFile(fileId);
        if (assertOk(retryFileRes, "get-file (retry)")) {
          const retryData = retryFileRes.body;
          const retryRes = client.updateFile(
            fileId,
            retryData.revn,
            retryData.vern,
            client.sessionId,
            changes
          );
          if (assertOk(retryRes, "update-file (retry)")) {
            updateOk = true;
            console.log(`VU ${__VU}: Updated file on retry`);
          }
        }
      } else {
        console.error(
          `VU ${__VU}: update-file failed: status=${updateRes.status} body=${JSON.stringify(body).substring(0, 300)}`
        );
      }
    }
  }

  sleep(1);

  // ---- Step 8: Upload images to the file ----
  // Small image (97 B) uses direct multipart upload.
  // Large image (120 KB) uses chunked upload (create-session → upload-chunk × N → assemble).
  if (testImageSmall && testImageSmall.byteLength > 0) {
    const uploadRes = client.uploadFileMediaObject(
      fileId,
      testImageSmall,
      "test-small.png",
      "image/png"
    );
    if (assertOk(uploadRes, "upload-file-media-object (direct)")) {
      console.log(`VU ${__VU}: Uploaded small image (direct)`);
    }
  }

  sleep(0.5);

  if (testImageLarge && testImageLarge.byteLength > 0) {
    const uploadRes = client.uploadFileMediaObject(
      fileId,
      testImageLarge,
      "test-large.png",
      "image/png"
    );
    if (assertOk(uploadRes, "upload-file-media-object (chunked)")) {
      console.log(`VU ${__VU}: Uploaded large image (chunked)`);
    }
  }

  sleep(1);

  // ---- Step 9: Delete the file ----
  const deleteFileRes = client.deleteFile(fileId);
  if (assertOk(deleteFileRes, "delete-file")) {
    console.log(`VU ${__VU}: Deleted file: ${fileId}`);
  }

  sleep(0.5);

  // ---- Step 10: Delete the project ----
  const deleteProjectRes = client.deleteProject(projectId);
  if (assertOk(deleteProjectRes, "delete-project")) {
    console.log(`VU ${__VU}: Deleted project: ${projectId}`);
  }

  sleep(0.5);

  // ---- Step 11: Delete the team ----
  // Note: We only delete the team if it's NOT the default team.
  // The default team cannot be deleted (or may cause errors).
  // For this test, we skip team deletion to avoid errors.
  // In a real scenario, we'd create a separate team and delete that.
  console.log(`VU ${__VU}: Skipping team deletion (using default team)`);

  sleep(0.5);

  // ---- Step 12: Logout ----
  const logoutRes = client.logout(profileId);
  console.log(`VU ${__VU}: Logout status: ${logoutRes.status}`);

  console.log(`VU ${__VU}: Lifecycle complete`);
}

// ---------------------------------------------------------------------------
// Teardown — runs once after all VUs finish
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Lifecycle test complete.");
}
