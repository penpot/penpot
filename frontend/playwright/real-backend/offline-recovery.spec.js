import { test, expect } from "@playwright/test";

// Recovery of unsaved edits against a real backend. Each test signs in as a
// fresh demo profile, creates a file, edits it in a tab whose saves do not get
// through, closes that tab, and opens the file again in a second tab of the
// same browser context (shared IndexedDB and Web Locks). Every assertion on
// the outcome reads the file back from the backend.

const UPDATE_FILE = "**/api/main/methods/update-file?*";
const DAY_MS = 24 * 60 * 60 * 1000;
const EDITED_NAME = "Edited offline";

async function rpc(request, method, params = {}) {
  const response = await request.post(`/api/main/methods/${method}`, {
    data: params,
    headers: { accept: "application/json" },
  });
  expect(response.ok(), `${method} failed: ${await response.text()}`).toBe(
    true,
  );
  return response.json();
}

/** The file as stored by the backend, in readable transit. */
async function storedFile(request, fileId) {
  const response = await request.get(
    `/api/main/methods/get-file?id=${fileId}`,
    { headers: { accept: "application/transit+json" } },
  );
  expect(response.ok(), await response.text()).toBe(true);
  const body = await response.text();
  // Readable transit writes maps as objects ("~:revn":N) or arrays ("~:revn",N).
  const revn = Number(/"~:revn"\s*[:,]\s*(\d+)/.exec(body)?.[1]);
  expect(Number.isInteger(revn), "file revn in response").toBe(true);
  return { body, revn };
}

/** Signs the context in as a new demo profile and creates an empty file. */
async function setupDesigner(context, testInfo) {
  const request = context.request;
  const { email, password } = await rpc(request, "create-demo-profile", {
    skipOnboarding: true,
  });
  await rpc(request, "login-with-password", { email, password });
  const profile = await rpc(request, "get-profile");
  const name = `Outbox e2e ${testInfo.testId} ${Date.now()}`;
  const file = await rpc(request, "create-file", {
    name,
    projectId: profile.defaultProjectId,
  });
  return {
    request,
    teamId: profile.defaultTeamId,
    projectId: profile.defaultProjectId,
    fileId: file.id,
    fileName: name,
  };
}

async function openFile(context, { teamId, fileId }) {
  const page = await context.newPage();
  await page.goto(`/?screen=workspace&team-id=${teamId}&file-id=${fileId}`);
  await expect(page.getByTestId("viewport")).toBeVisible({ timeout: 60000 });
  await expect(page).toHaveURL(/page-id=/);
  const pageId = new URL(page.url()).searchParams.get("page-id");
  return { page, pageId };
}

function outboxRowCount(page) {
  return page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open("penpot-outbox");
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          if (!db.objectStoreNames.contains("commits")) {
            db.close();
            resolve(0);
            return;
          }
          const request = db
            .transaction("commits", "readonly")
            .objectStore("commits")
            .count();
          request.onsuccess = () => {
            db.close();
            resolve(request.result);
          };
          request.onerror = () => reject(request.error);
        };
      }),
  );
}

function ageOutboxRows(page, ms) {
  return page.evaluate(
    (ms) =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open("penpot-outbox");
        open.onsuccess = () => {
          const db = open.result;
          const tx = db.transaction("commits", "readwrite");
          const store = tx.objectStore("commits");
          const all = store.getAll();
          all.onsuccess = () => {
            for (const row of all.result) {
              store.put({ ...row, createdAt: row.createdAt - ms });
            }
          };
          tx.oncomplete = () => {
            db.close();
            resolve();
          };
          tx.onerror = () => reject(tx.error);
        };
      }),
    ms,
  );
}

/**
 * Renames the first page in a tab whose saves are handled by `onSave`, waits
 * until the edit is in the outbox and closes the tab without unloading.
 */
