import { describe, it, vi, expect, beforeEach, afterEach } from 'vitest';
import {
  loadPlugin,
  ɵloadPlugin,
  ɵloadPluginByUrl,
  setContextBuilder,
  getPlugins,
} from './load-plugin';
import { loadManifest } from './parse-manifest';
import { createPlugin } from './create-plugin';
import type { Context } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';

vi.mock('./parse-manifest', () => ({
  loadManifest: vi.fn(),
}));

vi.mock('./create-plugin', () => ({
  createPlugin: vi.fn(),
}));

vi.mock('./ses.js', () => ({
  ses: {
    harden: vi.fn().mockImplementation((obj) => obj),
  },
}));

describe('plugin-loader', () => {
  let mockContext: Context;
  let manifest: Manifest;
  let mockPluginApi: Awaited<ReturnType<typeof createPlugin>>;
  let mockClose: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    manifest = {
      pluginId: 'test-plugin',
      name: 'Test Plugin',
      host: '',
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

    mockClose = vi.fn();
    mockPluginApi = {
      plugin: {
        close: mockClose,
        sendMessage: vi.fn(),
      },
    } as unknown as Awaited<ReturnType<typeof createPlugin>>;

    mockContext = {
      addListener: vi.fn(),
      removeListener: vi.fn(),
    } as unknown as Context;

    vi.mocked(createPlugin).mockResolvedValue(mockPluginApi);
    setContextBuilder(() => mockContext);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should load and initialize a plugin', async () => {
    await loadPlugin(manifest);

    expect(createPlugin).toHaveBeenCalledWith(
      mockContext,
      manifest,
      expect.any(Function),
    );
    expect(mockPluginApi.plugin.close).not.toHaveBeenCalled();
    expect(getPlugins()).toHaveLength(1);
  });

  it('should close all plugins before loading a new one', async () => {
    await loadPlugin(manifest);
    await loadPlugin(manifest);

    expect(mockClose).toHaveBeenCalledTimes(1);
    expect(createPlugin).toHaveBeenCalledTimes(2);
  });

  it('should remove the plugin from the list on close', async () => {
    await loadPlugin(manifest);

    const closeCallback = vi.mocked(createPlugin).mock.calls[0][2];
    closeCallback();

    expect(getPlugins()).toHaveLength(0);
  });

  it('should handle errors and close all plugins', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    vi.mocked(createPlugin).mockRejectedValue(
      new Error('Plugin creation failed'),
    );

    await loadPlugin(manifest);

    expect(getPlugins()).toHaveLength(0);
    expect(consoleSpy).toHaveBeenCalled();
  });

  it('should handle messages sent to plugins', async () => {
    await loadPlugin(manifest);

    window.dispatchEvent(new MessageEvent('message', { data: 'test-message' }));

    expect(mockPluginApi.plugin.sendMessage).toHaveBeenCalledWith(
      'test-message',
    );
  });

  it('should load plugin using ɵloadPlugin', async () => {
    await ɵloadPlugin(manifest);

    expect(createPlugin).toHaveBeenCalledWith(
      mockContext,
      manifest,
      expect.any(Function),
    );
  });

  it('should load plugin by URL using ɵloadPluginByUrl', async () => {
    const manifestUrl = 'https://example.com/manifest.json';
    vi.mocked(loadManifest).mockResolvedValue(manifest);

    await ɵloadPluginByUrl(manifestUrl);

    expect(loadManifest).toHaveBeenCalledWith(manifestUrl);
    expect(createPlugin).toHaveBeenCalledWith(
      mockContext,
      manifest,
      expect.any(Function),
    );
  });
});
