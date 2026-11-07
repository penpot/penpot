import { describe, it, vi, expect, beforeAll } from 'vitest';
import 'ses';
import {
  loadPlugin,
  setContextBuilder,
  getPlugins,
  ɵunloadPlugin,
} from './load-plugin';
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
  const listeners = new Map<
    symbol,
    { type: string; callback: (...args: unknown[]) => unknown }
  >();
  const shapes: object[] = [];
  // Inline code (empty host + non-URL code) resolves without network, so no
  // fetch mock is needed. UI/modal APIs are never touched by the probe code.
  const createRectangle = vi.fn(() => {
    const shape = { type: 'rectangle-marker' };
    shapes.push(shape);
    return shape;
  });
  const selection: object[] = [{ id: 'shape-1' }];
  const context = {
    addListener: (type: string, callback: (...args: unknown[]) => unknown) => {
      const id = Symbol(type);
      listeners.set(id, { type, callback });
      listenerTypes.push(type);
      return id;
    },
    removeListener: (id: symbol) => {
      listeners.delete(id);
    },
    theme: 'dark',
    management: { workspace: { status: 'ready' } },
    createRectangle,
    selection,
    // Host-only member: present on the raw context but NOT part of the
    // public penpot API. Plugin code must never see it (see B-2 below).
    __internalSecret: 'host-internal',
  } as unknown as Context;
  return {
    context,
    listeners,
    listenerTypes,
    createRectangle,
    selection,
    shapes,
  };
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
    // `themechange` + `finish` + `logout`, and the plugin code adds its own `finish`
    // listener through the public API.
    expect(fixture.listenerTypes).toEqual([
      'themechange',
      'finish',
      'logout',
      'finish',
    ]);
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
    expect(fixture.listenerTypes.length).toBe(4);
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
  it.each(['before', 'after'])(
    'discards a cancelled global load that completes %s its replacement',
    async (order) => {
      const fixture = makeHostFixture();
      setContextBuilder(() => fixture.context);
      const manifest = {
        ...makeManifest('plugin.js', ['content:read', 'content:write']),
        host: 'https://plugins.test/',
        scope: 'global' as const,
      };
      ɵunloadPlugin(manifest.pluginId);
      let finishFetch!: (response: object) => void;
      const response = new Promise((resolve) => {
        finishFetch = resolve;
      });
      const fetch = vi.fn().mockReturnValue(response);
      vi.stubGlobal('fetch', fetch);
      try {
        const cancelled = loadPlugin(manifest);
        await loadPlugin(manifest);
        expect(fetch).toHaveBeenCalledOnce();
        ɵunloadPlugin(manifest.pluginId);
        const replacement = () =>
          loadPlugin({
            ...manifest,
            host: '',
            code: 'globalThis.marker = "replacement";',
          });
        if (order === 'after') await replacement();
        finishFetch({
          ok: true,
          text: async () => 'penpot.createRectangle();',
        });
        await cancelled;
        expect(fixture.shapes).toEqual([]);
        if (order === 'before') {
          expect(getPlugins()).toHaveLength(0);
          expect(fixture.listeners.size).toBe(0);
          await replacement();
        }
        expect(getPlugins()).toHaveLength(1);
        expect(lastCompartmentGlobalThis()['marker']).toBe('replacement');
        expect(fixture.listeners.size).toBe(3);
      } finally {
        ɵunloadPlugin(manifest.pluginId);
        vi.unstubAllGlobals();
      }
    },
  );

  it('cancels a workspace load when another load replaces the same plugin', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);
    const manifest = {
      ...makeManifest('plugin.js', ['content:read', 'content:write']),
      host: 'https://plugins.test/',
    };
    let finishFetch!: (response: object) => void;
    vi.stubGlobal(
      'fetch',
      () =>
        new Promise((resolve) => {
          finishFetch = resolve;
        }),
    );
    try {
      const cancelled = loadPlugin(manifest);
      await loadPlugin({
        ...manifest,
        host: '',
        code: 'globalThis.marker = "replacement";',
      });
      finishFetch({ ok: true, text: async () => 'penpot.createRectangle();' });
      await cancelled;
      expect(fixture.shapes).toEqual([]);
      expect(getPlugins()).toHaveLength(1);
      expect(lastCompartmentGlobalThis()['marker']).toBe('replacement');
    } finally {
      for (const plugin of [...getPlugins()]) plugin.plugin.close();
      vi.unstubAllGlobals();
    }
  });

  it('ignores a fetch failure after a load has been cancelled', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);
    const manifest = {
      ...makeManifest('plugin.js', []),
      host: 'https://plugins.test/',
      scope: 'global' as const,
    };
    let failFetch!: (error: Error) => void;
    vi.stubGlobal(
      'fetch',
      () =>
        new Promise((_resolve, reject) => {
          failFetch = reject;
        }),
    );
    try {
      const cancelled = loadPlugin(manifest);
      ɵunloadPlugin(manifest.pluginId);
      await loadPlugin({
        ...manifest,
        host: '',
        code: 'globalThis.marker = "replacement";',
      });
      failFetch(new Error('Cancelled fetch failed'));
      await cancelled;
      expect(getPlugins()).toHaveLength(1);
      expect(lastCompartmentGlobalThis()['marker']).toBe('replacement');
      expect(fixture.listeners.size).toBe(3);
    } finally {
      ɵunloadPlugin(manifest.pluginId);
      vi.unstubAllGlobals();
    }
  });

  it('allows retrying a global plugin after a failed load', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);
    const manifest = {
      ...makeManifest('throw new Error("load failed");', []),
      scope: 'global' as const,
    };
    await expect(loadPlugin(manifest)).rejects.toThrow('load failed');
    expect(getPlugins()).toHaveLength(0);
    await loadPlugin({ ...manifest, code: 'globalThis.marker = "retry";' });
    expect(lastCompartmentGlobalThis()['marker']).toBe('retry');
    ɵunloadPlugin(manifest.pluginId);
    expect(fixture.listeners.size).toBe(0);
  });

  it('keeps a global sandbox through navigation and other plugin loads, then closes it on logout', async () => {
    const fixture = makeHostFixture();
    let workspace = {
      status: 'none',
      fileId: null,
      fileName: null,
      teamId: null,
    };
    const listeners = new Map<
      symbol,
      { type: string; callback: (value?: unknown) => void }
    >();
    const context = {
      ...fixture.context,
      management: {
        get workspace() {
          return workspace;
        },
        openFile: async () => {},
      },
      addListener(type: string, callback: (value?: unknown) => void) {
        const id = Symbol();
        listeners.set(id, { type, callback });
        return id;
      },
      removeListener(id: symbol) {
        listeners.delete(id);
      },
    } as unknown as Context;
    setContextBuilder(() => context);
    const manifest = {
      ...makeManifest(
        'globalThis.savedPenpot = penpot; globalThis.probe = penpotMgmt.workspace.status;',
        ['content:read', 'content:write'],
      ),
      pluginId: 'global-plugin',
      scope: 'global' as const,
    };
    await loadPlugin(manifest);
    const globalPlugin = getPlugins().find(
      (plugin) => plugin.manifest.pluginId === 'global-plugin',
    )!;
    const globals = globalPlugin.compartment.compartment.globalThis;
    expect(globals['probe']).toBe('none');
    expect(() =>
      globalPlugin.compartment.compartment.evaluate('penpot.createRectangle()'),
    ).toThrow(/No workspace/);
    for (const listener of [...listeners.values()]) {
      if (listener.type === 'finish') listener.callback();
    }
    await loadPlugin(makeManifest('globalThis.workspacePlugin = true;', []));
    expect(getPlugins()).toContain(globalPlugin);
    await loadPlugin(manifest);
    expect(
      getPlugins().filter(
        (plugin) => plugin.manifest.pluginId === 'global-plugin',
      ),
    ).toHaveLength(1);
    workspace = { ...workspace, status: 'ready' };
    globalPlugin.compartment.compartment.evaluate(
      'penpot.createRectangle(); globalThis.sameFacade = savedPenpot === penpot;',
    );
    expect(globals['sameFacade']).toBe(true);
    expect(fixture.createRectangle).toHaveBeenCalledOnce();
    await expect(
      loadPlugin(makeManifest('throw new Error("failure");', [])),
    ).rejects.toThrow('failure');
    expect(getPlugins()).toContain(globalPlugin);
    for (const listener of [...listeners.values()]) {
      if (listener.type === 'logout') listener.callback();
    }
    expect(getPlugins()).toHaveLength(0);
    expect(globals['penpotMgmt']).toBeUndefined();
    expect(listeners.size).toBe(0);
  });

  it('cancels a global load at logout before it can execute in another session', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);
    const manifest = {
      ...makeManifest('plugin.js', ['content:read', 'content:write']),
      pluginId: 'logout-plugin',
      host: 'https://plugins.test/',
      scope: 'global' as const,
    };
    let finishFetch!: (response: object) => void;
    vi.stubGlobal(
      'fetch',
      () =>
        new Promise((resolve) => {
          finishFetch = resolve;
        }),
    );
    try {
      const cancelled = loadPlugin(manifest);
      for (const listener of [...fixture.listeners.values()]) {
        if (listener.type === 'logout') listener.callback();
      }
      await loadPlugin({
        ...manifest,
        host: '',
        code: 'globalThis.marker = "new-session";',
      });
      finishFetch({ ok: true, text: async () => 'penpot.createRectangle();' });
      await cancelled;
      expect(fixture.shapes).toEqual([]);
      expect(getPlugins()).toHaveLength(1);
      expect(lastCompartmentGlobalThis()['marker']).toBe('new-session');
      expect(fixture.listeners.size).toBe(3);
    } finally {
      ɵunloadPlugin(manifest.pluginId);
      vi.unstubAllGlobals();
    }
  });

  it('cleans up a failed fetch and allows loading the plugin again', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);
    const manifest = {
      ...makeManifest('plugin.js', []),
      pluginId: 'failed-fetch-plugin',
      host: 'https://plugins.test/',
      scope: 'global' as const,
    };
    vi.stubGlobal('fetch', () => Promise.reject(new Error('Fetch failed')));
    try {
      await expect(loadPlugin(manifest)).rejects.toThrow('Fetch failed');
      expect(getPlugins()).toHaveLength(0);
      expect(fixture.listeners.size).toBe(0);
      await loadPlugin({
        ...manifest,
        host: '',
        code: 'globalThis.marker = "retry";',
      });
      expect(lastCompartmentGlobalThis()['marker']).toBe('retry');
    } finally {
      ɵunloadPlugin(manifest.pluginId);
      vi.unstubAllGlobals();
    }
  });

  it('does not register a global plugin that closes during startup and permits reopening', async () => {
    const fixture = makeHostFixture();
    setContextBuilder(() => fixture.context);
    const manifest = {
      ...makeManifest('penpot.closePlugin();', [
        'content:read',
        'content:write',
      ]),
      pluginId: 'self-closing-plugin',
      scope: 'global' as const,
    };
    try {
      await loadPlugin(manifest);
      expect(getPlugins()).toHaveLength(0);
      expect(fixture.listeners.size).toBe(0);
      await loadPlugin({ ...manifest, code: 'penpot.createRectangle();' });
      expect(fixture.shapes).toEqual([{ type: 'rectangle-marker' }]);
      expect(getPlugins()).toHaveLength(1);
    } finally {
      ɵunloadPlugin(manifest.pluginId);
    }
    expect(getPlugins()).toHaveLength(0);
    expect(fixture.listeners.size).toBe(0);
  });
});
