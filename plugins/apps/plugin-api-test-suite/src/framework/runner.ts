import apiSurface from '../generated/api-surface.json';
import { computeCoverage, createRecorder } from './coverage';
import { getTests } from './registry';
import type {
  ApiSurface,
  CoverageReport,
  RunSummary,
  TestResult,
} from './types';

const SCRATCH_NAME = '__api_test_scratch__';

// Every test runs an extra postcondition: after it we assert it did not leave
// the file referentially inconsistent (a class of bug local property round-trips
// can't catch — see `File.validate`). Cheap enough (~1ms/call) to run on all
// tests, and widens the net to non-component corruption (e.g. layout).

/** Stable signature for a validation error so results can be diffed across runs. */
function errorSignature(e: {
  code: string;
  shapeId: string | null;
  pageId: string | null;
}): string {
  return `${e.code}:${e.shapeId ?? ''}:${e.pageId ?? ''}`;
}

// Runs `File.validate` on the current file, or returns null when the running
// frontend predates the method (keeps the suite working against older builds).
// Uses the *raw* penpot so it isn't credited toward coverage — a dedicated test
// in file.test.ts exercises `File.validate` for coverage.
function fileValidate(): FileValidationError[] | null {
  const file = penpot.currentFile as
    { validate?: () => FileValidationError[] } | null | undefined;
  if (!file || typeof file.validate !== 'function') return null;
  return file.validate();
}

interface FileValidationError {
  code: string;
  shapeId: string | null;
  pageId: string | null;
}

// Snapshot of the file's current referential-integrity errors, by signature.
// Null when validation is unavailable, which disables the postcondition.
function integritySignatures(): Set<string> | null {
  const errors = fileValidate();
  return errors ? new Set(errors.map(errorSignature)) : null;
}

// Fails the test if it introduced referential-integrity errors not present in
// `before`. Diffing against a baseline tolerates pre-existing corruption (e.g. a
// `.nocleanup` test that intentionally leaves a broken file behind) so one bad
// test doesn't cascade into every later variant/component test.
function assertNoNewIntegrityErrors(before: Set<string>): void {
  const errors = fileValidate();
  if (!errors) return;
  const introduced = errors.filter((e) => !before.has(errorSignature(e)));
  if (introduced.length > 0) {
    const summary = introduced
      .map((e) => (e.shapeId ? `${e.code} (${e.shapeId})` : e.code))
      .join(', ');
    throw new Error(
      `Test left the file referentially inconsistent: ${summary}`,
    );
  }
}

// A single test must never freeze the whole run. Some plugin API calls can hang
// indefinitely (e.g. an async op whose completion event never fires), so each
// test is raced against this timeout and turned into a failure if it exceeds it.
const TEST_TIMEOUT_MS = 15000;

export interface RunOutput {
  results: TestResult[];
  summary: RunSummary;
  coverage: CoverageReport;
  /** Names of tests excluded because of {@link RunOptions.skipMocked}. */
  skipped: string[];
  /** True when the run ended early because {@link RunOptions.shouldStop} asked it to. */
  stopped: boolean;
}

export interface RunOptions {
  /**
   * When true, tests tagged {@link TestCase.mockedSkip} are excluded from the
   * run (used by the mocked-backend CI mode). Their names are returned in
   * {@link RunOutput.skipped}.
   */
  skipMocked?: boolean;
  /**
   * Checked before each test; returning true stops the run after the current
   * test finishes. Lets the UI cancel a long run without leaving a half-created
   * scratch board behind.
   */
  shouldStop?: () => boolean;
}

export type ResultReporter = (result: TestResult) => void;

/** File and page that were active when the run started. */
interface Home {
  fileId: string | null;
  teamId: string | null;
  pageId: string | null;
}

// Reopens the home file when a test navigated to another one. Shape, page and
// file references taken before navigating are stale afterwards, so callers
// look objects up again by id. Uses the *raw* globals so it isn't credited
// toward coverage.
async function returnToHomeFile(home: Home): Promise<void> {
  if (!home.fileId) return;
  const workspace = penpotMgmt.workspace;
  if (workspace.status === 'ready' && workspace.fileId === home.fileId) return;
  await penpotMgmt.openFile(home.fileId, { teamId: home.teamId ?? undefined });
}

function homePage(home: Home) {
  return penpot.currentFile?.pages.find((page) => page.id === home.pageId);
}

function withTimeout(promise: void | Promise<void>, ms: number): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        reject(new Error(`Test timed out after ${ms}ms`));
      }
    }, ms);
    Promise.resolve(promise).then(
      () => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve();
        }
      },
      (err) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          reject(err);
        }
      },
    );
  });
}

/**
 * Runs the selected tests (or all of them) in the plugin sandbox. Each test gets
 * a fresh scratch board through the recording proxy and that board is removed
 * afterwards, so the user's file is left clean. API usage across the whole run is
 * accumulated and turned into a coverage report.
 */
