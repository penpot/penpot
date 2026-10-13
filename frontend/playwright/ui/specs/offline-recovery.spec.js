import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

// Edits that could not be saved survive the tab that made them: they are kept
// in the IndexedDB outbox and sent again by the next tab that opens the file.
// Both tabs share one browser context, so they share IndexedDB and Web Locks,
// like two tabs of the same browser.

const OUTBOX_DB = "penpot-outbox";
const OUTBOX_STORE = "commits";
const UPDATE_FILE = "**/api/main/methods/update-file?*";
const DAY_MS = 24 * 60 * 60 * 1000;

/** Rows currently stored in the outbox of `page`'s origin. */
function outboxRows(page) {
  return page.evaluate(
    ([dbName, storeName]) =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open(dbName);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          if (!db.objectStoreNames.contains(storeName)) {
            db.close();
            resolve([]);
            return;
          }
          const request = db
            .transaction(storeName, "readonly")
            .objectStore(storeName)
            .getAll();
          request.onerror = () => reject(request.error);
          request.onsuccess = () => {
            db.close();
            resolve(
              request.result.map(({ id, fileId, createdAt }) => ({
                id,
                fileId,
                createdAt,
              })),
            );
          };
        };
      }),
    [OUTBOX_DB, OUTBOX_STORE],
  );
}

/** Moves every stored row `ms` into the past. */
function ageOutboxRows(page, ms) {
  return page.evaluate(
    ([dbName, storeName, ms]) =>
      new Promise((resolve, reject) => {
        const open = indexedDB.open(dbName);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const tx = db.transaction(storeName, "readwrite");
          const store = tx.objectStore(storeName);
          const request = store.getAll();
          request.onsuccess = () => {
            for (const row of request.result) {
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
    [OUTBOX_DB, OUTBOX_STORE, ms],
  );
}

/** Opens a workspace tab on the blank file of the fixtures. */
async function openWorkspace(context) {
  const page = await context.newPage();
  await WorkspacePage.init(page);
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  return workspacePage;
}

/**
 * Records the `update-file` requests of `page` and answers them as saved.
 * Registered after `setupEmptyFile`, so this route wins.
 */
async function recordSaves(page) {
  const saves = [];
  await page.route(UPDATE_FILE, (route) => {
    const request = route.request();
    saves.push({ url: request.url(), body: request.postData() ?? "" });
    return route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: "playwright/data/workspace/update-file-empty.json",
    });
  });
  return saves;
}

/**
 * In a tab whose saves cannot reach the backend, renames the current page and
 * closes the tab once the edit is in the outbox. Returns the stored row.
 */
async function leaveUnsavedEdit(context, { ageMs = 0 } = {}) {
  const workspacePage = await openWorkspace(context);
  const page = workspacePage.page;

  // The connection is gone: every save fails at the transport.
  await page.route(UPDATE_FILE, (route) => route.abort("internetdisconnected"));

  await workspacePage.goToWorkspace();

  const pageItem = page.getByTestId(`page-${workspacePage.pageId}`);
  await pageItem.dblclick();
  const input = pageItem.locator("input");
  await expect(input).toBeVisible();
  await input.fill("Edited offline");
  await page.keyboard.press("Enter");

  await expect.poll(() => outboxRows(page), { timeout: 15000 }).toHaveLength(1);
  const [row] = await outboxRows(page);
  expect(row.fileId).toBe(workspacePage.fileId);

  if (ageMs > 0) {
    await ageOutboxRows(page, ageMs);
  }

  // Closing without `beforeunload`, like a crash or a discarded tab.
  await page.close({ runBeforeUnload: false });
  return { row, fileId: workspacePage.fileId };
}

test("An edit left unsaved by a closed tab is sent again when the file opens", async ({
  context,
}) => {
  const { row, fileId } = await leaveUnsavedEdit(context);

  const workspacePage = await openWorkspace(context);
  const page = workspacePage.page;
  const saves = await recordSaves(page);

  await workspacePage.goToWorkspace();

  // The stored edit is sent under its original commit id, so the backend can
  // tell it apart from a new one if it had already been applied.
  await expect
    .poll(() => saves.filter((save) => save.body.includes(`~u${row.id}`)), {
      timeout: 15000,
    })
    .toHaveLength(1);

  const [replay] = saves.filter((save) => save.body.includes(`~u${row.id}`));
  expect(replay.url).toContain(fileId);
  expect(replay.body).toContain("Edited offline");

  await expect(
    page.getByText("Recovered 1 unsaved changes from a previous session."),
  ).toBeVisible();
  await expect.poll(() => outboxRows(page)).toHaveLength(0);
});

test("An edit too old to send safely is restored in a copy of the file", async ({
  context,
}) => {
  const copyId = "c7ce0794-0992-8105-8004-38f2deadbeef";
  const { row, fileId } = await leaveUnsavedEdit(context, {
    ageMs: 3 * DAY_MS,
  });

  const workspacePage = await openWorkspace(context);
  const page = workspacePage.page;
  const saves = await recordSaves(page);

  const duplicates = [];
  await page.route("**/api/main/methods/duplicate-file*", (route) => {
    duplicates.push(route.request().postData() ?? "");
    return route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      body: JSON.stringify({
        "~:id": `~u${copyId}`,
        "~:name": "New File 1 (recovered changes)",
        "~:revn": 0,
        "~:vern": 0,
        "~:features": { "~#set": [] },
      }),
    });
  });

  await workspacePage.goToWorkspace();

  // Nothing is sent to the original file; the user is asked instead.
  await expect(
    page.getByText(/can no longer be added to this file safely/),
  ).toBeVisible();
  expect(
    saves.filter((save) => save.body.includes(`~u${row.id}`)),
  ).toHaveLength(0);

  await page.getByRole("button", { name: "Restore as a copy" }).click();

  await expect.poll(() => duplicates, { timeout: 15000 }).toHaveLength(1);
  expect(duplicates[0]).toContain(fileId);

  await expect
    .poll(() => saves.filter((save) => save.url.includes(copyId)), {
      timeout: 15000,
    })
    .toHaveLength(1);
  const [copySave] = saves.filter((save) => save.url.includes(copyId));
  expect(copySave.body).toContain("Edited offline");

  // The original file never receives the stored edit.
  expect(
    saves.filter(
      (save) =>
        save.url.includes(fileId) && save.body.includes("Edited offline"),
    ),
  ).toHaveLength(0);

  await expect(
    page.getByText(
      'Unsaved changes restored in "New File 1 (recovered changes)".',
    ),
  ).toBeVisible();
  await expect.poll(() => outboxRows(page)).toHaveLength(0);
});
