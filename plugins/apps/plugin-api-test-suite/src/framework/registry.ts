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

function registerTest(name: string, fn: TestFn, mockedSkip: boolean): void {
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
  registry.push({ id, name, group, fn, mockedSkip });
}

/**
 * Registers a test. Called at module load time from the auto-discovered
 * `tests/*.test.ts` files. Ids are derived from the name and de-duplicated so
 * the UI and runner can address each test unambiguously.
 *
 * `test.skipIfMocked(name, fn)` registers a single test that is excluded when
 * running against a mocked backend (see {@link TestCase.mockedSkip}).
 */
export const test: {
  (name: string, fn: TestFn): void;
  skipIfMocked(name: string, fn: TestFn): void;
} = Object.assign(
  (name: string, fn: TestFn): void =>
    registerTest(name, fn, skipMockedDepth > 0),
  {
    skipIfMocked(name: string, fn: TestFn): void {
      registerTest(name, fn, true);
    },
  },
);

export function getTests(): TestCase[] {
  return registry.slice();
}

export function getTestMetas(): TestMeta[] {
  return registry.map(({ id, name, group }) => ({ id, name, group }));
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
