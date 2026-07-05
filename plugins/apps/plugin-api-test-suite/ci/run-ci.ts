import { spawn, type ChildProcess } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, type Page } from 'playwright';
import type { CoverageReport, TestResult } from '../src/framework/types';

// Out-of-sandbox CI driver (Node + Playwright). Injects the prebuilt
// `headless.js` bundle (built from the in-sandbox entry `src/ci/headless.ts` —
// note: a different `ci/` directory) into the plugin sandbox via
// `globalThis.ɵloadPlugin` and captures results/coverage from the page console.
// Two modes:
//
// - LIVE (default): logs into a real Penpot instance (devenv), creates a scratch
//   file, and drives the real backend + frontend end-to-end.
//   Required env: E2E_LOGIN_EMAIL, E2E_LOGIN_PASSWORD.
//   Optional env: PENPOT_BASE_URL (default https://localhost:3449).
//
// - MOCKED (`MOCK_BACKEND=1`): serves the prebuilt frontend bundle via the e2e
//   static server and intercepts every backend RPC with Playwright `page.route`,
//   reusing the frontend e2e mock fixtures. No backend/login needed. Validates
//   the frontend Plugin API binding + in-memory store only; results that depend
//   on real backend behaviour are not faithfully reproduced, so those tests are
//   skipped via the `skipIfMocked` tag.

const here = dirname(fileURLToPath(import.meta.url));
// here = <root>/plugins/apps/plugin-api-test-suite/ci
const repoRoot = resolve(here, '../../../../');
const frontendDir = resolve(repoRoot, 'frontend');
const e2eDataDir = resolve(frontendDir, 'playwright/data');

const MOCKED = !!process.env['MOCK_BACKEND'];
const MOCK_BASE_URL = 'http://localhost:3000';
const apiUrl = MOCKED
  ? MOCK_BASE_URL
  : (process.env['PENPOT_BASE_URL'] ?? 'https://localhost:3449');

const headlessBundlePath = resolve(
  here,
  '../../../dist/apps/plugin-api-test-suite/headless.js',
);

// Source the permissions from the same manifest the real plugin ships with, so
// the CI sandbox never drifts from what users actually grant.
const manifestPath = resolve(here, '../public/manifest.json');
const PERMISSIONS: string[] = (
  JSON.parse(readFileSync(manifestPath, 'utf-8')) as { permissions: string[] }
).permissions;

function cleanId(id: string): string {
  return id.replace('~u', '');
}

interface FileRpc {
  '~:id': string;
  '~:project-id': string;
  '~:data': { '~:pages': string[] };
}

async function login() {
  const email = process.env['E2E_LOGIN_EMAIL'];
  const password = process.env['E2E_LOGIN_PASSWORD'];
  if (!email || !password) {
    throw new Error('E2E_LOGIN_EMAIL / E2E_LOGIN_PASSWORD must be set');
  }

  const response = await fetch(
    `${apiUrl}/api/main/methods/login-with-password`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    },
  );

  const loginData = await response.json();
  const authToken = response.headers
    .getSetCookie()
    .find((cookie) => cookie.startsWith('auth-token='))
    ?.split(';')[0];

  if (!authToken)
    throw new Error('Login failed: no auth-token cookie returned');

  return { authToken, defaultProjectId: loginData['~:default-project-id'] };
}

async function createFile(
  authToken: string,
  projectId: string,
): Promise<FileRpc> {
  const response = await fetch(`${apiUrl}/api/main/methods/create-file`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/transit+json',
      cookie: authToken,
    },
    body: JSON.stringify({
      '~:name': `api-test-suite ${new Date().toISOString()}`,
      '~:project-id': projectId,
      '~:features': {
        '~#set': [
          'fdata/objects-map',
          'fdata/pointer-map',
          'fdata/shape-data-type',
          'fdata/path-data',
          'design-tokens/v1',
          'variants/v1',
          'components/v2',
          'styles/v2',
          'layout/grid',
          'plugins/runtime',
        ],
      },
    }),
  });

  return (await response.json()) as FileRpc;
}

