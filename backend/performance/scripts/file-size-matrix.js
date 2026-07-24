// File Size Matrix Performance Test
//
// Measures how update-file and get-file latency scales with file size.
// Creates files with different shape counts (10, 100, 500, 1000) and
// benchmarks operations on each.
//
// Usage:
//   k6 run scripts/file-size-matrix.js
//   k6 run --iterations 10 scripts/file-size-matrix.js
//   ./run.sh file-size-matrix
//   ./run.sh file-size-matrix -n 10

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";
const ITERATIONS_PER_TIER = parseInt(__ENV.PENPOT_EDIT_ITERATIONS || "5");

// Shape tiers
const TIERS = [
  { name: "small",  shapes: 10,  color: "#ff0000" },
  { name: "medium", shapes: 100, color: "#00ff00" },
  { name: "large",  shapes: 500, color: "#0000ff" },
  { name: "xlarge", shapes: 1000, color: "#ff00ff" },
];

export const options = {
  thresholds: {
    http_req_duration: ["p(95)<10000"],
    http_req_failed: ["rate<0.01"],
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

function makeAddRectChange(pageId, index, color) {
  const shapeId = uuidv4();
  const x = 50 + (index % 20) * 30;
  const y = 50 + Math.floor(index / 20) * 30;
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
      fillColor: color, fillOpacity: 0.8,
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

// Populate a file with N shapes in a single update-file call
function populateFile(client, fileId, pageId, shapeCount, color) {
  // Get current file state
  const getFileRes = client.getFile(fileId);
  if (getFileRes.status !== 200) return null;
  let { revn, vern } = getFileRes.body;

  // Add shapes in batches (backend may have limits on changes per call)
  const BATCH_SIZE = 100;
  let added = 0;

  while (added < shapeCount) {
    const batchCount = Math.min(BATCH_SIZE, shapeCount - added);
    const changes = [];
    for (let i = 0; i < batchCount; i++) {
      changes.push(makeAddRectChange(pageId, added + i, color));
    }

    const updateRes = client.updateFile(fileId, revn, vern, client.sessionId, changes);
    if (updateRes.status !== 200) {
      console.error(`Failed to add batch at ${added}: ${JSON.stringify(updateRes.body)}`);
      return null;
    }

    // update-file returns {revn, lagged} but not vern
    // vern only changes on snapshot restore, so keep the original
    revn = updateRes.body.revn;
    added += batchCount;
  }

  return { revn, vern };
}

// ---------------------------------------------------------------------------
// Setup — create files with different shape counts
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`File Size Matrix Test`);
  console.log(`  Base URL:        ${BASE_URL}`);
  console.log(`  Iterations/tier: ${ITERATIONS_PER_TIER}`);
  console.log(`  Tiers:           ${TIERS.map(t => `${t.name}(${t.shapes})`).join(", ")}`);
  console.log(``);

  const client = createClient(BASE_URL);
  if (client.getProfile().status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  // Create demo profile
  const userRes = client.rpc("POST", "create-demo-profile", {});
  if (userRes.status !== 200) fail("Failed to create demo profile");
  const user = userRes.json();
  console.log(`  Created demo profile: ${user.email}`);

  // Login
  if (client.login(user.email, user.password).status !== 200) fail("Login failed");
  const teamId = client.getTeams().body[0].id;
  const projectId = client.createProject(teamId, "File Size Matrix Project").body.id;
  console.log(`  Project: ${projectId}`);

  // Create and populate files for each tier
  const tiers = [];

  for (const tier of TIERS) {
    console.log(`\n  Creating ${tier.name} file (${tier.shapes} shapes)...`);

    // Create file
    const fileRes = client.createFile(projectId, `Matrix ${tier.name} (${tier.shapes} shapes)`);
    if (fileRes.status !== 200) fail(`Failed to create ${tier.name} file`);
    const fileId = fileRes.body.id;

    // Get page ID
    const getFileRes = client.getFile(fileId);
    if (getFileRes.status !== 200) fail(`Failed to get ${tier.name} file`);
    const pageId = getFileRes.body.data.pages[0];

    // Populate with shapes
    const startTime = Date.now();
    const result = populateFile(client, fileId, pageId, tier.shapes, tier.color);
    if (!result) fail(`Failed to populate ${tier.name} file`);
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

    console.log(`  ${tier.name}: ${tier.shapes} shapes in ${elapsed}s (revn=${result.revn})`);

    tiers.push({
      name: tier.name,
      shapes: tier.shapes,
      fileId,
      pageId,
      revn: result.revn,
      vern: result.vern,
    });
  }

  console.log(`\n  Setup complete. ${tiers.length} files ready.`);

  return { baseUrl: BASE_URL, user, tiers, iterationsPerTier: ITERATIONS_PER_TIER };
}

// ---------------------------------------------------------------------------
// Main VU Function — benchmark each tier
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Login
  if (!assertOk(client.login(data.user.email, data.user.password), "login")) fail("login failed");
  sleep(0.5);

  console.log(`\n=== Starting benchmark (${data.iterationsPerTier} iterations per tier) ===\n`);

  // Benchmark each tier
  for (const tier of data.tiers) {
    console.log(`--- Tier: ${tier.name} (${tier.shapes} shapes) ---`);

    // Get latest file state
    const getFileRes = client.getFile(tier.fileId);
    if (!assertOk(getFileRes, `get-file-${tier.name}`)) continue;
    let { revn, vern } = getFileRes.body;

    for (let i = 0; i < data.iterationsPerTier; i++) {
      // Benchmark get-file
      const getRes = client.getFile(tier.fileId);
      if (!assertOk(getRes, `get-file-${tier.name}`)) continue;

      sleep(0.2);

      // Benchmark update-file (add 1 shape)
      const change = makeAddRectChange(tier.pageId, tier.shapes + i, "#ffaa00");
      const updateRes = client.updateFile(tier.fileId, getRes.body.revn, getRes.body.vern, client.sessionId, [change]);

      if (updateRes.status !== 200) {
        console.error(`update-file failed on ${tier.name} iteration ${i}: ${JSON.stringify(updateRes.body)}`);
        continue;
      }

      // update-file returns {revn, lagged} but not vern
      // vern only changes on snapshot restore, so keep the original
      revn = updateRes.body.revn;

      sleep(0.3);
    }

    console.log(`  Completed ${data.iterationsPerTier} iterations on ${tier.name}`);
  }

  console.log(`\n=== Benchmark complete ===`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log(`File size matrix test complete.`);
}
