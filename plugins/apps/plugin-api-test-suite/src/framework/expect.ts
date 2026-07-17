/**
 * Minimal, dependency-free jest-like assertion library. It must not rely on any
 * Node/browser globals beyond the basics because it runs inside the SES plugin
 * sandbox. Every failed matcher throws an {@link AssertionError}; the runner
 * turns that into a red test with the message attached.
 *
 * Two properties matter for coverage correctness:
 * - Failure messages are built lazily (only when an assertion fails), so passing
 *   assertions never touch the value's members.
 * - `stringify` never enumerates non-plain objects (e.g. the recording proxies
 *   used for API coverage). Otherwise `JSON.stringify` would walk every property
 *   of a shape and inflate coverage with members the test never used.
 */

export class AssertionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AssertionError';
  }
}

function isPlainObject(value: object): boolean {
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function stringify(value: unknown): string {
  if (typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'bigint') return `${value}n`;
  if (typeof value === 'function')
    return `[Function ${value.name || 'anonymous'}]`;
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  // Only enumerate plain objects/arrays. Host/proxy objects (e.g. Penpot shape
  // proxies) are rendered opaquely so stringifying never reads their members.
  if (
    typeof value === 'object' &&
    !Array.isArray(value) &&
    !isPlainObject(value)
  ) {
    return '[object]';
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (
    typeof a !== 'object' ||
    typeof b !== 'object' ||
    a === null ||
    b === null
  ) {
    return false;
  }
  if (Array.isArray(a) !== Array.isArray(b)) return false;

  const aKeys = Object.keys(a as Record<string, unknown>);
  const bKeys = Object.keys(b as Record<string, unknown>);
  if (aKeys.length !== bKeys.length) return false;

  return aKeys.every(
    (key) =>
      Object.prototype.hasOwnProperty.call(b, key) &&
      deepEqual(
        (a as Record<string, unknown>)[key],
        (b as Record<string, unknown>)[key],
      ),
  );
}

export interface Matchers {
  toBe(expected: unknown): void;
  toEqual(expected: unknown): void;
  toBeTruthy(): void;
  toBeFalsy(): void;
  toBeNull(): void;
  toBeUndefined(): void;
  toBeDefined(): void;
  toContain(item: unknown): void;
  toHaveLength(length: number): void;
  toBeGreaterThan(n: number): void;
  toBeLessThan(n: number): void;
  toBeCloseTo(n: number, numDigits?: number): void;
  toThrow(expected?: string | RegExp): void;
}

export interface Expectation extends Matchers {
  not: Matchers;
}

type Message = () => string;

function errorMessage(thrown: unknown): string {
  return thrown instanceof Error ? thrown.message : String(thrown);
}

function messageMatches(message: string, expected?: string | RegExp): boolean {
  if (typeof expected === 'string') return message.includes(expected);
  if (expected instanceof RegExp) return expected.test(message);
  return true;
}

function makeMatchers(actual: unknown, negate: boolean): Matchers {
  // Message factories are only invoked on failure, so passing assertions never
  // stringify `actual` (which would enumerate proxies and skew coverage).
  const check = (pass: boolean, message: Message, negatedMessage: Message) => {
    if (negate ? pass : !pass) {
      throw new AssertionError((negate ? negatedMessage : message)());
    }
  };

  return {
    toBe(expected) {
      check(
        Object.is(actual, expected),
        () => `Expected ${stringify(actual)} to be ${stringify(expected)}`,
        () => `Expected ${stringify(actual)} not to be ${stringify(expected)}`,
      );
    },
    toEqual(expected) {
      check(
        deepEqual(actual, expected),
        () => `Expected ${stringify(actual)} to equal ${stringify(expected)}`,
        () =>
          `Expected ${stringify(actual)} not to equal ${stringify(expected)}`,
      );
    },
    toBeTruthy() {
      check(
        !!actual,
        () => `Expected ${stringify(actual)} to be truthy`,
        () => `Expected ${stringify(actual)} not to be truthy`,
      );
    },
    toBeFalsy() {
      check(
        !actual,
        () => `Expected ${stringify(actual)} to be falsy`,
        () => `Expected ${stringify(actual)} not to be falsy`,
      );
    },
    toBeNull() {
      check(
        actual === null,
        () => `Expected ${stringify(actual)} to be null`,
        () => `Expected ${stringify(actual)} not to be null`,
      );
    },
    toBeUndefined() {
      check(
        actual === undefined,
        () => `Expected ${stringify(actual)} to be undefined`,
        () => `Expected ${stringify(actual)} not to be undefined`,
      );
    },
    toBeDefined() {
      check(
        actual !== undefined,
        () => 'Expected value to be defined',
        () => 'Expected value not to be defined',
      );
    },
    toContain(item) {
      const pass =
        (typeof actual === 'string' && actual.includes(String(item))) ||
        (Array.isArray(actual) && actual.includes(item));
      check(
        pass,
        () => `Expected ${stringify(actual)} to contain ${stringify(item)}`,
        () => `Expected ${stringify(actual)} not to contain ${stringify(item)}`,
      );
    },
    toHaveLength(length) {
      const actualLength = (actual as { length?: number })?.length;
      check(
        actualLength === length,
        () =>
          `Expected ${stringify(actual)} to have length ${length} but got ${actualLength}`,
        () => `Expected ${stringify(actual)} not to have length ${length}`,
      );
    },
    toBeGreaterThan(n) {
      check(
        typeof actual === 'number' && actual > n,
        () => `Expected ${stringify(actual)} to be greater than ${n}`,
        () => `Expected ${stringify(actual)} not to be greater than ${n}`,
      );
    },
    toBeLessThan(n) {
      check(
        typeof actual === 'number' && actual < n,
        () => `Expected ${stringify(actual)} to be less than ${n}`,
        () => `Expected ${stringify(actual)} not to be less than ${n}`,
      );
    },
    toBeCloseTo(n, numDigits = 2) {
      const pass =
        typeof actual === 'number' &&
        Math.abs(actual - n) < Math.pow(10, -numDigits) / 2;
      check(
        pass,
        () =>
          `Expected ${stringify(actual)} to be close to ${n} (${numDigits} digits)`,
        () =>
          `Expected ${stringify(actual)} not to be close to ${n} (${numDigits} digits)`,
      );
    },
    toThrow(expected) {
      if (typeof actual !== 'function') {
        throw new AssertionError(
          `Expected a function to call but got ${stringify(actual)}`,
        );
      }
      let thrown: unknown;
      let didThrow = false;
      try {
        (actual as () => unknown)();
      } catch (err) {
        didThrow = true;
        thrown = err;
      }
      if (!didThrow) {
        check(
          false,
          () => 'Expected function to throw',
          () => 'Expected function not to throw',
        );
        return;
      }
      const message = errorMessage(thrown);
      const matches = messageMatches(message, expected);
      check(
        matches,
        () =>
          `Expected function to throw matching ${stringify(expected)} but threw ${stringify(message)}`,
        () => `Expected function not to throw matching ${stringify(expected)}`,
      );
    },
  };
}

export function expect(actual: unknown): Expectation {
  const matchers = makeMatchers(actual, false) as Expectation;
  matchers.not = makeMatchers(actual, true);
  return matchers;
}

/**
 * Async counterpart to {@link Matchers.toThrow}: awaits a promise (or a 0-arg
 * thunk returning one) and asserts that it REJECTS. `toThrow` can't cover this
 * because it calls its argument synchronously, but a large share of edge cases
 * are async (uploads, exports, version/comment/library ops).
 *
 * A thunk that throws synchronously also counts as a rejection, so callers can
 * pass `() => ctx.penpot.someAsyncCall(badArgs)` regardless of whether the
 * failure surfaces before or after the first await. The optional `expected`
 * matches the error message exactly like `toThrow` (string includes / RegExp).
 */
export async function expectReject(
  actual: Promise<unknown> | (() => Promise<unknown> | unknown),
  expected?: string | RegExp,
): Promise<void> {
  let thrown: unknown;
  let didReject = false;
  try {
    await (typeof actual === 'function' ? actual() : actual);
  } catch (err) {
    didReject = true;
    thrown = err;
  }
  if (!didReject) {
    throw new AssertionError('Expected promise to reject but it resolved');
  }
  const message = errorMessage(thrown);
  if (!messageMatches(message, expected)) {
    throw new AssertionError(
      `Expected promise to reject matching ${stringify(expected)} but rejected with ${stringify(message)}`,
    );
  }
}
