// Font Upload Performance Test
//
// Tests the font upload flow: chunked upload of TTF + OTF files followed by
// creating a font variant. Exercises storage pipeline and font processing.
//
// setup() creates N demo profiles.
// Each VU picks its user, uploads fonts, and creates a variant.
//
// Usage:
//   k6 run scripts/font-upload.js
//   k6 run --vus 50 --iterations 5 scripts/font-upload.js

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";

export const options = {
  scenarios: {
    font_upload: {
      executor: "per-vu-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "2m",
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<15000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:create-upload-session}": ["p(95)<1000"],
    "http_req_duration{rpc_command:upload-chunk}": ["p(95)<5000"],
    "http_req_duration{rpc_command:create-font-variant}": ["p(95)<10000"],
  },
};

// ---------------------------------------------------------------------------
// Test Data
// ---------------------------------------------------------------------------

const fontTtf = open("../../backend/test/backend_tests/test_files/font-1.ttf", "b");
const fontOtf = open("../../backend/test/backend_tests/test_files/font-1.otf", "b");

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
  const vuCount = (options.scenarios.font_upload && options.scenarios.font_upload.vus) || __ENV.K6_VUS || 1;

  console.log(`Penpot Font Upload Test`);
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
  const teamId = client.getTeams().body[0].id;

  sleep(0.5);

  const fontId = uuidv4();
  const fontFamily = `PerfFont-${uuidv4().substring(0, 8)}`;
  const chunkSize = 50 * 1024; // 50 KB

  // Upload TTF via chunked upload
  const ttfChunks = Math.ceil(fontTtf.byteLength / chunkSize);
  const ttfSessionRes = client.createUploadSession(ttfChunks);
  if (!assertOk(ttfSessionRes, "create-upload-session (ttf)")) fail("create-upload-session failed");
  const ttfSessionId = ttfSessionRes.sessionId;

  for (let i = 0; i < ttfChunks; i++) {
    const chunk = fontTtf.slice(i * chunkSize, Math.min((i + 1) * chunkSize, fontTtf.byteLength));
    if (!assertOk(client.uploadChunk(ttfSessionId, i, chunk, "font-1.ttf", "font/ttf"), `upload-chunk ttf ${i}`)) fail("ttf chunk failed");
    sleep(0.1);
  }

  // Upload OTF via chunked upload
  const otfChunks = Math.ceil(fontOtf.byteLength / chunkSize);
  const otfSessionRes = client.createUploadSession(otfChunks);
  if (!assertOk(otfSessionRes, "create-upload-session (otf)")) fail("create-upload-session (otf) failed");
  const otfSessionId = otfSessionRes.sessionId;

  for (let i = 0; i < otfChunks; i++) {
    const chunk = fontOtf.slice(i * chunkSize, Math.min((i + 1) * chunkSize, fontOtf.byteLength));
    if (!assertOk(client.uploadChunk(otfSessionId, i, chunk, "font-1.otf", "font/otf"), `upload-chunk otf ${i}`)) fail("otf chunk failed");
    sleep(0.1);
  }

  sleep(0.5);

  // Create font variant
  if (!assertOk(client.rpc("POST", "create-font-variant", {
    "team-id": teamId,
    "font-id": fontId,
    "font-family": fontFamily,
    "font-weight": 400,
    "font-style": "normal",
    uploads: { "font/ttf": ttfSessionId, "font/otf": otfSessionId },
  }), "create-font-variant")) fail("create-font-variant failed");

  console.log(`VU ${__VU}: Font "${fontFamily}" created`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Font upload test complete.");
}
