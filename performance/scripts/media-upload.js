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
// Flow:
//   1. Register → login → create project → create file
//   2. Upload SVG (direct)
//   3. Upload PNG (direct)
//   4. Upload JPG (chunked)
//
// Usage:
//   k6 run scripts/media-upload.js
//   k6 run --vus 5 --iterations 3 scripts/media-upload.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";

export const options = {
  scenarios: {
    media_upload: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "2m",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<10000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:upload-file-media-object}": ["p(95)<5000"],
    "http_req_duration{rpc_command:upload-chunk}": ["p(95)<5000"],
    "http_req_duration{rpc_command:assemble-file-media-object}": ["p(95)<5000"],
  },
};

// ---------------------------------------------------------------------------
// Test Data — load fixtures from backend test files
// ---------------------------------------------------------------------------

const imageSvg = open("../../backend/test/backend_tests/test_files/sample1.svg", "b");
const imagePng = open("../../backend/test/backend_tests/test_files/sample.png", "b");
const imageJpg = open("../../backend/test/backend_tests/test_files/sample.jpg", "b");

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
// Setup
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`Penpot Media Upload Test`);
  console.log(`  Base URL: ${BASE_URL}`);
  console.log(`  Fixtures:`);
  console.log(`    SVG: ${imageSvg.byteLength} B`);
  console.log(`    PNG: ${imagePng.byteLength} B`);
  console.log(`    JPG: ${imageJpg.byteLength} B`);
  console.log(``);

  const client = createClient(BASE_URL);

  // Verify backend is reachable
  const res = client.getProfile();
  if (res.status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  return { baseUrl: BASE_URL };
}

// ---------------------------------------------------------------------------
// Main VU Function
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Register
  const demoRes = client.rpc("POST", "create-demo-profile", {});
  if (!assertOk(demoRes, "create-demo-profile")) fail("Failed to create demo profile");
  const demo = demoRes.json();

  // Login
  const loginRes = client.login(demo.email, demo.password);
  if (!assertOk(loginRes, "login")) fail("Login failed");

  sleep(0.5);

  // Get team
  const teamsRes = client.getTeams();
  if (!assertOk(teamsRes, "get-teams")) fail("get-teams failed");
  const teamId = teamsRes.body[0].id;

  // Create project
  const projRes = client.createProject(teamId, `Media Project ${uuidv4().substring(0, 8)}`);
  if (!assertOk(projRes, "create-project")) fail("create-project failed");
  const projectId = projRes.body.id;

  // Create file
  const fileRes = client.createFile(projectId, `Media File ${uuidv4().substring(0, 8)}`);
  if (!assertOk(fileRes, "create-file")) fail("create-file failed");
  const fileId = fileRes.body.id;

  sleep(0.5);

  // Upload SVG (direct — small file)
  const svgRes = client.uploadFileMediaObject(fileId, imageSvg, "sample.svg", "image/svg+xml");
  if (!assertOk(svgRes, "upload SVG")) {
    console.error(`VU ${__VU}: SVG upload failed`);
  } else {
    console.log(`VU ${__VU}: Uploaded SVG (${imageSvg.byteLength} B, direct)`);
  }

  sleep(0.5);

  // Upload PNG (direct — small file)
  const pngRes = client.uploadFileMediaObject(fileId, imagePng, "sample.png", "image/png");
  if (!assertOk(pngRes, "upload PNG")) {
    console.error(`VU ${__VU}: PNG upload failed`);
  } else {
    console.log(`VU ${__VU}: Uploaded PNG (${imagePng.byteLength} B, direct)`);
  }

  sleep(0.5);

  // Upload JPG (chunked — 305 KB > 50 KB threshold)
  const jpgRes = client.uploadFileMediaObject(fileId, imageJpg, "sample.jpg", "image/jpeg");
  if (!assertOk(jpgRes, "upload JPG")) {
    console.error(`VU ${__VU}: JPG upload failed`);
  } else {
    console.log(`VU ${__VU}: Uploaded JPG (${imageJpg.byteLength} B, chunked)`);
  }

  console.log(`VU ${__VU}: Media upload test complete`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Media upload test complete.");
}
