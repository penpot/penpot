// Media Upload Performance Test
//
// Tests direct and chunked image uploads with varying file sizes.
// Each VU creates its own file and uploads multiple images to it.
//
// Upload sizes:
//   - SVG (3.6 KB) → direct upload
//   - PNG (5.1 KB) → direct upload
//   - JPG (305 KB) → chunked upload (7 chunks at 50 KB each)
//
// Usage:
//   k6 run scripts/media-upload.js
//   k6 run --vus 50 --iterations 5 scripts/media-upload.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";

export const options = {
  thresholds: {
    http_req_duration: ["p(95)<10000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:upload-file-media-object}": ["p(95)<5000"],
    "http_req_duration{rpc_command:upload-chunk}": ["p(95)<5000"],
    "http_req_duration{rpc_command:assemble-file-media-object}": ["p(95)<5000"],
  },
};

// ---------------------------------------------------------------------------
// Test Data
// ---------------------------------------------------------------------------
const imageSvg = open("../../test/backend_tests/test_files/sample1.svg", "b");

const imagePng = open("../../test/backend_tests/test_files/sample.png", "b");

const imageJpg = open("../../test/backend_tests/test_files/sample.jpg", "b");
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
// Setup — create user pool
// ---------------------------------------------------------------------------

export function setup() {
  const vuCount = parseInt(__ENV.K6_VUS) || 1;

  console.log(`Penpot Media Upload Test`);
  console.log(`  Base URL: ${BASE_URL}`);
  console.log(`  VUs:      ${vuCount}`);
  console.log(``);

  const client = createClient(BASE_URL);
  if (client.getProfile().status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  const users = [];
  for (let i = 0; i < vuCount; i++) {
    const res = client.rpc("POST", "create-demo-profile", {});
    if (res.status !== 200) fail(`Failed to create demo profile ${i + 1}/${vuCount}`);
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
  if (!user) fail(`No user for VU ${__VU}`);

  // Login
  if (!assertOk(client.login(user.email, user.password), "login")) fail("login failed");

  sleep(0.5);

  // Get team
  const teamId = client.getTeams().body[0].id;

  // Create project + file
  const projectId = client.createProject(teamId, `Media ${uuidv4().substring(0, 8)}`).body.id;
  const fileId = client.createFile(projectId, `Media ${uuidv4().substring(0, 8)}`).body.id;

  sleep(0.5);

  // Upload SVG (direct — 3.6 KB)
  assertOk(client.uploadFileMediaObject(fileId, imageSvg, "sample.svg", "image/svg+xml"), "upload SVG");

  sleep(0.5);

  // Upload PNG (direct — 5.1 KB)
  assertOk(client.uploadFileMediaObject(fileId, imagePng, "sample.png", "image/png"), "upload PNG");

  sleep(0.5);

  // Upload JPG (chunked — 305 KB > 50 KB threshold)
  assertOk(client.uploadFileMediaObject(fileId, imageJpg, "sample.jpg", "image/jpeg"), "upload JPG");

  console.log(`VU ${__VU}: Media upload complete`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Media upload test complete.");
}
