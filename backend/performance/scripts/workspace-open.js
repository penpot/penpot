// Workspace Open Performance Test (Read-heavy)
//
// Simulates many users opening the same file in the workspace editor.
// This is the most common read-heavy operation — loading a file and its
// dependencies (libraries, thumbnails).
//
// setup() creates one user, one project, and one file with a shape.
// All VUs login with the same user and read the same file concurrently.
//
// Flow (per VU iteration):
//   Login → get-file → get-file-libraries → get-file-object-thumbnails
//        → get-file-data-for-thumbnail
//
// Usage:
//   k6 run scripts/workspace-open.js
//   k6 run --vus 100 --iterations 20 scripts/workspace-open.js
//   k6 run --env PENPOT_BASE_URL=http://localhost:6060 scripts/workspace-open.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";
const OPEN_ITERATIONS = parseInt(__ENV.PENPOT_OPEN_ITERATIONS || "5");

export const options = {
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
// Setup — create one user + one file with data
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`Penpot Workspace Open Test`);
  console.log(`  Base URL:         ${BASE_URL}`);
  console.log(`  Open iterations:  ${OPEN_ITERATIONS}`);
  console.log(``);

  const client = createClient(BASE_URL);

  // Verify backend reachable
  if (client.getProfile().status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  // Create one demo user
  const demoRes = client.rpc("POST", "create-demo-profile", {});
  if (demoRes.status !== 200) fail("Failed to create demo profile");
  const { email, password } = demoRes.json();

  // Login
  const loginRes = client.login(email, password);
  if (loginRes.status !== 200) fail("Login failed");

  // Create project + file
  const teamId = client.getTeams().body[0].id;
  const projectId = client.createProject(teamId, "WS Open Project").body.id;
  const fileId = client.createFile(projectId, "WS Open File").body.id;

  // Get file data and add a shape so it has meaningful content
  const fileData = client.getFile(fileId).body;
  const pageId = fileData.data.pages[0];
  const shapeId = uuidv4();
  const x = 50, y = 50, w = 300, h = 200;

  client.updateFile(fileId, fileData.revn, fileData.vern, uuidv4(), [{
    type: "add-obj", pageId, id: shapeId, frameId: pageId, parentId: pageId,
    obj: {
      id: shapeId, type: "rect", name: "Background",
      x, y, width: w, height: h,
      fillColor: "#cccccc", fillOpacity: 1,
      rotation: 0, hidden: false, locked: false,
      selrect: { x, y, width: w, height: h, x1: x, y1: y, x2: x + w, y2: y + h },
      points: [{ x, y }, { x: x + w, y }, { x: x + w, y: y + h }, { x, y: y + h }],
      transform: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      transformInverse: { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 },
      parentId: pageId, frameId: pageId,
    },
  }]);

  console.log(`  File ready: ${fileId} (page: ${pageId})`);

  return { baseUrl: BASE_URL, email, password, fileId };
}

// ---------------------------------------------------------------------------
// Main VU Function — all VUs read the same file
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Login with shared user
  if (!assertOk(client.login(data.email, data.password), "login")) fail("login failed");

  sleep(0.5);

  for (let i = 0; i < OPEN_ITERATIONS; i++) {
    if (!assertOk(client.getFile(data.fileId), "get-file")) fail("get-file failed");
    sleep(0.3);

    if (!assertOk(client.getFileLibraries(data.fileId), "get-file-libraries")) fail("get-file-libraries failed");
    sleep(0.2);

    if (!assertOk(client.getFileObjectThumbnails(data.fileId), "get-file-object-thumbnails")) fail("get-file-object-thumbnails failed");
    sleep(0.2);

    if (!assertOk(client.getFileDataForThumbnail(data.fileId), "get-file-data-for-thumbnail")) fail("get-file-data-for-thumbnail failed");
    sleep(1);
  }

  console.log(`VU ${__VU}: Completed ${OPEN_ITERATIONS} open iterations`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Workspace open test complete.");
}