function getFileUrl(file: FileRpc): string {
  const projectId = cleanId(file['~:project-id']);
  const fileId = cleanId(file['~:id']);
  const pageId = cleanId(file['~:data']['~:pages'][0]);
  return `${apiUrl}/#/workspace/${projectId}/${fileId}?page-id=${pageId}`;
}

// --- Mocked mode setup -------------------------------------------------------

// Ids of the mocked full-feature file fixture (`ci/fixtures/get-file.json`),
// kept in sync with the frontend e2e fixtures.
const MOCK_TEAM_ID = 'c7ce0794-0992-8105-8004-38e630f7920a';
const MOCK_FILE_ID = 'c7ce0794-0992-8105-8004-38f280443849';
const MOCK_PAGE_ID = '66697432-c33d-8055-8006-2c62cc084cad';

// Workspace-load RPCs mirrored from the frontend e2e harness
// (WorkspacePage.init + setupEmptyFile). Maps RPC glob -> fixture file relative
// to frontend/playwright/data.
const MOCK_RPCS: Record<string, string> = {
  'get-profile': 'logged-in-user/get-profile-logged-in.json',
  'get-teams': 'get-teams.json',
  'get-team?id=*': 'workspace/get-team-default.json',
  'get-team-members?team-id=*':
    'logged-in-user/get-team-members-your-penpot.json',
  'get-team-users?file-id=*': 'logged-in-user/get-team-users-single-user.json',
  'get-project?id=*': 'workspace/get-project-default.json',
  'get-comment-threads?file-id=*': 'workspace/get-comment-threads-empty.json',
  'get-profiles-for-file-comments?file-id=*':
    'workspace/get-profile-for-file-comments.json',
  'get-file-object-thumbnails?file-id=*':
    'workspace/get-file-object-thumbnails-blank.json',
  'get-font-variants?team-id=*': 'workspace/get-font-variants-empty.json',
  'get-file-fragment?file-id=*': 'workspace/get-file-fragment-blank.json',
  'get-file-libraries?file-id=*': 'workspace/get-file-libraries-empty.json',
  'update-profile-props': 'workspace/update-profile-empty.json',
};

// Persistence (`update-file`) response shape the frontend expects: it reads
// `revn`/`lagged` (persistence.cljs `update-file-revn`). `revn` is merged with
// `max`, so a low value is harmless.
const UPDATE_FILE_RESPONSE = JSON.stringify({ '~:revn': 1, '~:lagged': [] });

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
  // WebSocket against (ws://localhost:3000/ws/notifications) — so the WS mock
  // below matches without extra config.
  const child = spawn('node', ['scripts/e2e-server.js'], {
    cwd: frontendDir,
    stdio: 'inherit',
  });
  return child;
}

// Install the frontend e2e WebSocket mock so the workspace's notifications
// socket can be "opened" without a backend.
async function installWebSocketMock(page: Page): Promise<void> {
  const created = new Set<string>();
  await page.exposeFunction('onMockWebSocketConstructor', (url: string) => {
    created.add(url);
  });
  await page.addInitScript({
    path: resolve(frontendDir, 'playwright/scripts/MockWebSocket.js'),
  });
  // Stash the helper on the page object for later use.
  (page as unknown as { __wsCreated: Set<string> }).__wsCreated = created;
}

