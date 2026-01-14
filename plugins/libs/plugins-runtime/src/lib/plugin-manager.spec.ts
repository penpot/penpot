import { describe, it, vi, expect, beforeEach, afterEach } from 'vitest';
import { createPluginManager } from './plugin-manager';
import { loadManifestCode, getValidUrl } from './parse-manifest.js';
import { PluginModalElement } from './modal/plugin-modal.js';
import { openUIApi } from './api/openUI.api.js';
import type { Context, Theme } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';

vi.mock('./parse-manifest.js', () => ({
  loadManifestCode: vi.fn(),
  getValidUrl: vi.fn(),
}));

vi.mock('./api/openUI.api.js', () => ({
  openUIApi: vi.fn(),
}));

describe('createPluginManager', () => {
  let mockContext: Context;
  let manifest: Manifest;
  let onCloseCallback: ReturnType<typeof vi.fn>;
  let onReloadModal: ReturnType<typeof vi.fn>;
  let mockModal: {
    setTheme: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    addEventListener: ReturnType<typeof vi.fn>;
    removeEventListener: ReturnType<typeof vi.fn>;
    getAttribute: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    manifest = {
      pluginId: 'test-plugin',
      name: 'Test Plugin',
      host: 'https://example.com',
      code: '',
      permissions: [
        'content:read',
        'content:write',
        'library:read',
        'library:write',
        'user:read',
        'comment:read',
        'comment:write',
        'allow:downloads',
        'allow:localstorage',
      ],
    };

    mockModal = {
      setTheme: vi.fn(),
      remove: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      getAttribute: vi.fn(),
    };

    vi.mocked(openUIApi).mockReturnValue(
      mockModal as unknown as PluginModalElement,
    );

    mockContext = {
      theme: 'light',
      addListener: vi.fn().mockReturnValue(Symbol()),
      removeListener: vi.fn(),
    } as unknown as Context;

    onCloseCallback = vi.fn();
    onReloadModal = vi.fn();

    vi.mocked(loadManifestCode).mockResolvedValue(
      'console.log("Plugin loaded");',
    );
    vi.mocked(getValidUrl).mockReturnValue('https://example.com/plugin');
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should load the plugin and set up listeners', async () => {
    await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    expect(loadManifestCode).toHaveBeenCalledWith(manifest);
    expect(mockContext.addListener).toHaveBeenCalledWith(
      'themechange',
      expect.any(Function),
    );
    expect(mockContext.addListener).toHaveBeenCalledWith(
      'finish',
      expect.any(Function),
    );
  });

  it('should open a modal with the correct URL and theme', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    pluginManager.openModal('Test Modal', '/test-url', {
      width: 400,
      height: 300,
    });

    expect(getValidUrl).toHaveBeenCalledWith(manifest.host, '/test-url');
    expect(openUIApi).toHaveBeenCalledWith(
      'Test Modal',
      'https://example.com/plugin',
      'light',
      { width: 400, height: 300 },
      true,
    );
    expect(mockModal.setTheme).toHaveBeenCalledWith('light');
    expect(mockModal.addEventListener).toHaveBeenCalledWith(
      'close',
      expect.any(Function),
      { once: true },
    );
    expect(mockModal.addEventListener).toHaveBeenCalledWith(
      'load',
      expect.any(Function),
    );
  });

  it('should not open a new modal if the URL has not changed', async () => {
    mockModal.getAttribute.mockReturnValue('https://example.com/plugin');

    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    pluginManager.openModal('Test Modal', '/test-url');
    pluginManager.openModal('Test Modal', '/test-url');

    expect(openUIApi).toHaveBeenCalledTimes(1);
  });

  it('should handle theme changes and update the modal theme', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    pluginManager.openModal('Test Modal', '/test-url');

    const themeChangeCallback = vi
      .mocked(mockContext.addListener)
      .mock.calls.find((call) => call[0] === 'themechange')?.[1];

    if (!themeChangeCallback) {
      throw new Error('Theme change callback not found');
    }

    themeChangeCallback('dark' as Theme);

    expect(mockModal.setTheme).toHaveBeenCalledWith('dark');
  });

  it('should remove all event listeners and close the plugin', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    pluginManager.openModal('Test Modal', '/test-url');

    pluginManager.close();

    expect(mockContext.removeListener).toHaveBeenCalled();
    expect(mockModal.removeEventListener).toHaveBeenCalledWith(
      'close',
      expect.any(Function),
    );
    expect(mockModal.remove).toHaveBeenCalled();
    expect(onCloseCallback).toHaveBeenCalled();
  });

  it('shoud clean setTimeout when plugin is closed', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    pluginManager.timeouts.add(setTimeout(() => {}, 1000));
    pluginManager.timeouts.add(setTimeout(() => {}, 1000));

    expect(pluginManager.timeouts.size).toBe(2);

    pluginManager.close();

    expect(pluginManager.timeouts.size).toBe(0);
  });

  it('should reload the modal when reloaded', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    await pluginManager.openModal('Test Modal', '/test-url');

    const loadCallback = mockModal.addEventListener.mock.calls.find((call) => {
      return call[0] === 'load';
    });

    if (loadCallback) {
      // initial load
      await loadCallback[1]();

      // reload
      await loadCallback[1]();

      expect(onReloadModal).toHaveBeenCalledWith(
        'console.log("Plugin loaded");',
      );
    }
  });

  it('should register and trigger message callbacks', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    const callback = vi.fn();
    pluginManager.registerMessageCallback(callback);

    pluginManager.sendMessage('Test Message');

    expect(callback).toHaveBeenCalledWith('Test Message');
  });

  it('should register and remove listeners', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    const callback = vi.fn();
    const listenerId = pluginManager.registerListener('themechange', callback);

    expect(mockContext.addListener).toHaveBeenCalledWith(
      'themechange',
      expect.any(Function),
      undefined,
    );

    pluginManager.destroyListener(listenerId);

    expect(mockContext.removeListener).toHaveBeenCalledWith(listenerId);
  });

  it('should clean up all event listeners on close', async () => {
    const pluginManager = await createPluginManager(
      mockContext,
      manifest,
      onCloseCallback,
      onReloadModal,
    );

    pluginManager.close();

    expect(mockContext.removeListener).toHaveBeenCalled();
    expect(onCloseCallback).toHaveBeenCalled();
  });
});
