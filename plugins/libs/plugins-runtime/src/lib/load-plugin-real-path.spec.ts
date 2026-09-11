import { describe, it, vi, expect, beforeAll } from 'vitest';
import 'ses';
import { loadPlugin, setContextBuilder, getPlugins } from './load-plugin';
import type { Context } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';

// Real initialization-path regression tests for #11001.
//
// NOTE: `./create-plugin`, `./plugin-manager` and
// `./create-sandbox` are intentionally NOT mocked here. This spec exercises
// the real `loadPlugin → createPlugin → createPluginManager → createSandbox`
// path with the real SES implementation, mirroring the production
// initialization order from `plugins-runtime/src/index.ts`:
//   repairIntrinsics (module load) → loadPlugin → createSandbox/hardenIntrinsics
//
// `hardenIntrinsics()` is deliberately NOT called up front: the first test
// must run in the production window where only `repairIntrinsics` has run.
// Tests run in declaration order; the later tests build on the locked-down
// state the first real `loadPlugin` leaves behind (via `createSandbox`).
//
// Note on SES isolation: this suite depends on Vitest's default
// file-level isolation. Each test file runs in a separate worker
// process, so SES intrinsics frozen here do not leak into other
// spec files.

const REPAIR_OPTIONS = {
  evalTaming: 'unsafeEval',
  stackFiltering: 'verbose',
  errorTaming: 'unsafe',
  consoleTaming: 'unsafe',
  errorTrapping: 'none',
  unhandledRejectionTrapping: 'none',
};

function makeManifest(
  code: string,
  permissions: Manifest['permissions'],
): Manifest {
  return {
    pluginId: 'test-plugin',
    name: 'Test Plugin',
    host: '',
    code,
    permissions,
  };
}

function makeHostFixture() {
  const listenerTypes: string[] = [];
  const listeners = new Map<symbol, string>();
  // Inline code (empty host + non-URL code) resolves without network, so no
  // fetch mock is needed. UI/modal APIs are never touched by the probe code.
  const createRectangle = vi.fn(() => ({ type: 'rectangle-marker' }));
  const selection: object[] = [{ id: 'shape-1' }];
  const context = {
    addListener: (type: string, _callback: (...args: unknown[]) => unknown) => {
      const id = Symbol(type);
      listeners.set(id, type);
      listenerTypes.push(type);
      return id;
    },
    removeListener: (id: symbol) => {
      listeners.delete(id);
    },
    theme: 'dark',
    createRectangle,
    selection,
    // Host-only member: present on the raw context but NOT part of the
    // public penpot API. Plugin code must never see it (see B-2 below).
    __internalSecret: 'host-internal',
  } as unknown as Context;
  return { context, listenerTypes, createRectangle, selection };
}

function lastCompartmentGlobalThis(): Record<string, unknown> {
  const plugins = getPlugins();
  const last = plugins[plugins.length - 1] as unknown as {
    compartment: { compartment: { globalThis: Record<string, unknown> } };
  };
  return last.compartment.compartment.globalThis;
}

describe('loadPlugin real initialization path (regression for #11001)', () => {
  beforeAll(() => {
    // Production module-load step only: repairs intrinsics WITHOUT
    // installing override taming, exactly like `index.ts` at import time.
    (
      globalThis as unknown as { repairIntrinsics(opts: object): void }
    ).repairIntrinsics({ ...REPAIR_OPTIONS });
  });

  it('loads through the real path and keeps host function augmentation working', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);

    await loadPlugin(
      makeManifest('penpot.on("finish", function () {});', ['content:read']),
    );

    // The plugin code really ran inside the sandbox: the manager registers
    // `themechange` + `finish`, and the plugin code adds its own `finish`
    // listener through the public API.
    expect(fixture.listenerTypes).toEqual(['themechange', 'finish', 'finish']);
    expect(getPlugins()).toHaveLength(1);

    // The user-facing behavior from #11001: host-side augmentation of a
    // fresh function (e.g. assigning `toString` during page navigation)
    // succeeds after a real plugin load.
    const freshWrapper = function freshWrapper() {
      return 'navigation-wrapper';
    };
    expect(() => {
      freshWrapper.toString = () => 'patched-by-runtime';
    }).not.toThrow();
    expect(fixture.listenerTypes.length).toBe(3);
  });

  it('denies the write API without permission and leaves the host untouched', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);

    await expect(
      loadPlugin(makeManifest('penpot.createRectangle();', ['content:read'])),
    ).rejects.toThrow(/content:write/);
    expect(fixture.createRectangle).not.toHaveBeenCalled();
  });

  it('allows the same write API with permission', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);

    await loadPlugin(
      makeManifest('penpot.createRectangle();', [
        'content:read',
        'content:write',
      ]),
    );
    expect(fixture.createRectangle).toHaveBeenCalledTimes(1);
  });

  it('does not expose raw host-only context members to plugin code', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);

    await loadPlugin(
      makeManifest('globalThis.__probe = typeof penpot.__internalSecret;', [
        'content:read',
      ]),
    );
    // The public `penpot` object is a boundary proxy over a curated API, not
    // the raw host context, so host-only members are invisible inside.
    expect(lastCompartmentGlobalThis()['__probe']).toBe('undefined');
  });

  it('keeps safeReturn protection on returned values without blocking allowed edits', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);

    await loadPlugin(
      makeManifest(
        'penpot.createRectangle(); ' +
          'globalThis.__selectionFrozen = Object.isFrozen(penpot.selection);',
        ['content:read', 'content:write'],
      ),
    );
    expect(fixture.createRectangle).toHaveBeenCalledTimes(1);
    expect(lastCompartmentGlobalThis()['__selectionFrozen']).toBe(true);
  });
});
