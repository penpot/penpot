import { afterEach, describe, expect, it, vi } from 'vitest';
import { bootstrapSES } from './bootstrap.js';

// Intentional snapshot of the production options in bootstrap.ts: if they
// change, this test must fail. Do not replace this literal with an import.
const REPAIR_OPTIONS = {
  evalTaming: 'unsafeEval',
  stackFiltering: 'verbose',
  errorTaming: 'unsafe',
  consoleTaming: 'unsafe',
  errorTrapping: 'none',
  unhandledRejectionTrapping: 'none',
};

const temporalError = () =>
  new TypeError('unexpected Temporal __proto__: [object Object]');

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.doUnmock('ses');
  vi.doUnmock('./modal/plugin-modal');
  vi.resetModules();
});

describe('bootstrapSES', () => {
  it('repairs intrinsics with the production options and reports success', () => {
    const repair = vi.fn();
    vi.stubGlobal('repairIntrinsics', repair);

    expect(bootstrapSES()).toBeNull();
    expect(repair).toHaveBeenCalledWith(REPAIR_OPTIONS);
  });

  it('returns the SES error instead of throwing it', () => {
    const error = temporalError();
    vi.stubGlobal('repairIntrinsics', () => {
      throw error;
    });

    expect(bootstrapSES()).toBe(error);
  });
});

describe('plugins runtime entry point', () => {
  it('installs the runtime globals when SES bootstrap succeeds', async () => {
    const globalAny = globalThis as unknown as Record<string, unknown>;
    const context = { theme: 'dark' };
    const runtimeKeys = [
      'ɵcontext',
      'ɵloadPlugin',
      'ɵloadPluginByUrl',
      'ɵunloadPlugin',
    ];
    const previous = runtimeKeys.map((key) => globalAny[key]);

    vi.stubGlobal('repairIntrinsics', vi.fn());
    vi.doMock('ses', () => ({}));
    // The custom element is registered at module load; mock it so repeated
    // entry-point imports do not redefine it in the same jsdom registry.
    vi.doMock('./modal/plugin-modal', () => ({}));
    vi.resetModules();

    try {
      const runtime = await import('../index.js');
      runtime.initPluginsRuntime(() => context as never);

      expect(runtime.getPluginsRuntimeError()).toBeNull();
      expect(globalAny['ɵcontext']).toBe(context);
      expect(typeof globalAny['ɵloadPlugin']).toBe('function');
      expect(typeof globalAny['ɵloadPluginByUrl']).toBe('function');
      expect(typeof globalAny['ɵunloadPlugin']).toBe('function');
    } finally {
      for (const [index, key] of runtimeKeys.entries()) {
        if (previous[index] === undefined) {
          delete globalAny[key];
        } else {
          globalAny[key] = previous[index];
        }
      }
    }
  });

  it('loads with a broken SES bootstrap without installing runtime globals', async () => {
    const error = temporalError();
    const globalAny = globalThis as unknown as Record<string, unknown>;
    const runtimeKeys = [
      'ɵcontext',
      'ɵloadPlugin',
      'ɵloadPluginByUrl',
      'ɵunloadPlugin',
    ];
    const previous = runtimeKeys.map((key) => globalAny[key]);

    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.stubGlobal('repairIntrinsics', () => {
      throw error;
    });
    // Keep the stub in place: `import 'ses'` in index.ts would otherwise
    // install a new repairIntrinsics global before bootstrap runs.
    vi.doMock('ses', () => ({}));
    // The custom element is registered at module load; mock it so repeated
    // entry-point imports do not redefine it in the same jsdom registry.
    vi.doMock('./modal/plugin-modal', () => ({}));
    vi.resetModules();

    try {
      for (const key of runtimeKeys) {
        delete globalAny[key];
      }

      const runtime = await import('../index.js');

      expect(runtime.getPluginsRuntimeError()).toBe(error);
      expect(() =>
        runtime.initPluginsRuntime(() => ({}) as never),
      ).not.toThrow();
      for (const key of runtimeKeys) {
        expect(globalAny[key]).toBeUndefined();
      }
    } finally {
      for (const [index, key] of runtimeKeys.entries()) {
        if (previous[index] === undefined) {
          delete globalAny[key];
        } else {
          globalAny[key] = previous[index];
        }
      }
    }
  });
});
