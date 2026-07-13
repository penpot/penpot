import { spawn, type ChildProcess } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium, type Page } from "playwright";

// Out-of-sandbox CI driver (Node + Playwright) for the composable test suite,
// following the plugin-api-test-suite's CI driver. NOTE on provenance: the mock
// harness here (the RPC fixture table, the WebSocket mock, the get-file fixture)
// exists in three places that must stay in sync — the frontend e2e harness
// (frontend/playwright, the origin), the plugin-api-test-suite driver, and this
// file. If workspace loading changes and this driver times out waiting for the
// viewport, diff against those two first. It serves the prebuilt
// frontend bundle via the frontend e2e static server, intercepts every backend
// RPC with Playwright `page.route` (reusing the frontend e2e mock fixtures),
// injects the prebuilt `headless.js` bundle into the plugin sandbox via
// `globalThis.ɵloadPlugin`, and captures the results from the page console.
//
// No backend and no login are needed: everything the suite asserts (component
// synchronization, overrides, swap slots, variants) is frontend store logic,
// executed optimistically in memory; the backend's only role is persistence,
// which the mock answers with a canned 200.
//
// Optional env:
// - TEST_FILTER: run only tests whose composite identifier contains the given
//   substring (case-insensitive), e.g. "MainEditSyncs" or "MainEditSyncs-2".
// - CI_TIMEOUT_MS: overall timeout waiting for results (default 600000).

const here = dirname(fileURLToPath(import.meta.url));
// here = <root>/plugins/apps/composable-test-suite/ci
const repoRoot = resolve(here, "../../../../");
const frontendDir = resolve(repoRoot, "frontend");
const e2eDataDir = resolve(frontendDir, "playwright/data");

const BASE_URL = "http://localhost:3000";

const headlessBundlePath = resolve(here, "../dist/headless.js");

// Source the permissions from the same manifest the real plugin ships with, so
// the CI sandbox never drifts from what users actually grant.
const manifestPath = resolve(here, "../public/manifest.json");
const PERMISSIONS: string[] = (JSON.parse(readFileSync(manifestPath, "utf-8")) as { permissions: string[] })
    .permissions;

// Ids of the mocked full-feature file fixture (`ci/fixtures/get-file.json`),
// kept in sync with the frontend e2e fixtures.
const MOCK_TEAM_ID = "c7ce0794-0992-8105-8004-38e630f7920a";
const MOCK_FILE_ID = "c7ce0794-0992-8105-8004-38f280443849";
const MOCK_PAGE_ID = "66697432-c33d-8055-8006-2c62cc084cad";

// Workspace-load RPCs mirrored from the frontend e2e harness. Maps RPC glob ->
// fixture file relative to frontend/playwright/data.
const MOCK_RPCS: Record<string, string> = {
    "get-profile": "logged-in-user/get-profile-logged-in.json",
    "get-teams": "get-teams.json",
    "get-team?id=*": "workspace/get-team-default.json",
    "get-team-members?team-id=*": "logged-in-user/get-team-members-your-penpot.json",
    "get-team-users?file-id=*": "logged-in-user/get-team-users-single-user.json",
    "get-project?id=*": "workspace/get-project-default.json",
    "get-comment-threads?file-id=*": "workspace/get-comment-threads-empty.json",
    "get-profiles-for-file-comments?file-id=*": "workspace/get-profile-for-file-comments.json",
    "get-file-object-thumbnails?file-id=*": "workspace/get-file-object-thumbnails-blank.json",
    "get-font-variants?team-id=*": "workspace/get-font-variants-empty.json",
    "get-file-fragment?file-id=*": "workspace/get-file-fragment-blank.json",
    "get-file-libraries?file-id=*": "workspace/get-file-libraries-empty.json",
    "update-profile-props": "workspace/update-profile-empty.json",
};

