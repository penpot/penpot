// Workspace Edit Concurrent Performance Test
//
// Two modes for measuring concurrent file editing:
//
// Mode 1: same-file — N VUs edit different pages in 1 file
//   Measures lock contention on a single popular file.
//   Bottleneck: advisory lock serialization (db/xact-lock!).
//
// Mode 2: multi-file — G groups × M VUs per file
//   Each group edits its own file on its own page.
//   Measures whole system responsiveness under parallel edit sessions.
//   Bottleneck: DB connection pool, CPU, memory.
//
// Key insight: revn conflicts only occur when incoming > stored (should
// never happen in normal usage). The real contention point is the file-level
// advisory lock that serializes all update-file calls on the same file.
//
// Usage:
//   # Same-file mode (default): 5 VUs edit different pages in 1 file
//   k6 run --vus 5 --iterations 10 scripts/workspace-edit-concurrent.js
//
//   # Multi-file mode: 3 files × 2 VUs each = 6 VUs total
//   PENPOT_EDIT_MODE=multi-file PENPOT_FILE_COUNT=3 PENPOT_VUS_PER_FILE=2 \
//     k6 run --vus 6 --iterations 10 scripts/workspace-edit-concurrent.js
//
//   # Via run.sh
//   ./run.sh concurrent-edit --mode same-file --vus 5 --iterations 10
//   ./run.sh concurrent-edit --mode multi-file --files 3 --vus-per-file 2 --iterations 10

import { check, sleep, fail } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { createClient } from "../lib/penpot-client.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.PENPOT_BASE_URL || "http://localhost:6060";
const EDIT_MODE = __ENV.PENPOT_EDIT_MODE || "same-file"; // "same-file" or "multi-file"
const FILE_COUNT = parseInt(__ENV.PENPOT_FILE_COUNT || "1");
const VUS_PER_FILE = parseInt(__ENV.PENPOT_VUS_PER_FILE || "1");
const EDIT_ITERATIONS = parseInt(__ENV.PENPOT_EDIT_ITERATIONS || "50");

// Calculate total VUs based on mode
const TOTAL_VUS = EDIT_MODE === "multi-file"
  ? FILE_COUNT * VUS_PER_FILE
  : parseInt(__ENV.PENPOT_TOTAL_VUS || "3");

