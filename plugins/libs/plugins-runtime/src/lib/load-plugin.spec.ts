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

vi.mock('./create-sandbox.js', () => ({
  markPluginError: vi.fn(),
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
      undefined,
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
    vi.mocked(createPlugin).mockRejectedValue(
      new Error('Plugin creation failed'),
    );

    try {
      await loadPlugin(manifest);
    } catch (err) {
      expect.assert(err);
    }

    expect(getPlugins()).toHaveLength(0);
  });

  it('should handle messages sent to plugins', async () => {
    const mockIframeWindow = { nodeType: 1 } as unknown as Window;
    const mockPluginWithIframe = {
      plugin: {
        close: mockClose,
        sendMessage: vi.fn(),
      },
      iframeWindow: mockIframeWindow,
      manifest: { ...manifest, host: 'http://localhost:4202' },
    } as unknown as Awaited<ReturnType<typeof createPlugin>>;

    vi.mocked(createPlugin).mockResolvedValue(mockPluginWithIframe);

    await loadPlugin(manifest);

    const event = new MessageEvent('message', {
      data: 'test-message',
      origin: 'http://localhost:4202',
    });
    Object.defineProperty(event, 'source', { value: mockIframeWindow });
    window.dispatchEvent(event);

    expect(mockPluginWithIframe.plugin.sendMessage).toHaveBeenCalledWith(
      'test-message',
    );
  });

  it('should reject messages from unrecognized sources', async () => {
    await loadPlugin(manifest);

    const event = new MessageEvent('message', {
      data: 'malicious-message',
      origin: 'https://evil.com',
    });
    Object.defineProperty(event, 'source', {
      value: { nodeType: 999 } as unknown as Window,
    });
    window.dispatchEvent(event);

    expect(mockPluginApi.plugin.sendMessage).not.toHaveBeenCalled();
  });

  it('should only route messages to the sender plugin', async () => {
    const mockIframeWindow1 = { nodeType: 1 } as unknown as Window;
    const mockIframeWindow2 = { nodeType: 2 } as unknown as Window;

    const mockPluginApi1 = {
      plugin: {
        close: vi.fn(),
        sendMessage: vi.fn(),
      },
      iframeWindow: mockIframeWindow1,
      manifest: { ...manifest, host: 'http://localhost:4202' },
    } as unknown as Awaited<ReturnType<typeof createPlugin>>;

    const mockPluginApi2 = {
      plugin: {
        close: vi.fn(),
        sendMessage: vi.fn(),
      },
      iframeWindow: mockIframeWindow2,
      manifest: { ...manifest, host: 'http://localhost:4203' },
    } as unknown as Awaited<ReturnType<typeof createPlugin>>;

    vi.mocked(createPlugin).mockResolvedValue(mockPluginApi1);
    await loadPlugin(manifest);

    vi.mocked(createPlugin).mockResolvedValue(mockPluginApi2);
    await loadPlugin(manifest);

    const event = new MessageEvent('message', {
      data: 'test',
      origin: 'http://localhost:4203',
    });
    Object.defineProperty(event, 'source', { value: mockIframeWindow2 });
    window.dispatchEvent(event);

    expect(mockPluginApi2.plugin.sendMessage).toHaveBeenCalledWith('test');
    expect(mockPluginApi1.plugin.sendMessage).not.toHaveBeenCalled();
  });

  it('should load plugin using ɵloadPlugin', async () => {
    await ɵloadPlugin(manifest);

    expect(createPlugin).toHaveBeenCalledWith(
      mockContext,
      manifest,
      expect.any(Function),
      undefined,
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
      undefined,
    );
  });
});
