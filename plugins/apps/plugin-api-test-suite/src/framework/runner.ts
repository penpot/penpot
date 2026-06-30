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
}

export interface RunOptions {
  /**
   * When true, tests tagged {@link TestCase.mockedSkip} are excluded from the
   * run (used by the mocked-backend CI mode). Their names are returned in
   * {@link RunOutput.skipped}.
   */
  skipMocked?: boolean;
}

export type ResultReporter = (result: TestResult) => void;

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
  const skipped = options?.skipMocked
    ? requested.filter((t) => t.mockedSkip).map((t) => t.name)
    : [];
  const selected = options?.skipMocked
    ? requested.filter((t) => !t.mockedSkip)
    : requested;

  const recorder = createRecorder(penpot, apiSurface as ApiSurface);

  // Run every test with strict, deterministic API behavior. Set through the
  // recording proxy so the flags also count towards coverage:
  // - throwValidationErrors: invalid API usage throws instead of only logging,
  //   so it surfaces as a red test rather than passing silently.
  // - naturalChildOrdering: `children` is always in z-index order and
  //   appendChild/insertChild respect it, making ordering assertions stable.
  recorder.proxy.flags.throwValidationErrors = true;
  recorder.proxy.flags.naturalChildOrdering = true;

  // Remember the page that was active when the run started. Tests share global
  // state (selection, the active page) with no per-test reset, so a test that
  // changes the active page — or fails before restoring it — would silently make
  // every later test run on the wrong page. After each test we clear the
  // selection and restore this page, all through the *raw* penpot so the cleanup
  // isn't credited toward coverage.
  const homePage = penpot.currentPage;

  const results: TestResult[] = [];

  for (const testCase of selected) {
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
    let rawBoard: ReturnType<typeof penpot.createBoard> | undefined;
    let result: TestResult;

    try {
      rawBoard = penpot.createBoard();
      rawBoard.name = SCRATCH_NAME;
      const board = recorder.wrap(rawBoard, 'Board');
      await withTimeout(
        testCase.fn({ penpot: recorder.proxy, board }),
        TEST_TIMEOUT_MS,
      );
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
      try {
        rawBoard?.remove();
      } catch {
        // best-effort cleanup; never fail a test because teardown failed
      }
      // Reset shared state so the next test starts clean. All best-effort: a
      // teardown failure must never turn into a test failure.
      try {
        penpot.selection = [];
      } catch {
        /* ignore */
      }
      try {
        const active = penpot.currentPage;
        if (homePage && active && active.id !== homePage.id) {
          await penpot.openPage(homePage);
        }
      } catch {
        /* ignore */
      }
    }

    results.push(result);
    onResult?.(result);
  }

  const summary: RunSummary = {
    total: results.length,
    passed: results.filter((r) => r.status === 'pass').length,
    failed: results.filter((r) => r.status === 'fail').length,
  };

  const coverage = computeCoverage(recorder.accessed, apiSurface as ApiSurface);

  return { results, summary, coverage, skipped };
}
