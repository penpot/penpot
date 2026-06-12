// Font Upload Performance Test
//
// Tests the font upload flow: chunked upload of a TTF file followed by
// creating a font variant. This exercises the storage pipeline and
// font processing (FontForge/WOFF conversion).
//
// Flow:
//   1. Register → login → get team
//   2. Chunked upload of font-1.ttf (68 KB, 2 chunks at 50 KB)
//   3. create-font-variant with the uploaded session
//
// Usage:
//   k6 run scripts/font-upload.js
//   k6 run --vus 3 --iterations 2 scripts/font-upload.js

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
// Test Data — load font fixtures from backend test files
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
// Setup
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`Penpot Font Upload Test`);
  console.log(`  Base URL: ${BASE_URL}`);
  console.log(`  Font TTF: ${fontTtf.byteLength} B`);
  console.log(`  Font OTF: ${fontOtf.byteLength} B`);
  console.log(``);

  const client = createClient(BASE_URL);

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
  const teamId = loginRes.body.defaultTeamId;

  sleep(0.5);

  // Get teams (to confirm team-id)
  const teamsRes = client.getTeams();
  if (!assertOk(teamsRes, "get-teams")) fail("get-teams failed");
  const confirmedTeamId = teamsRes.body[0].id;

  // Upload TTF via chunked upload
  const fontId = uuidv4();
  const fontFamily = `PerfFont-${uuidv4().substring(0, 8)}`;

  console.log(`VU ${__VU}: Uploading font "${fontFamily}" (font-id: ${fontId})`);

  // Create upload session for TTF
  const chunkSize = 50 * 1024; // 50 KB — matches client default
  const ttfChunks = Math.ceil(fontTtf.byteLength / chunkSize);

  const sessionRes = client.createUploadSession(ttfChunks);
  if (!assertOk(sessionRes, "create-upload-session (ttf)")) fail("create-upload-session failed");
  const ttfSessionId = sessionRes.sessionId;

  sleep(0.2);

  // Upload TTF chunks
  for (let i = 0; i < ttfChunks; i++) {
    const start = i * chunkSize;
    const end = Math.min(start + chunkSize, fontTtf.byteLength);
    const chunk = fontTtf.slice(start, end);

    const chunkRes = client.uploadChunk(ttfSessionId, i, chunk, "font-1.ttf", "font/ttf");
    if (!assertOk(chunkRes, `upload-chunk (ttf ${i}/${ttfChunks})`)) {
      fail(`Chunk upload failed at index ${i}`);
    }

    sleep(0.1);
  }

  console.log(`VU ${__VU}: Uploaded TTF in ${ttfChunks} chunks`);

  // Upload OTF via chunked upload (separate session)
  const otfChunks = Math.ceil(fontOtf.byteLength / chunkSize);

  const otfSessionRes = client.createUploadSession(otfChunks);
  if (!assertOk(otfSessionRes, "create-upload-session (otf)")) fail("create-upload-session (otf) failed");
  const otfSessionId = otfSessionRes.sessionId;

  sleep(0.2);

  for (let i = 0; i < otfChunks; i++) {
    const start = i * chunkSize;
    const end = Math.min(start + chunkSize, fontOtf.byteLength);
    const chunk = fontOtf.slice(start, end);

    const chunkRes = client.uploadChunk(otfSessionId, i, chunk, "font-1.otf", "font/otf");
    if (!assertOk(chunkRes, `upload-chunk (otf ${i}/${otfChunks})`)) {
      fail(`OTF chunk upload failed at index ${i}`);
    }

    sleep(0.1);
  }

  console.log(`VU ${__VU}: Uploaded OTF in ${otfChunks} chunks`);

  sleep(0.5);

  // Create font variant
  const variantRes = client.rpc("POST", "create-font-variant", {
    "team-id": confirmedTeamId,
    "font-id": fontId,
    "font-family": fontFamily,
    "font-weight": 400,
    "font-style": "normal",
    uploads: {
      "font/ttf": ttfSessionId,
      "font/otf": otfSessionId,
    },
  });

  if (!assertOk(variantRes, "create-font-variant")) {
    fail("create-font-variant failed");
  }

  console.log(`VU ${__VU}: Created font variant "${fontFamily}" (weight: 400, style: normal)`);

  // Verify by fetching font variants
  sleep(0.5);
  const getVariantsRes = client.rpc("GET", "get-font-variants", {
    "team-id": confirmedTeamId,
  });
  if (assertOk(getVariantsRes, "get-font-variants")) {
    const variants = getVariantsRes.json();
    console.log(`VU ${__VU}: Team has ${Array.isArray(variants) ? variants.length : "?"} font variants`);
  }

  console.log(`VU ${__VU}: Font upload test complete`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log("Font upload test complete.");
}