async function openNotificationsWebSocket(page: Page): Promise<void> {
  const created = (page as unknown as { __wsCreated: Set<string> }).__wsCreated;
  const start = Date.now();
  let wsUrl: string | undefined;
  while (!wsUrl) {
    wsUrl = [...created].find((u) => u.includes('ws/notifications'));
    if (wsUrl) break;
    if (Date.now() - start > 30000) {
      throw new Error('Timed out waiting for notifications WebSocket');
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  await page.evaluate((url) => {
    (
      WebSocket as unknown as {
        getByURL: (u: string) => { mockOpen: () => void } | undefined;
      }
    )
      .getByURL(url)
      ?.mockOpen();
  }, wsUrl);
}

async function setupMockedRoutes(page: Page): Promise<void> {
  // Config flags: deterministic empty flags (mirror BasePage.mockConfigFlags).
  await page.route('**/js/config.js*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/javascript',
      body: 'var penpotFlags = "";\n',
    }),
  );

  // Workspace-load RPCs from fixtures.
  for (const [rpc, fixture] of Object.entries(MOCK_RPCS)) {
    await page.route(`**/api/main/methods/${rpc}`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/transit+json',
        path: resolve(e2eDataDir, fixture),
      }),
    );
  }

  // get-file: the custom full-feature fixture (enables plugins/runtime,
  // design-tokens/v1, variants/v1, ...). Without these features active the
  // plugin runtime never initialises.
  await page.route(/\/api\/main\/methods\/get-file\?/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/transit+json',
      path: resolve(here, 'fixtures/get-file.json'),
    }),
  );

  // Blanket no-op persistence: most of the Plugin API mutates the in-memory
  // store optimistically, so a 200 `update-file` mock is enough for the bulk of
  // the suite to run against in-memory state.
  await page.route(/\/api\/main\/methods\/update-file\b/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/transit+json',
      body: UPDATE_FILE_RESPONSE,
    }),
  );
}

function mockedFileUrl(): string {
  return `${MOCK_BASE_URL}/#/workspace?team-id=${MOCK_TEAM_ID}&file-id=${MOCK_FILE_ID}&page-id=${MOCK_PAGE_ID}`;
}

// --- Reporting ---------------------------------------------------------------

function printReport(
  results: TestResult[],
  coverage: CoverageReport | null,
  skipped: string[],
) {
  // Each result is already printed live as it streams in; here we only recap the
  // failures so they're easy to find at the bottom of a long run.
  const failures = results.filter((r) => r.status === 'fail');
  if (failures.length > 0) {
    console.log('\nFailures:');
    for (const r of failures) {
      console.log(`  ✗ ${r.name} (${r.durationMs}ms)`);
      if (r.error) {
        console.log(`      ${r.error}`);
      }
    }
  }

  if (skipped.length > 0) {
    console.log(`\nSkipped (mocked mode): ${skipped.length}`);
    for (const name of skipped) {
      console.log(`  - ${name}`);
    }
  }

  if (coverage) {
    console.log(
      `\nAPI coverage (report-only): ${coverage.percent}% recorded ` +
        `(${coverage.covered}/${coverage.total}), ` +
        `${coverage.effectivePercent}% effective ` +
        `(+${coverage.staticallyCovered} statically covered)`,
    );

    // Opt-in dump of the uncovered targets per interface, to drive test writing.
    if (process.env['PRINT_UNCOVERED']) {
      console.log('\nUncovered targets by interface:');
      for (const [iface, info] of Object.entries(coverage.byInterface)) {
        if (info.uncovered.length > 0) {
          console.log(`  ${iface}: ${info.uncovered.join(', ')}`);
        }
      }
    }

    // Opt-in dump of the statically-covered targets (exercised behaviourally but
    // not creditable through the recording proxy).
    if (process.env['PRINT_STATIC']) {
      console.log('\nStatically covered targets by interface:');
      for (const [iface, info] of Object.entries(coverage.byInterface)) {
        if (info.staticallyCovered.length > 0) {
          console.log(`  ${iface}: ${info.staticallyCovered.join(', ')}`);
        }
      }
    }
  }
}

