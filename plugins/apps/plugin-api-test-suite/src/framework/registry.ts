import type { TestCase, TestFn, TestMeta } from './types';

export const DEFAULT_GROUP = 'General';

let registry: TestCase[] = [];
let seenIds = new Set<string>();
const groupStack: string[] = [];
// >0 while inside a `describe.skipIfMocked` block; every test registered while
// it is positive is tagged `mockedSkip`.
let skipMockedDepth = 0;

/** Separator used to join nested `describe` names into a single group path. */
export const GROUP_SEPARATOR = ' / ';

function slugify(name: string): string {
  return name
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/**
 * Groups the tests registered inside `fn` under `name`. Groups are collapsible in
 * the UI and show their own pass/fail counts. Calls may be nested in a file; the
 * nested names are joined into a single hierarchical path (e.g. `Layout / Flex`)
 * so a group always reveals the file/area it belongs to. Tests registered outside
 * any `describe` fall into the {@link DEFAULT_GROUP}.
 */
function describeImpl(name: string, fn: () => void): void {
  groupStack.push(name);
  try {
    fn();
  } finally {
    groupStack.pop();
  }
}

/**
 * Groups the tests registered inside `fn` under `name`.
 *
 * `describe.skipIfMocked(name, fn)` additionally tags every test registered in
 * the block as {@link TestCase.mockedSkip} — use it for a whole group of
 * backend-dependent tests.
 */
export const describe: {
  (name: string, fn: () => void): void;
  skipIfMocked(name: string, fn: () => void): void;
} = Object.assign(describeImpl, {
  skipIfMocked(name: string, fn: () => void): void {
    skipMockedDepth++;
    try {
      describeImpl(name, fn);
    } finally {
      skipMockedDepth--;
    }
  },
});

/** Per-test flags applied through the `test` modifier getters. */
interface TestModifiers {
  only: boolean;
  noCleanup: boolean;
  mockedSkip: boolean;
}

function registerTest(
  name: string,
  fn: TestFn,
  mods: Partial<TestModifiers>,
): void {
  const base = slugify(name) || 'test';
  let id = base;
  let n = 2;
  while (seenIds.has(id)) {
    id = `${base}-${n++}`;
  }
  seenIds.add(id);
  const group = groupStack.length
    ? groupStack.join(GROUP_SEPARATOR)
    : DEFAULT_GROUP;
  registry.push({
    id,
    name,
    group,
    fn,
    mockedSkip: Boolean(mods.mockedSkip) || skipMockedDepth > 0,
    only: mods.only,
    noCleanup: mods.noCleanup,
  });
}

/**
 * Registrar for a single test. Callable as `test(name, fn)` and chainable
 * through modifier getters so flags compose, e.g. `test.only.nocleanup(name, fn)`.
 */
export interface TestRegistrar {
  (name: string, fn: TestFn): void;
  /**
   * Focuses the run on tests marked `.only`; every other test is skipped. A dev
   * aid for isolating a single case (see {@link TestCase.only}).
   */
  readonly only: TestRegistrar;
  /**
   * Leaves the scratch board and shared state in place after the test instead of
   * tearing them down, so the result can be inspected (see {@link TestCase.noCleanup}).
   */
  readonly nocleanup: TestRegistrar;
  /**
   * Registers a test that is excluded when running against a mocked backend
   * (see {@link TestCase.mockedSkip}).
   */
  skipIfMocked(name: string, fn: TestFn): void;
}

function makeRegistrar(mods: Partial<TestModifiers>): TestRegistrar {
  const register = ((name: string, fn: TestFn): void =>
    registerTest(name, fn, mods)) as TestRegistrar;
  // Define the modifier getters directly rather than via Object.assign: assign
  // would *read* each getter to copy its value, recursing without end.
  Object.defineProperties(register, {
    only: {
      get: () => makeRegistrar({ ...mods, only: true }),
      configurable: true,
    },
    nocleanup: {
      get: () => makeRegistrar({ ...mods, noCleanup: true }),
      configurable: true,
    },
    skipIfMocked: {
      value: (name: string, fn: TestFn): void =>
        registerTest(name, fn, { ...mods, mockedSkip: true }),
      configurable: true,
    },
  });
  return register;
}

/**
 * Registers a test. Called at module load time from the auto-discovered
 * `tests/*.test.ts` files. Ids are derived from the name and de-duplicated so
 * the UI and runner can address each test unambiguously.
 *
 * Modifiers compose through chainable getters:
 * - `test.only(name, fn)` focuses the run on this test.
 * - `test.nocleanup(name, fn)` keeps the scratch board after the test.
 * - `test.skipIfMocked(name, fn)` excludes it from a mocked-backend run.
 */
export const test: TestRegistrar = makeRegistrar({});

export function getTests(): TestCase[] {
  return registry.slice();
}

/**
 * Test descriptions for the UI. When any test is marked `.only`, only those are
 * returned so the UI shows the focused set exclusively (matching the run focus).
 */
export function getTestMetas(): TestMeta[] {
  const source = registry.some((t) => t.only)
    ? registry.filter((t) => t.only)
    : registry;
  return source.map(({ id, name, group }) => ({ id, name, group }));
}

/**
 * Replaces the whole registry. Used by the reload mechanism, which evaluates a
 * freshly built test bundle and hands back the discovered {@link TestCase}s.
 */
export function setTests(tests: TestCase[]): void {
  registry = tests.slice();
  seenIds = new Set(registry.map((t) => t.id));
}

export function clearTests(): void {
  registry = [];
  seenIds = new Set();
}
