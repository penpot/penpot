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

  describe('management', () => {
    it.each(['none', 'loading', 'ready'])(
      'lists teams and projects and loads files with workspace status %s',
      async (status) => {
        const teams = [{ id: 'team-id' }];
        const projects = [{ id: 'project-id' }];
        const file = { id: 'file-id' };
        const management = {
          workspace: { status },
          listTeams: vi.fn().mockResolvedValue(teams),
          createTeam: vi.fn().mockResolvedValue(teams[0]),
          listProjects: vi.fn().mockResolvedValue(projects),
          getFile: vi.fn().mockResolvedValue(file),
        };
        const { penpotMgmt } = createApi({
          ...pluginManager,
          manifest: {
            ...pluginManager.manifest,
            permissions: ['content:read', 'manage:teams'],
            scope: 'global',
          },
          context: { ...pluginManager.context, management },
        } as any);

        await expect(penpotMgmt.listTeams()).resolves.toEqual(teams);
        await expect(
          penpotMgmt.createTeam({ name: 'Marketing' }),
        ).resolves.toEqual(teams[0]);
        expect(management.createTeam).toHaveBeenCalledWith({
          name: 'Marketing',
        });
        await expect(penpotMgmt.listProjects()).resolves.toEqual(projects);
        expect(management.listProjects).toHaveBeenLastCalledWith(undefined);
        await penpotMgmt.listProjects({ teamId: 'team-id' });
        expect(management.listProjects).toHaveBeenLastCalledWith({
          teamId: 'team-id',
        });
        await expect(penpotMgmt.getFile('file-id')).resolves.toEqual(file);
        expect(management.getFile).toHaveBeenCalledWith('file-id');
      },
    );

    it('requires content:read before listing projects or loading files', () => {
      const management = {
        listProjects: vi.fn(),
        getFile: vi.fn(),
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

      expect(() => penpotMgmt.listProjects()).toThrow(
        'Permission content:read is not granted',
      );
      expect(() => penpotMgmt.getFile('file-id')).toThrow(
        'Permission content:read is not granted',
      );
      expect(management.listProjects).not.toHaveBeenCalled();
      expect(management.getFile).not.toHaveBeenCalled();
    });

    it('requires manage:teams before listing or creating teams', () => {
      const management = { listTeams: vi.fn(), createTeam: vi.fn() };
      const { penpotMgmt } = createApi({
        ...pluginManager,
        manifest: {
          ...pluginManager.manifest,
          permissions: ['content:read'],
          scope: 'global',
        },
        context: { ...pluginManager.context, management },
      } as any);

      expect(() => penpotMgmt.listTeams()).toThrow(
        'Permission manage:teams is not granted',
      );
      expect(() => penpotMgmt.createTeam({ name: 'Marketing' })).toThrow(
        'Permission manage:teams is not granted',
      );
      expect(management.listTeams).not.toHaveBeenCalled();
      expect(management.createTeam).not.toHaveBeenCalled();
    });

    it('preserves backend errors', async () => {
      const error = new Error('Access denied');
      const { penpotMgmt } = createApi({
        ...pluginManager,
        manifest: {
          ...pluginManager.manifest,
          permissions: ['content:read', 'manage:teams'],
          scope: 'global',
        },
        context: {
          ...pluginManager.context,
          management: {
            listTeams: vi.fn().mockRejectedValue(error),
            getFile: vi.fn().mockRejectedValue(error),
          },
        },
      } as any);

      await expect(penpotMgmt.listTeams()).rejects.toBe(error);
      await expect(penpotMgmt.getFile('file-id')).rejects.toBe(error);
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