export async function runTests(
  ids: string[] | 'all',
  onResult?: ResultReporter,
  options?: RunOptions,
): Promise<RunOutput> {
  const all = getTests();
  const requested = ids === 'all' ? all : all.filter((t) => ids.includes(t.id));
  // `.only` focuses the run: when any requested test is marked only, restrict the
  // run to those and drop the rest. A source-level dev aid for isolating a case.
  const focused = requested.some((t) => t.only)
    ? requested.filter((t) => t.only)
    : requested;
  const skipped = options?.skipMocked
    ? focused.filter((t) => t.mockedSkip).map((t) => t.name)
    : [];
  const selected = options?.skipMocked
    ? focused.filter((t) => !t.mockedSkip)
    : focused;

  const recorder = createRecorder(penpot, apiSurface as ApiSurface);
  const management = recorder.wrap(penpotMgmt, 'PenpotMgmt');

  // Fixtures shared by the tests of this run (see `TestContext.fixture`).
  const fixtures = new Map<string, Promise<unknown>>();
  const fixture = <T>(key: string, create: () => Promise<T>): Promise<T> => {
    if (!fixtures.has(key)) fixtures.set(key, create());
    return fixtures.get(key) as Promise<T>;
  };

  // Run every test with strict, deterministic API behavior. Set through the
  // recording proxy so the flags also count towards coverage:
  // - throwValidationErrors: invalid API usage throws instead of only logging,
  //   so it surfaces as a red test rather than passing silently.
  // - naturalChildOrdering: `children` is always in z-index order and
  //   appendChild/insertChild respect it, making ordering assertions stable.
  recorder.proxy.flags.throwValidationErrors = true;
  recorder.proxy.flags.naturalChildOrdering = true;

  // Remember the file and page that were active when the run started. Tests
  // share global state (selection, the active page, the open file) with no
  // per-test reset, so a test that changes them — or fails before restoring
  // them — would silently make every later test run in the wrong place. After
  // each test we reopen this file and page and clear the selection, all through
  // the *raw* globals so the cleanup isn't credited toward coverage.
  const home: Home = {
    fileId: penpotMgmt.workspace.fileId,
    teamId: penpotMgmt.workspace.teamId,
    pageId: penpot.currentPage?.id ?? null,
  };

  const results: TestResult[] = [];

  let stopped = false;
  for (const testCase of selected) {
    if (options?.shouldStop?.()) {
      stopped = true;
      break;
    }

    onResult?.({
      id: testCase.id,
      name: testCase.name,
      status: 'running',
      durationMs: 0,
    });

    const start = Date.now();
    // Create/name/remove the scratch board through the *raw* penpot so this
    // harness bookkeeping isn't credited toward coverage. The test still gets a
    // recording-wrapped board, so its own access to it is counted.
    let boardId: string | undefined;
    let result: TestResult;

    // Baseline the file's integrity errors before the test so we can attribute
    // only newly-introduced ones to it.
    const integrityBaseline = integritySignatures();

    try {
      const rawBoard = penpot.createBoard();
      rawBoard.name = SCRATCH_NAME;
      boardId = rawBoard.id;
      const board = recorder.wrap(rawBoard, 'Board');
      await withTimeout(
        testCase.fn({
          penpot: recorder.proxy,
          penpotMgmt: management,
          board,
          fixture,
        }),
        TEST_TIMEOUT_MS,
      );
      // The integrity baseline belongs to the home file, so check it there.
      await returnToHomeFile(home);
      // Postcondition: the test must not have broken referential integrity.
      if (integrityBaseline) assertNoNewIntegrityErrors(integrityBaseline);
      result = {
        id: testCase.id,
        name: testCase.name,
        status: 'pass',
        durationMs: Date.now() - start,
      };
    } catch (err) {
      result = {
        id: testCase.id,
        name: testCase.name,
        status: 'fail',
        error: err instanceof Error ? err.message : String(err),
        durationMs: Date.now() - start,
      };
    } finally {
      // `test.nocleanup` keeps the scratch board and shared state as the test
      // left them so the result can be inspected in the workspace.
      if (!testCase.noCleanup) {
        // Reset shared state so the next test starts clean. All best-effort: a
        // teardown failure must never turn into a test failure.
        try {
          await returnToHomeFile(home);
        } catch {
          /* ignore */
        }
        try {
          if (boardId) homePage(home)?.getShapeById(boardId)?.remove();
        } catch {
          /* ignore */
        }
        try {
          penpot.selection = [];
        } catch {
          /* ignore */
        }
        try {
          const page = homePage(home);
          if (page && penpot.currentPage?.id !== page.id) {
            await penpot.openPage(page);
          }
        } catch {
          /* ignore */
        }
      }
    }

    results.push(result);
    onResult?.(result);

    // Yield a macrotask between tests so the workspace can flush pending
    // renders. Sync tests only yield microtasks, and hundreds of back-to-back
    // mutation bursts starve React's commit cycle until it trips its
    // nested-update limit, surfacing "Maximum update depth exceeded" error
    // toasts in the host app (and, more rarely, wasm render errors).
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  const summary: RunSummary = {
    total: results.length,
    passed: results.filter((r) => r.status === 'pass').length,
    failed: results.filter((r) => r.status === 'fail').length,
  };

  const coverage = computeCoverage(recorder.accessed, apiSurface as ApiSurface);

  return { results, summary, coverage, skipped, stopped };
}
