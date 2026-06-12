// Workspace Open Performance Test (Read-heavy)
//
// Simulates a user opening a file in the workspace editor.
// This is the most common read-heavy operation — loading a file and its
// dependencies (libraries, thumbnails).
//
// Flow:
//   1. Register (demo profile)
//   2. Login
//   3. Get teams → default team
//   4. Create project → create file (setup data)
//   5. Update file with a shape (so the file has data)
//   6. Loop: get-file → get-file-libraries → get-file-object-thumbnails
//           → get-file-data-for-thumbnail
//
// Usage:
//   k6 run scripts/workspace-open.js
//   k6 run --env PENPOT_BASE_URL=http://localhost:6060 scripts/workspace-open.js
//   k6 run --vus 10 --iterations 5 scripts/workspace-open.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";
const OPEN_ITERATIONS = parseInt(__ENV.PENPOT_OPEN_ITERATIONS || "5");

export const options = {
  scenarios: {
    workspace_open: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "2m",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<5000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:get-file}": ["p(95)<500"],
    "http_req_duration{rpc_command:get-file-libraries}": ["p(95)<500"],
    "http_req_duration{rpc_command:get-file-object-thumbnails}": ["p(95)<500"],
    "http_req_duration{rpc_command:get-file-data-for-thumbnail}": ["p(95)<500"],
  },
};

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
// Setup — create a file with data that we'll open repeatedly
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`Penpot Workspace Open Test`);
  console.log(`  Base URL:         ${BASE_URL}`);
  console.log(`  Open iterations:  ${OPEN_ITERATIONS}`);
  console.log(``);

  const client = createClient(BASE_URL);

  // Small random delay to avoid demo profile creation race with parallel scripts
  sleep(Math.random() * 3);

  // Create demo profile
  const demoRes = client.rpc("POST", "create-demo-profile", {});
  if (demoRes.status !== 200) {
    fail("Failed to create demo profile — is demo-users flag enabled?");
  }
  const demoBody = demoRes.json();
  console.log(`  Created demo profile: ${demoBody.email}`);

  // Login
  const loginRes = client.login(demoBody.email, demoBody.password);
  if (loginRes.status !== 200) fail("Login failed");

  // Get default team
  const teamsRes = client.getTeams();
  if (teamsRes.status !== 200) fail("get-teams failed");
  const teamId = teamsRes.body[0].id;

  // Create project
  const projRes = client.createProject(teamId, "WS Open Project");
  if (projRes.status !== 200) fail("create-project failed");
  const projectId = projRes.body.id;

  // Create file
  const fileRes = client.createFile(projectId, "WS Open File");
  if (fileRes.status !== 200) fail("create-file failed");
  const fileId = fileRes.body.id;

  // Get file to read page-id, revn, vern
  const getFileRes = client.getFile(fileId);
  if (getFileRes.status !== 200) fail("get-file failed");
  const fileData = getFileRes.body;
  const pageId = fileData.data.pages[0];

  // Add a shape so the file has meaningful data
  const shapeId = uuidv4();
  const x = 50;
  const y = 50;
  const w = 300;
  const h = 200;
  const changes = [{
    type: "add-obj",
    pageId: pageId,
    id: shapeId,
    frameId: pageId,
    parentId: pageId,
    obj: {
      id: shapeId, type: "rect", name: "Background",
      x, y, width: w, height: h,
      fillColor: "#cccccc", fillOpacity: 1,
      rotation: 0, hidden: false, locked: false,
      selrect: { x, y, width: w, height: h, x1: x, y1: y, x2: x + w, y2: y + h },
      points: [
        { x, y }, { x: x + w, y }, { x: x + w, y: y + h }, { x, y: y + h },
      ],
      transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      transformInverse: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      parentId: pageId, frameId: pageId,
    },
  }];
  const updateRes = client.updateFile(fileId, fileData.revn, fileData.vern, client.sessionId, changes);
  if (updateRes.status !== 200) fail("update-file (seed shape) failed");

  console.log(`  File ready: ${fileId} (page: ${pageId})`);

  return {
    baseUrl: BASE_URL,
    fileId,
    projectId,
    teamId,
    profileId: loginRes.body.id,
    email: demoBody.email,
    password: demoBody.password,
  };
}

// ---------------------------------------------------------------------------
// Main VU Function
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Small random delay to spread out concurrent VU logins
  sleep(Math.random() * 2);

  // Login with the same profile that owns the file
  const loginRes = client.login(data.email, data.password);
  if (!assertOk(loginRes, "login")) fail("login failed");

  sleep(0.5);

  for (let i = 0; i < OPEN_ITERATIONS; i++) {
    // 1. Get file
    const getFileRes = client.getFile(data.fileId);
    if (!assertOk(getFileRes, "get-file")) fail("get-file failed");

    sleep(0.3);

    // 2. Get file libraries
    const libsRes = client.getFileLibraries(data.fileId);
    if (!assertOk(libsRes, "get-file-libraries")) fail("get-file-libraries failed");

    sleep(0.2);

    // 3. Get object thumbnails
    const thumbsRes = client.getFileObjectThumbnails(data.fileId);
    if (!assertOk(thumbsRes, "get-file-object-thumbnails")) fail("get-file-object-thumbnails failed");

    sleep(0.2);

    // 4. Get file data for thumbnail
    const thumbDataRes = client.getFileDataForThumbnail(data.fileId);
    if (!assertOk(thumbDataRes, "get-file-data-for-thumbnail")) fail("get-file-data-for-thumbnail failed");

    sleep(1);
  }

  console.log(`VU ${__VU}: Completed ${OPEN_ITERATIONS} workspace open iterations`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Workspace open test complete.");
}
