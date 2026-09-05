import { describe, it, expect, beforeAll } from 'vitest';
import 'ses';
import { ses } from './ses.js';

// Production-order contrast evidence for #11001 (see also #8636).
//
// The committed regression test in `load-plugin-context.spec.ts` bootstraps
// full SES (`repairIntrinsics` + `hardenIntrinsics`) before asserting. That
// hides the initialization-ordering hazard this file proves: in production,
// `plugins-runtime/src/index.ts` runs `repairIntrinsics` ONLY at module load,
// while `hardenIntrinsics` runs later inside `createSandbox`. The original
// `loadPlugin` called `ses.harden(context)` in between those two steps.
// Per #8636, hardening first freezes `Function.prototype` with plain data
// properties, so the later `enablePropertyOverrides` taming silently skips
// them — and any later `fn.toString = ...` assignment (e.g. the minified
// `Mm at libs.js` wrapper touched during page navigation in #11001) throws
// `TypeError: Cannot assign to read only property 'toString'`.
//
// This file therefore bootstraps `repairIntrinsics` ONLY (no
// `hardenIntrinsics`), mirroring the production window in which the original
// `loadPlugin` hardened the host context. It is kept in a separate spec file
// so the full bootstrap in `load-plugin-context.spec.ts` cannot mask the
// ordering effect (vitest isolates spec files in separate workers).
//
// NOTE: `./ses.js` is intentionally NOT mocked here.

describe('host context hardening order (contrast evidence for #11001)', () => {
  beforeAll(() => {
    // Production module-load step only: repairs intrinsics WITHOUT
    // installing override taming, exactly like `index.ts` at import time.
    // `ses.hardenIntrinsics()` must NOT run here — that is the later
    // `createSandbox` step.
    (
      globalThis as unknown as { repairIntrinsics(opts: object): void }
    ).repairIntrinsics({
      evalTaming: 'unsafeEval',
      stackFiltering: 'verbose',
      errorTaming: 'unsafe',
      consoleTaming: 'unsafe',
      errorTrapping: 'none',
      unhandledRejectionTrapping: 'none',
    });
  });

  it('hardening the host context before hardenIntrinsics freezes shared Function.prototype', () => {
    // Host-shaped context graph: an `addListener`-style closure producing
    // wrapped callbacks (cf. `events.cljs` safe-callback/debounce wrappers),
    // all sharing the ordinary `Function.prototype`.
    function addListener(type: string, callback: (v: unknown) => unknown) {
      const safeCallback = function safeCallback(value: unknown) {
        return callback(value);
      };
      const debounced = function debounced(this: unknown) {
        return safeCallback.apply(this, arguments as unknown as [unknown]);
      };
      return { type, safeCallback, debounced };
    }
    const hostListener = function hostListener() {
      return 'host-value';
    };
    const hostContext = { addListener, hostListener };

    // What the ORIGINAL loadPlugin did in the production window.
    ses.harden(hostContext);

    // Host-owned functions are frozen through the context graph.
    expect(Object.isFrozen(hostListener)).toBe(true);

    // The shared Function.prototype is frozen with plain (non-tamed) data
    // properties — override taming can no longer install its accessors.
    expect(Object.isFrozen(Function.prototype)).toBe(true);
    const desc = Object.getOwnPropertyDescriptor(
      Function.prototype,
      'toString',
    )!;
    expect(desc.writable).toBe(false);
    expect(desc.configurable).toBe(false);
    expect(desc.get).toBeUndefined();

    // Any function created afterwards — e.g. wrappers built during page
    // navigation — inherits the frozen prototype, so a later `toString`
    // augmentation throws the exact #11001 crash signature.
    const freshWrapper = function freshWrapper() {
      return 'navigation-wrapper';
    };
    expect(() => {
      freshWrapper.toString = () => 'patched-by-runtime';
    }).toThrow(TypeError);
  });
});