export const options = {
  thresholds: {
    http_req_duration: ["p(95)<5000"],
    http_req_failed: ["rate<0.01"],
    "http_req_duration{rpc_command:get-file}": ["p(95)<500"],
    "http_req_duration{rpc_command:update-file}": ["p(95)<3000"],
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

// Add a page to a file via update-file with add-page change
function addPage(client, fileId, revn, vern, pageId, pageName) {
  const change = {
    type: "add-page",
    id: pageId,
    name: pageName,
  };
  return client.updateFile(fileId, revn, vern, client.sessionId, [change]);
}

// ---------------------------------------------------------------------------
// Setup — create users, files, and pages based on mode
// ---------------------------------------------------------------------------

export function setup() {
  console.log(`Penpot Concurrent Edit Test`);
  console.log(`  Base URL:        ${BASE_URL}`);
  console.log(`  Mode:            ${EDIT_MODE}`);
  console.log(`  Edit iterations: ${EDIT_ITERATIONS}`);

  if (EDIT_MODE === "same-file") {
    console.log(`  Total VUs:       ${TOTAL_VUS} (same file)`);
  } else {
    console.log(`  Files:           ${FILE_COUNT}`);
    console.log(`  VUs per file:    ${VUS_PER_FILE}`);
    console.log(`  Total VUs:       ${TOTAL_VUS}`);
  }
  console.log(``);

  const client = createClient(BASE_URL);
  if (client.getProfile().status === 0) fail(`Backend unreachable at ${BASE_URL}`);

  // Create demo profiles (one per VU)
  const users = [];
  for (let i = 0; i < TOTAL_VUS; i++) {
    const res = client.rpc("POST", "create-demo-profile", {});
    if (res.status !== 200) fail(`Failed to create demo profile ${i + 1}/${TOTAL_VUS}`);
    users.push(res.json());
  }
  console.log(`  Created ${users.length} demo profiles`);

  // Login with first user to create shared team and files
  const loginRes = client.login(users[0].email, users[0].password);
  if (loginRes.status !== 200) fail("Login failed for setup");

  // Create a shared team so all VUs can access the same file.
  // Each demo profile gets its own default team; without a shared
  // team, VUs 2+ would get 404 on get-file.
  const teamRes = client.createTeam("Concurrent Edit Team");
  if (teamRes.status !== 200) fail("Failed to create shared team");
  const sharedTeamId = teamRes.body.id;
  console.log(`  Shared team: ${sharedTeamId}`);

  // Invite remaining users to the shared team and get acceptance tokens.
  // The tokens are used by each VU via verify-token to join the team.
  const invitationTokens = [];
  for (let i = 1; i < TOTAL_VUS; i++) {
    const invRes = client.inviteTeamMembers(sharedTeamId, [users[i].email], "editor");
    if (invRes.status !== 200) {
      console.error(`  Invite user ${i}: status=${invRes.status}`);
    }
    const tokenRes = client.getTeamInvitationToken(sharedTeamId, users[i].email);
    if (tokenRes.status === 200 && tokenRes.body) {
      invitationTokens.push({ vuIndex: i, token: tokenRes.body });
    }
  }
  if (invitationTokens.length > 0) {
    console.log(`  Got ${invitationTokens.length} invitation tokens`);
  } else {
    console.log(`  All users auto-added (no tokens needed)`);
  }

  // Create project and files in the shared team
  const projectId = client.createProject(sharedTeamId, "Concurrent Edit Project").body.id;
  console.log(`  Project: ${projectId}`);

  // Build file/page assignments based on mode
  const fileAssignments = []; // [{ fileId, pageIds[] }]
  const vuAssignments = [];   // [{ vuIndex, fileId, pageId }]

  if (EDIT_MODE === "same-file") {
    // One file, N pages (one per VU)
    const fileRes = client.createFile(projectId, "Shared Edit File");
    if (fileRes.status !== 200) fail("Failed to create shared file");
    const fileId = fileRes.body.id;
    console.log(`  Created file: ${fileId}`);

    // Get initial file state (has 1 default page)
    const getFileRes = client.getFile(fileId);
    if (getFileRes.status !== 200) fail("Failed to get initial file");
    const defaultPageId = getFileRes.body.data.pages[0];
    let revn = getFileRes.body.revn;
    let vern = getFileRes.body.vern;

    // First VU uses the default page
    const pageIds = [defaultPageId];

    // Add remaining pages
    // vern never changes on regular edits (only on snapshot restore),
    // and each add-page increments revn by 1, so no need to re-fetch.
    for (let i = 1; i < TOTAL_VUS; i++) {
      const pageId = uuidv4();
      const pageName = `Page ${i + 1}`;
      const addRes = addPage(client, fileId, revn, vern, pageId, pageName);
      if (addRes.status !== 200) fail(`Failed to add page ${i + 1}`);
      revn++;
      pageIds.push(pageId);
    }
    console.log(`  Added ${pageIds.length} pages to file`);

    fileAssignments.push({ fileId, pageIds });

    // Each VU gets its own page in the same file
    for (let i = 0; i < TOTAL_VUS; i++) {
      vuAssignments.push({ vuIndex: i, fileId, pageId: pageIds[i] });
    }

  } else {
    // Multi-file mode: G files, each with M pages
    for (let f = 0; f < FILE_COUNT; f++) {
      const fileRes = client.createFile(projectId, `Edit File ${f + 1}`);
      if (fileRes.status !== 200) fail(`Failed to create file ${f + 1}`);
      const fileId = fileRes.body.id;
      console.log(`  Created file ${f + 1}: ${fileId}`);

      // Get initial file state (has 1 default page)
      const getFileRes = client.getFile(fileId);
      if (getFileRes.status !== 200) fail(`Failed to get file ${f + 1}`);
      const defaultPageId = getFileRes.body.data.pages[0];
      let revn = getFileRes.body.revn;
      let vern = getFileRes.body.vern;

      // First VU of this file uses the default page
      const pageIds = [defaultPageId];

      // Add remaining pages for this file
      for (let p = 1; p < VUS_PER_FILE; p++) {
        const pageId = uuidv4();
        const pageName = `Page ${p + 1}`;
        const addRes = addPage(client, fileId, revn, vern, pageId, pageName);
        if (addRes.status !== 200) fail(`Failed to add page ${p + 1} to file ${f + 1}`);
        revn++;
        pageIds.push(pageId);
      }
      console.log(`  Added ${pageIds.length} pages to file ${f + 1}`);

      fileAssignments.push({ fileId, pageIds });

      // Assign VUs to this file's pages
      for (let p = 0; p < VUS_PER_FILE; p++) {
        const vuIndex = f * VUS_PER_FILE + p;
        vuAssignments.push({ vuIndex, fileId, pageId: pageIds[p] });
      }
    }
  }

  console.log(`  Setup complete. ${vuAssignments.length} VU assignments.`);
  console.log(``);

  return {
    baseUrl: BASE_URL,
    editMode: EDIT_MODE,
    users,
    vuAssignments,
    invitationTokens,
  };
}

// ---------------------------------------------------------------------------
// Main VU Function — each VU edits its assigned page
// ---------------------------------------------------------------------------

// Track which VUs have accepted their invitation (once per VU, not per iteration)
const verifiedVus = {};

// ---------------------------------------------------------------------------
// Main VU Function — each VU edits its assigned page
// ---------------------------------------------------------------------------

export default function (data) {
  const client = createClient(data.baseUrl);

  // Each VU uses its own demo profile (different users editing the same file)
  const vuIndex = __VU - 1;
  const user = data.users[vuIndex];
  const assignment = data.vuAssignments[vuIndex];

  if (!user) fail(`No user for VU ${__VU} (index ${vuIndex})`);
  if (!assignment) fail(`No assignment for VU ${__VU} (index ${vuIndex})`);

  const { fileId, pageId } = assignment;

  // Login
  if (!assertOk(client.login(user.email, user.password), "login")) fail("login failed");

  // Accept team invitation once per VU (not per iteration).
  // In devenv the user may already be auto-added; 400 on already-accepted
  // tokens is harmless — skip the token on subsequent iterations.
  if (!verifiedVus[__VU]) {
    const tokenEntry = data.invitationTokens.find((t) => t.vuIndex === vuIndex);
    if (tokenEntry && tokenEntry.token) {
      client.rpc("POST", "verify-token", tokenEntry.token);
    }
    verifiedVus[__VU] = true;
  }

  sleep(0.5);

  // Edit loop
  for (let i = 0; i < EDIT_ITERATIONS; i++) {
    // Refresh file state to get latest revn
    const refreshRes = client.getFile(fileId);
    if (!assertOk(refreshRes, "get-file")) continue;
    const { revn, vern } = refreshRes.body;

    sleep(0.3);

    // Submit a change to our assigned page
    const changes = [makeAddRectChange(pageId, i)];
    const updateRes = client.updateFile(fileId, revn, vern, client.sessionId, changes);

    if (updateRes.status !== 200) {
      const body = updateRes.body;
      const isConflict = body && (body.code === "revn-conflict" || body.type === "revn-conflict");
      if (isConflict) {
        // This shouldn't happen in normal circumstances, but handle it gracefully
        console.warn(`VU ${__VU}: revn conflict on iteration ${i} (unexpected)`);
        const retryFile = client.getFile(fileId);
        if (retryFile.status === 200) {
          client.updateFile(fileId, retryFile.body.revn, retryFile.body.vern, client.sessionId, changes);
        }
      } else {
        console.error(`VU ${__VU}: update-file failed on iteration ${i}: ${JSON.stringify(body)}`);
      }
    }

    sleep(1);
  }

  console.log(`VU ${__VU}: Completed ${EDIT_ITERATIONS} edits on file ${fileId}, page ${pageId}`);
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

export function teardown(data) {
  console.log(`Concurrent edit test complete (${data.editMode}).`);
}