// Persistence (`update-file`) response shape the frontend expects: it reads
// `revn`/`lagged` (persistence.cljs `update-file-revn`). `revn` is merged with
// `max`, so a low value is harmless.
const UPDATE_FILE_RESPONSE = JSON.stringify({ "~:revn": 1, "~:lagged": [] });

interface ReportedResult {
    identifier: string;
    name: string;
    passed: boolean;
    durationMs: number;
    error?: string;
    transcript?: string[];
}

async function waitForServer(url: string, timeoutMs = 30000): Promise<void> {
    const start = Date.now();
    for (;;) {
        try {
            const res = await fetch(url);
            if (res.ok || res.status === 404) return; // static server is up
        } catch {
            /* not up yet */
        }
        if (Date.now() - start > timeoutMs) {
            throw new Error(`Timed out waiting for server at ${url}`);
        }
        await new Promise((r) => setTimeout(r, 250));
    }
}

function startE2eServer(): ChildProcess {
    // Reuse the frontend e2e static server: it serves frontend/resources/public
    // on port 3000, which is also the host the app opens its notifications
    // WebSocket against — so the WS mock below matches without extra config.
    return spawn("node", ["scripts/e2e-server.js"], {
        cwd: frontendDir,
        stdio: "inherit",
    });
}

// Install the frontend e2e WebSocket mock so the workspace's notifications
// socket can be "opened" without a backend.
async function installWebSocketMock(page: Page): Promise<Set<string>> {
    const created = new Set<string>();
    await page.exposeFunction("onMockWebSocketConstructor", (url: string) => {
        created.add(url);
    });
    await page.addInitScript({
        path: resolve(frontendDir, "playwright/scripts/MockWebSocket.js"),
    });
    return created;
}

async function openNotificationsWebSocket(page: Page, created: Set<string>): Promise<void> {
    const start = Date.now();
    let wsUrl: string | undefined;
    while (!wsUrl) {
        wsUrl = [...created].find((u) => u.includes("ws/notifications"));
        if (wsUrl) break;
        if (Date.now() - start > 30000) {
            throw new Error("Timed out waiting for notifications WebSocket");
        }
        await new Promise((r) => setTimeout(r, 50));
    }
    await page.evaluate((url) => {
        (WebSocket as unknown as { getByURL: (u: string) => { mockOpen: () => void } | undefined })
            .getByURL(url)
            ?.mockOpen();
    }, wsUrl);
}

async function setupMockedRoutes(page: Page): Promise<void> {
    // Config flags: deterministic empty flags.
    await page.route("**/js/config.js*", (route) =>
        route.fulfill({
            status: 200,
            contentType: "application/javascript",
            body: 'var penpotFlags = "";\n',
        })
    );

    // Workspace-load RPCs from the frontend e2e fixtures.
    for (const [rpc, fixture] of Object.entries(MOCK_RPCS)) {
        await page.route(`**/api/main/methods/${rpc}`, (route) =>
            route.fulfill({
                status: 200,
                contentType: "application/transit+json",
                path: resolve(e2eDataDir, fixture),
            })
        );
    }

    // get-file: the custom full-feature fixture (enables plugins/runtime,
    // design-tokens/v1, variants/v1, ...). Without these features active the
    // plugin runtime never initialises.
    await page.route(/\/api\/main\/methods\/get-file\?/, (route) =>
        route.fulfill({
            status: 200,
            contentType: "application/transit+json",
            path: resolve(here, "fixtures/get-file.json"),
        })
    );

    // Blanket no-op persistence: the suite mutates the in-memory store
    // optimistically, so a 200 `update-file` mock is enough.
    await page.route(/\/api\/main\/methods\/update-file\b/, (route) =>
        route.fulfill({
            status: 200,
            contentType: "application/transit+json",
            body: UPDATE_FILE_RESPONSE,
        })
    );
}

function fileUrl(): string {
    return `${BASE_URL}/#/workspace?team-id=${MOCK_TEAM_ID}&file-id=${MOCK_FILE_ID}&page-id=${MOCK_PAGE_ID}`;
}

