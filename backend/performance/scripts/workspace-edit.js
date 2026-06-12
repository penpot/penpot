// Workspace Edit Performance Test (Write-heavy)
//
// Simulates users editing files — repeatedly fetching the file (to get
// the latest revn) and submitting changes. Each VU edits its own file
// in its own project independently, so there are no concurrency conflicts.
//
// setup() creates N demo profiles + per-user project.
// Each VU picks its user, creates its own file, and edits it in a loop.
//
// Flow (per VU):
//   Login → create file → loop: get-file → update-file → sleep
//
// Usage:
//   k6 run scripts/workspace-edit.js
//   k6 run --vus 100 --iterations 50 scripts/workspace-edit.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";
const EDIT_ITERATIONS = parseInt(__ENV.PENPOT_EDIT_ITERATIONS || "50");

export const options = {
  thresholds: {
    http_req_duration: ["p(95)<5000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:get-file}": ["p(95)<500"],
    "http_req_duration{rpc_command:update-file}": ["p(95)<2000"],
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

function makeAddRectChange(pageId, index) {
  const shapeId = uuidv4();
  const x = 50 + (index % 10) * 30;
  const y = 50 + Math.floor(index / 10) * 30;
  const w = 100;
  const h = 80;

  return {
    type: "add-obj",
    pageId: pageId,
    id: shapeId,
    frameId: pageId,
    parentId: pageId,
    obj: {
      id: shapeId, type: "rect", name: `Shape ${index}`,
      x, y, width: w, height: h,
      fillColor: "#00ff00", fillOpacity: 0.8,
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
// Setup — create N users, each with their own project
// ---------------------------------------------------------------------------

export function setup() {
  const vuCount = parseInt(__ENV.K6_VUS) || 1;

  console.log(`Penpot Workspace Edit Test`);
  console.log(`  Base URL:        ${BASE_URL}`);
  console.log(`  VUs:             ${vuCount}`);
  console.log(`  Edit iterations: ${EDIT_ITERATIONS}`);
  console.log(``);

  const client = createClient(BASE_URL);

  if (client.getProfile().status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  // Create N demo profiles + per-user project.
  // Each user needs their own project because demo profiles
  // belong to different teams and cannot share a project.
  const users = [];
  for (let i = 0; i < vuCount; i++) {
    const res = client.rpc("POST", "create-demo-profile", {});
    if (res.status !== 200) fail(`Failed to create demo profile ${i + 1}/${vuCount}`);
    const user = res.json();

    // Login as this user and create their own project
    const loginRes = client.login(user.email, user.password);
    if (loginRes.status !== 200) fail(`Login failed for user ${i + 1}`);
    const teamId = client.getTeams().body[0].id;
    user.projectId = client.createProject(teamId, `WS Edit Project`).body.id;

    users.push(user);
  }
  console.log(`  Created ${users.length} demo profiles + projects`);

  return { baseUrl: BASE_URL, users };
}

// ---------------------------------------------------------------------------
// Main VU Function — each VU creates its own file and edits it
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Pick user from pool
  const user = data.users[__VU - 1];
  if (!user) fail(`No user for VU ${__VU}`);

  // Login
  if (!assertOk(client.login(user.email, user.password), "login")) fail("login failed");
  sleep(0.5);

  // Create a file for this VU in their own project
  const fileRes = client.createFile(user.projectId, `Edit File VU${__VU}`);
  if (!assertOk(fileRes, "create-file")) fail("create-file failed");
  const fileId = fileRes.body.id;

  // Get initial file state
  const getFileRes = client.getFile(fileId);
  if (!assertOk(getFileRes, "get-file")) fail("get-file failed");
  const pageId = getFileRes.body.data.pages[0];

  sleep(0.5);

  // Edit loop
  for (let i = 0; i < EDIT_ITERATIONS; i++) {
    // Refresh file state to get latest revn
    const refreshRes = client.getFile(fileId);
    if (!assertOk(refreshRes, "get-file")) continue;
    const { revn, vern } = refreshRes.body;

    sleep(0.3);

    // Submit a change
    const changes = [makeAddRectChange(pageId, i)];
    const updateRes = client.updateFile(fileId, revn, vern, client.sessionId, changes);

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

    sleep(1);
  }

  console.log(`VU ${__VU}: Completed ${EDIT_ITERATIONS} edits on file ${fileId}`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Workspace edit test complete.");
}