async function main() {
  const bundle = readFileSync(headlessBundlePath, 'utf-8');

  let server: ChildProcess | undefined;
  let fileUrl: string;
  let authToken: string | undefined;

  if (MOCKED) {
    server = startE2eServer();
    await waitForServer(MOCK_BASE_URL);
    fileUrl = mockedFileUrl();
  } else {
    const session = await login();
    authToken = session.authToken;
    const file = await createFile(authToken, session.defaultProjectId);
    fileUrl = getFileUrl(file);
  }

  const browser = await chromium.launch({
    args: ['--ignore-certificate-errors'],
  });
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  if (authToken) {
    await context.addCookies([
      { name: 'auth-token', value: authToken.split('=')[1], url: apiUrl },
    ]);
  }

  const page = await context.newPage();

  if (MOCKED) {
    await installWebSocketMock(page);
    await setupMockedRoutes(page);
  }

  // The bundle runs inside an SES Compartment (its own `globalThis`), so a page
  // `addInitScript` global can't reach it. Prepend the mocked flag straight into
  // the evaluated code so the bundle's `runTests` excludes `skipIfMocked` tests.
  const injectedCode = MOCKED
    ? `globalThis.__PLUGIN_SUITE_MOCKED__ = true;\n${bundle}`
    : bundle;

  const results: TestResult[] = [];
  let coverage: CoverageReport | null = null;
  let skipped: string[] = [];
  let fatal: string | null = null;

  console.log('\nRunning tests:');
  const done = new Promise<void>((resolvePromise) => {
    page.on('console', (msg) => {
      const text = msg.text();
      if (text.startsWith('__TEST_RESULT__ ')) {
        const result: TestResult = JSON.parse(
          text.slice('__TEST_RESULT__ '.length),
        );
        results.push(result);
        // Print each result as it streams in so the run shows live progress
        // instead of staying silent until it finishes.
        const icon = result.status === 'pass' ? '✓' : '✗';
        console.log(`  ${icon} ${result.name} (${result.durationMs}ms)`);
        if (result.status === 'fail' && result.error) {
          console.log(`      ${result.error}`);
        }
      } else if (text.startsWith('__TEST_COVERAGE__ ')) {
        coverage = JSON.parse(text.slice('__TEST_COVERAGE__ '.length));
      } else if (text.startsWith('__TEST_SKIPPED__ ')) {
        skipped = JSON.parse(text.slice('__TEST_SKIPPED__ '.length));
      } else if (text.startsWith('__TEST_DONE__ ')) {
        resolvePromise();
      } else if (text.startsWith('__TEST_FATAL__ ')) {
        fatal = JSON.parse(text.slice('__TEST_FATAL__ '.length)).message;
        resolvePromise();
      }
    });
  });

  await page.goto(fileUrl);

  if (MOCKED) {
    await openNotificationsWebSocket(page);
  }

  await page.waitForSelector('[data-testid="viewport"]');
  // The plugin runtime initialises asynchronously after the file's features are
  // active; wait for the loader to be exposed before injecting the bundle.
  await page.waitForFunction(
    () =>
      typeof (globalThis as unknown as { ɵloadPlugin?: unknown })
        .ɵloadPlugin === 'function',
    { timeout: 30000 },
  );

  await page.evaluate(
    ({ code, permissions }) => {
      (
        globalThis as unknown as { ɵloadPlugin: (m: unknown) => void }
      ).ɵloadPlugin({
        pluginId: '00000000-0000-0000-0000-000000000000',
        name: 'Plugin API Test Suite (CI)',
        code,
        icon: '',
        description: '',
        permissions,
      });
    },
    { code: injectedCode, permissions: PERMISSIONS },
  );

  await Promise.race([
    done,
    new Promise<void>((_, reject) =>
      setTimeout(
        () => reject(new Error('Timed out waiting for test results')),
        120000,
      ),
    ),
  ]);

  await browser.close();
  server?.kill();

  printReport(results, coverage, skipped);

  if (fatal) {
    console.error(`\nFatal error while running tests: ${fatal}`);
    process.exit(1);
  }

  const failed = results.filter((r) => r.status === 'fail').length;
  const passed = results.filter((r) => r.status === 'pass').length;
  console.log(
    `\n${passed} passed, ${failed} failed${
      skipped.length ? `, ${skipped.length} skipped` : ''
    }.`,
  );
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
