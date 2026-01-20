import { describe, it, vi, expect, beforeEach, afterEach } from 'vitest';
import { createPlugin } from './create-plugin';
import { createPluginManager } from './plugin-manager.js';
import { createSandbox } from './create-sandbox.js';
import type { Context } from '@penpot/plugin-types';
import type { Manifest } from './models/manifest.model.js';

vi.mock('./plugin-manager.js', () => ({
  createPluginManager: vi.fn(),
}));

vi.mock('./create-sandbox.js', () => ({
  createSandbox: vi.fn(),
}));

describe('createPlugin', () => {
  let mockContext: Context;
  let manifest: Manifest;
  let onCloseCallback: ReturnType<typeof vi.fn>;
  let mockPluginManager: Awaited<ReturnType<typeof createPluginManager>>;
  let mockSandbox: ReturnType<typeof createSandbox>;

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

    mockPluginManager = {
      close: vi.fn(),
      openModal: vi.fn(),
      getModal: vi.fn(),
      registerListener: vi.fn(),
      registerMessageCallback: vi.fn(),
      sendMessage: vi.fn(),
      destroyListener: vi.fn(),
      context: mockContext,
      manifest,
      timeouts: new Set(),
      code: 'console.log("Plugin running");',
    } as unknown as Awaited<ReturnType<typeof createPluginManager>>;

    mockSandbox = {
      evaluate: vi.fn(),
      cleanGlobalThis: vi.fn(),
      compartment: {},
    } as unknown as ReturnType<typeof createSandbox>;

    mockContext = {
      addListener: vi.fn(),
      removeListener: vi.fn(),
    } as unknown as Context;

    onCloseCallback = vi.fn();

    vi.mocked(createPluginManager).mockResolvedValue(mockPluginManager);
    vi.mocked(createSandbox).mockReturnValue(mockSandbox);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should create the plugin manager and sandbox, then evaluate the plugin code', async () => {
    const result = await createPlugin(mockContext, manifest, onCloseCallback);

    expect(createPluginManager).toHaveBeenCalledWith(
      mockContext,
      manifest,
      expect.any(Function),
      expect.any(Function),
    );
    expect(createSandbox).toHaveBeenCalledWith(mockPluginManager);
    expect(mockSandbox.evaluate).toHaveBeenCalled();
    expect(result).toEqual({
      plugin: mockPluginManager,
      compartment: mockSandbox,
      manifest,
    });
  });

  it('should clean globalThis and call onCloseCallback when plugin is closed', async () => {
    await createPlugin(mockContext, manifest, onCloseCallback);

    const onClose = vi.mocked(createPluginManager).mock.calls[0][2];
    onClose();

    expect(mockSandbox.cleanGlobalThis).toHaveBeenCalled();
    expect(onCloseCallback).toHaveBeenCalled();
  });

  it('should re-evaluate the plugin code when the modal is reloaded', async () => {
    await createPlugin(mockContext, manifest, onCloseCallback);

    const onReloadModal = vi.mocked(createPluginManager).mock.calls[0][3];
    onReloadModal('');

    expect(mockSandbox.evaluate).toHaveBeenCalled();
  });

  it('should call plugin.close when there is an exception during sandbox evaluation', async () => {
    vi.mocked(mockSandbox.evaluate).mockImplementation(() => {
      throw new Error('Evaluation error');
    });

    await createPlugin(mockContext, manifest, onCloseCallback);

    expect(mockPluginManager.close).toHaveBeenCalled();
  });
});
