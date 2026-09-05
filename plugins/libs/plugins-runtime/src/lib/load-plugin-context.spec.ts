import { describe, it, vi, expect, beforeAll, beforeEach } from 'vitest';
import 'ses';
import { loadPlugin, setContextBuilder, getPlugins } from './load-plugin';
import { createPlugin } from './create-plugin';
import { ses } from './ses.js';
import type { Context } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';

vi.mock('./create-plugin', () => ({
  createPlugin: vi.fn(),
}));

// NOTE: `./ses.js` is intentionally NOT mocked here. This test verifies the
// real host/sandbox hardening boundary, so `ses.harden` must keep its real
// implementation backed by a real SES bootstrap (see `beforeAll` below).

describe('loadPlugin host context boundary (regression for #11001)', () => {
  let manifest: Manifest;

  beforeAll(() => {
    // Real SES bootstrap: repairs intrinsics and installs the global
    // `harden` that `ses.harden` delegates to. Without this, `ses.harden`
    // would throw `ReferenceError: harden is not defined` instead of
    // exercising the real deep-freeze behavior under test.
    (globalThis as unknown as { repairIntrinsics(): void }).repairIntrinsics();
    ses.hardenIntrinsics();
  });

  beforeEach(() => {
    manifest = {
      pluginId: 'test-plugin',
      name: 'Test Plugin',
      host: '',
      code: '',
      permissions: ['content:read'],
    };

    vi.mocked(createPlugin).mockResolvedValue({
      plugin: {
        close: vi.fn(),
        sendMessage: vi.fn(),
      },
    } as unknown as Awaited<ReturnType<typeof createPlugin>>);
  });

  it('control: real ses.harden deep-freezes host-owned functions', () => {
    // Proves the real SES behavior this regression test guards against:
    // hardening a host-created context permanently freezes host-owned
    // functions reachable through it.
    const hostListener = function hostListener() {
      return 'host-value';
    };
    const hostContext = { addListener: hostListener };

    ses.harden(hostContext);

    expect(Object.isFrozen(hostListener)).toBe(true);
    expect(Object.isExtensible(hostListener)).toBe(false);
    // A later host-side augmentation of the frozen listener (e.g. a
    // Zone.js-style `toString` patch applied on page navigation) throws
    // instead of succeeding — the crash signature from #11001.
    expect(() => {
      hostListener.toString = () => 'patched-by-host';
    }).toThrow(TypeError);
  });

  it('does not freeze host-owned functions reachable through the context', async () => {
    const hostListener = function hostListener() {
      return 'host-value';
    };
    const nestedHostObject = {
      nestedFn() {
        return 'nested';
      },
    };
    const hostContext = {
      addListener: hostListener,
      nested: nestedHostObject,
    } as unknown as Context;

    setContextBuilder(() => hostContext);

    const hardenSpy = vi.spyOn(ses, 'harden');

    await loadPlugin(manifest);

    // The host context itself must be passed through untouched so the host
    // can keep modifying its own runtime objects (e.g. on page navigation).
    expect(createPlugin).toHaveBeenCalledWith(
      hostContext,
      manifest,
      expect.any(Function),
      undefined,
    );

    // Host-owned functions must remain extensible: page navigation and
    // runtime code may patch/augment them (e.g. assigning `toString` on a
    // wrapped listener). A deep `ses.harden(context)` here would freeze
    // them and turn such later assignments into
    // `TypeError: Cannot assign to read only property 'toString'`.
    expect(Object.isFrozen(hostListener)).toBe(false);
    expect(Object.isExtensible(hostListener)).toBe(true);
    expect(Object.isFrozen(nestedHostObject)).toBe(false);
    expect(() => {
      hostListener.toString = () => 'patched-by-host';
    }).not.toThrow();

    expect(hardenSpy).not.toHaveBeenCalled();
    expect(getPlugins()).toHaveLength(1);

    hardenSpy.mockRestore();
  });
});
