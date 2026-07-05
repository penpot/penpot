import type { Board, Penpot } from '@penpot/plugin-types';

export type TestStatus = 'pending' | 'running' | 'pass' | 'fail';

/**
 * The context handed to every test. Tests MUST use `ctx.penpot` (the recording
 * proxy) rather than the global `penpot` so their API usage is counted towards
 * coverage. A fresh scratch `board` is provided per test and removed afterwards.
 */
export interface TestContext {
  penpot: Penpot;
  board: Board;
}

export type TestFn = (ctx: TestContext) => void | Promise<void>;

export interface TestCase {
  id: string;
  name: string;
  /** Group the test belongs to (set via `describe`, defaults to "General"). */
  group: string;
  fn: TestFn;
  /**
   * When true, the test is excluded from runs against a mocked backend
   * (`MOCK_BACKEND=1`): it depends on real backend results/validation that a
   * `page.route` mock cannot faithfully reproduce. Set via `test.skipIfMocked`
   * or `describe.skipIfMocked`.
   */
  mockedSkip?: boolean;
}

/** Lightweight test description sent to the UI (no function). */
export interface TestMeta {
  id: string;
  name: string;
  group: string;
}

export interface TestResult {
  id: string;
  name: string;
  status: TestStatus;
  error?: string;
  durationMs: number;
}

export interface RunSummary {
  total: number;
  passed: number;
  failed: number;
}

/** Per-interface coverage detail derived from the public Plugin API types. */
export interface InterfaceCoverage {
  members: string[];
  covered: string[];
  /**
   * Targets exercised behaviourally by the tests but not creditable through the
   * recording proxy (see {@link ../framework/static-coverage}). Listed
   * separately from `covered` and `uncovered`.
   */
  staticallyCovered: string[];
  uncovered: string[];
}

export interface CoverageReport {
  total: number;
  /** Targets credited by the recording proxy. */
  covered: number;
  /** Targets covered only via the static allowlist (not recorder-credited). */
  staticallyCovered: number;
  /** Recorder-credited coverage: `covered / total`. */
  percent: number;
  /** Effective coverage including static targets: `(covered + static) / total`. */
  effectivePercent: number;
  byInterface: Record<string, InterfaceCoverage>;
}

/**
 * How a member is exercised, which determines the coverage targets it has:
 * - `method`: callable -> a single `call` target.
 * - `get`: read-only property -> a single `get` target.
 * - `getset`: writable property -> separate `get` and `set` targets.
 */
export type MemberKind = 'method' | 'get' | 'getset';

/** A single member in the API type graph. */
export interface ApiMemberInfo {
  /** Interface that actually declares this member (may be a base interface). */
  decl: string;
  /** Whether the member is a method, a read-only, or a writable property. */
  kind: MemberKind;
  /**
   * The interface/union name the member yields (return type for methods,
   * property type otherwise), or `null` when it is a primitive/untracked type.
   */
  type: string | null;
  /** True when the member yields an array of `type`. */
  array: boolean;
}

/** A union alias (e.g. `Shape`) and how to resolve it at runtime. */
export interface UnionInfo {
  variants: string[];
  /** Discriminant used to pick the concrete variant from a runtime value. */
  discriminant: { field: string; map: Record<string, string> } | null;
}

/**
 * Shape of the generated `api-surface.json`. Coverage is type-aware: `interfaces`
 * is the denominator (own members per interface) and `graph`/`unions` let the
 * recorder attribute each access to the interface the value actually is.
 */
export interface ApiSurface {
  interfaces: Record<string, string[]>;
  graph: Record<string, Record<string, ApiMemberInfo>>;
  unions: Record<string, UnionInfo>;
}
