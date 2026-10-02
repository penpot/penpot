import { expect, describe, vi } from 'vitest';
import { createApi } from './index.js';
import type { File, Page, Shape } from '@penpot/plugin-types';

const mockUrl = 'http://fake.fake/';

describe('Plugin api', () => {
  function generateMockPluginManager() {
    return {
      manifest: {
        pluginId: 'test',
        name: 'test',
        code: '',
        host: mockUrl,
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
      },
      openModal: vi.fn(),
      getModal: vi.fn(),
      registerMessageCallback: vi.fn(),
      close: vi.fn(),
      registerListener: vi.fn(),
      destroyListener: vi.fn(),
      context: {
        currentFile: null as File | null,
        currentPage: null as Page | null,
        selection: [] as Shape[],
        theme: 'dark',
        addListener: vi.fn().mockReturnValueOnce(Symbol()),
        removeListener: vi.fn(),
      },
    };
  }

  let api: ReturnType<typeof createApi>;
  let pluginManager: ReturnType<typeof generateMockPluginManager>;

  beforeEach(() => {
    pluginManager = generateMockPluginManager();

    api = createApi(pluginManager as any);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('management listing', () => {
    it.each(['none', 'loading', 'ready'])(
      'lists metadata with workspace status %s',
      async (status) => {
        const projects = [{ id: 'project-id' }];
        const files = [{ id: 'file-id' }];
        const management = {
          workspace: { status },
          listProjects: vi.fn().mockResolvedValue(projects),
          listFiles: vi.fn().mockResolvedValue(files),
        };
        const { penpotMgmt } = createApi({
          ...pluginManager,
          manifest: { ...pluginManager.manifest, scope: 'global' },
          context: { ...pluginManager.context, management },
        } as any);

        await expect(penpotMgmt.listProjects()).resolves.toEqual(projects);
        expect(management.listProjects).toHaveBeenLastCalledWith(undefined);
        await penpotMgmt.listProjects({ teamId: 'team-id' });
        expect(management.listProjects).toHaveBeenLastCalledWith({
          teamId: 'team-id',
        });
        await expect(
          penpotMgmt.listFiles({ projectId: 'project-id' }),
        ).resolves.toEqual(files);
        expect(management.listFiles).toHaveBeenCalledWith({
          projectId: 'project-id',
        });
      },
    );

    it('requires content:read before querying metadata', () => {
      const management = {
        listProjects: vi.fn(),
        listFiles: vi.fn(),
      };
      const { penpotMgmt } = createApi({
        ...pluginManager,
        manifest: {
          ...pluginManager.manifest,
          permissions: [],
          scope: 'global',
        },
        context: { ...pluginManager.context, management },
      } as any);

      expect(() => penpotMgmt.listProjects()).toThrow();
      expect(() => penpotMgmt.listFiles({ projectId: 'project-id' })).toThrow();
      expect(management.listProjects).not.toHaveBeenCalled();
      expect(management.listFiles).not.toHaveBeenCalled();
    });
  });

  describe('ui', () => {
    describe.concurrent('permissions', () => {
      const api = createApi({
        ...pluginManager,
        permissions: [],
      } as any);

      it('on', () => {
        const callback = vi.fn();

        expect(() => {
          api.penpot.on('filechange', callback);
        }).toThrow();

        expect(() => {
          api.penpot.on('pagechange', callback);
        }).toThrow();

        expect(() => {
          api.penpot.on('selectionchange', callback);
        }).toThrow();
      });
    });

    it('get file state', () => {
      const examplePage = {
        name: 'test',
        id: '123',
      } as Page;

      pluginManager.context.currentPage = examplePage;

      const pageState = api.penpot.currentPage;

      expect(pageState).toEqual(examplePage);
    });

    it('get page state', () => {
      const exampleFile = {
        name: 'test',
        id: '123',
        revn: 0,
      } as File;

      pluginManager.context.currentFile = exampleFile;

      const fileState = api.penpot.currentFile;

      expect(fileState).toEqual(exampleFile);
    });

    it('get selection', () => {
      const selection = [
        { id: '123', name: 'test' },
        { id: 'abc', name: 'test2' },
      ] as Shape[];

      pluginManager.context.selection = selection;

      const currentSelection = api.penpot.selection;

      expect(currentSelection).toEqual(selection);
    });
  });
});