function printReport(results: ReportedResult[]) {
    // Each result is already printed live as it streams in; here we only recap
    // the failures (with their transcripts) so they're easy to find at the
    // bottom of a long run.
    const failures = results.filter((r) => !r.passed);
    if (failures.length > 0) {
        console.log("\nFailures:");
        for (const r of failures) {
            console.log(`  ✗ ${r.identifier} — ${r.name} (${r.durationMs}ms)`);
            if (r.error) console.log(`      ${r.error}`);
            for (const step of r.transcript ?? []) {
                console.log(`        · ${step}`);
            }
        }
    }
}

async function main() {
    const bundle = readFileSync(headlessBundlePath, "utf-8");

    const server = startE2eServer();
    await waitForServer(BASE_URL);

    const browser = await chromium.launch();
    const context = await browser.newContext();
    const page = await context.newPage();

    const wsCreated = await installWebSocketMock(page);
    await setupMockedRoutes(page);

    // The bundle runs inside an SES Compartment (its own `globalThis`), so a
    // page `addInitScript` global can't reach it. Prepend the filter straight
    // into the evaluated code.
    const filter = process.env["TEST_FILTER"];
    const injectedCode = filter
        ? `globalThis.__COMPOSABLE_SUITE_FILTER__ = ${JSON.stringify(filter)};\n${bundle}`
        : bundle;

    const results: ReportedResult[] = [];
    let fatal: string | null = null;

    console.log("\nRunning tests:");
    const done = new Promise<void>((resolvePromise) => {
        page.on("console", (msg) => {
            const text = msg.text();
            if (text.startsWith("__TEST_RESULT__ ")) {
                const result: ReportedResult = JSON.parse(text.slice("__TEST_RESULT__ ".length));
                results.push(result);
                const icon = result.passed ? "✓" : "✗";
                console.log(`  ${icon} ${result.identifier} — ${result.name} (${result.durationMs}ms)`);
                if (!result.passed && result.error) {
                    console.log(`      ${result.error}`);
                }
            } else if (text.startsWith("__TEST_DONE__ ")) {
                resolvePromise();
            } else if (text.startsWith("__TEST_FATAL__ ")) {
                fatal = (JSON.parse(text.slice("__TEST_FATAL__ ".length)) as { message: string }).message;
                resolvePromise();
            }
        });
    });

    await page.goto(fileUrl());
    await openNotificationsWebSocket(page, wsCreated);

    await page.waitForSelector('[data-testid="viewport"]');
    // The plugin runtime initialises asynchronously after the file's features
    // are active; wait for the loader to be exposed before injecting.
    await page.waitForFunction(
        () => typeof (globalThis as unknown as { ɵloadPlugin?: unknown }).ɵloadPlugin === "function",
        { timeout: 30000 }
    );

    await page.evaluate(
        ({ code, permissions }) => {
            (globalThis as unknown as { ɵloadPlugin: (m: unknown) => void }).ɵloadPlugin({
                pluginId: "00000000-0000-0000-0000-000000000000",
                name: "Composable Test Suite (CI)",
                code,
                icon: "",
                description: "",
                permissions,
            });
        },
        { code: injectedCode, permissions: PERMISSIONS }
    );

    const timeoutMs = Number(process.env["CI_TIMEOUT_MS"] ?? 600000);
    await Promise.race([
        done,
        new Promise<void>((_, reject) =>
            setTimeout(() => reject(new Error("Timed out waiting for test results")), timeoutMs)
        ),
    ]);

    await browser.close();
    server.kill();

    printReport(results);

    if (fatal) {
        console.error(`\nFatal error while running tests: ${fatal}`);
        process.exit(1);
    }

    const failed = results.filter((r) => !r.passed).length;
    const passed = results.filter((r) => r.passed).length;
    console.log(`\n${passed} passed, ${failed} failed.`);
    process.exit(failed > 0 ? 1 : 0);
}

main().catch((err: unknown) => {
    console.error(err);
    process.exit(1);
});