async function editAndAbandon(context, designer, onSave, { ageMs = 0 } = {}) {
  const { page, pageId } = await openFile(context, designer);
  await page.route(UPDATE_FILE, onSave);

  const pageItem = page.getByTestId(`page-${pageId}`);
  await pageItem.dblclick();
  const input = pageItem.locator("input");
  await expect(input).toBeVisible();
  await input.fill(EDITED_NAME);
  await page.keyboard.press("Enter");

  await expect.poll(() => outboxRowCount(page), { timeout: 30000 }).toBe(1);
  if (ageMs > 0) {
    await ageOutboxRows(page, ageMs);
  }
  await page.close({ runBeforeUnload: false });
}

const lostConnection = (route) => route.abort("internetdisconnected");

test("An edit that never reached the backend is saved by the next tab", async ({
  context,
}, testInfo) => {
  const designer = await setupDesigner(context, testInfo);
  const before = await storedFile(designer.request, designer.fileId);

  await editAndAbandon(context, designer, lostConnection);
  expect(
    (await storedFile(designer.request, designer.fileId)).body,
  ).not.toContain(EDITED_NAME);

  const { page } = await openFile(context, designer);
  await expect(
    page.getByText("Recovered 1 unsaved changes from a previous session."),
  ).toBeVisible();

  const after = await storedFile(designer.request, designer.fileId);
  expect(after.body).toContain(EDITED_NAME);
  expect(after.revn).toBe(before.revn + 1);

  // The reloaded workspace shows the recovered edit.
  await expect(page.getByTestId("page-name").first()).toHaveText(EDITED_NAME);
  await expect.poll(() => outboxRowCount(page)).toBe(0);
});

test("An edit the backend applied but never acknowledged is not applied twice", async ({
  context,
}, testInfo) => {
  const designer = await setupDesigner(context, testInfo);
  const before = await storedFile(designer.request, designer.fileId);

  // The request reaches the backend, the response never reaches the page.
  let delivered = 0;
  await editAndAbandon(context, designer, async (route) => {
    await route.fetch();
    delivered += 1;
    await route.abort("internetdisconnected");
  });
  expect(delivered).toBeGreaterThan(0);

  const applied = await storedFile(designer.request, designer.fileId);
  expect(applied.body).toContain(EDITED_NAME);
  expect(applied.revn).toBe(before.revn + 1);

  const { page } = await openFile(context, designer);
  await expect(
    page.getByText("Recovered 1 unsaved changes from a previous session."),
  ).toBeVisible();
  await expect.poll(() => outboxRowCount(page)).toBe(0);

  // The resend carried the same commit id: the backend answered it from its
  // record instead of applying the change again.
  const after = await storedFile(designer.request, designer.fileId);
  expect(after.revn).toBe(applied.revn);
});

test("An edit too old to send again is restored in a copy of the file", async ({
  context,
}, testInfo) => {
  const designer = await setupDesigner(context, testInfo);

  await editAndAbandon(context, designer, lostConnection, {
    ageMs: 3 * DAY_MS,
  });

  const { page } = await openFile(context, designer);
  await expect(
    page.getByText(/can no longer be added to this file safely/),
  ).toBeVisible();
  await page.getByRole("button", { name: "Restore as a copy" }).click();

  const copyName = `${designer.fileName} (recovered changes)`;
  await expect(
    page.getByText(`Unsaved changes restored in "${copyName}".`),
  ).toBeVisible();

  const files = await rpc(designer.request, "get-project-files", {
    projectId: designer.projectId,
  });
  const copy = files.find((file) => file.name === copyName);
  expect(copy, `a file named ${copyName}`).toBeTruthy();

  expect((await storedFile(designer.request, copy.id)).body).toContain(
    EDITED_NAME,
  );
  expect(
    (await storedFile(designer.request, designer.fileId)).body,
  ).not.toContain(EDITED_NAME);
  await expect.poll(() => outboxRowCount(page)).toBe(0);
});
