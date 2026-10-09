import type { Project, ProjectFile, Team } from '@penpot/plugin-types';
import { expect, expectReject } from '../framework/expect';
import { describe, test } from '../framework/registry';
import { temporaryName } from '../framework/temporary';
import type { TestContext } from '../framework/types';
import { waitFor, waitForAsync } from './wait';

// Application management (`penpotMgmt`), available because the suite runs with
// global scope. These tests create projects and files and move between them,
// so they need the real backend. Every project they create has a temporary
// name, and the live CI driver deletes those projects afterwards.

async function currentTeam(ctx: TestContext): Promise<Team> {
  const teamId = ctx.penpotMgmt.workspace.teamId;
  const team = (await ctx.penpotMgmt.listTeams()).find((t) => t.id === teamId);
  if (!team) throw new Error('The current team is not listed');
  return team;
}

// One temporary project per run holds the files that tests only read.
function temporaryProject(ctx: TestContext): Promise<Project> {
  return ctx.fixture('management:project', async () =>
    (await currentTeam(ctx)).createProject({ name: temporaryName('files') }),
  );
}

async function temporaryFile(
  ctx: TestContext,
  name: string,
): Promise<ProjectFile> {
  return (await temporaryProject(ctx)).createFile({ name });
}

// Opens `file`, runs `fn` there and comes back to the file the test started in.
async function inFile(
  ctx: TestContext,
  file: ProjectFile,
  fn: () => void | Promise<void>,
): Promise<void> {
  const home = ctx.penpotMgmt.workspace;
  await file.open();
  try {
    await fn();
  } finally {
    await ctx.penpotMgmt.openFile(home.fileId!, {
      teamId: home.teamId ?? undefined,
    });
  }
}

async function listedFile(
  project: Project,
  fileId: string,
): Promise<ProjectFile | undefined> {
  return (await project.listFiles()).find((f) => f.id === fileId);
}

async function listedProject(
  ctx: TestContext,
  projectId: string,
): Promise<Project | undefined> {
  return (await ctx.penpotMgmt.listProjects()).find((p) => p.id === projectId);
}

interface BrandLibrary {
  file: ProjectFile;
  colorName: string;
  typographyName: string;
  componentName: string;
}

// A shared library with one color, typography and component, built once per
// run in its own file. Tests connect it to the open file, and connected
// libraries are no longer listed as available, so each run needs a new one.
function sharedLibrary(ctx: TestContext): Promise<BrandLibrary> {
  return ctx.fixture('management:brand-library', async () => {
    const file = await temporaryFile(ctx, 'Brand library');
    const library: BrandLibrary = {
      file,
      colorName: 'brand-primary',
      typographyName: 'brand-heading',
      componentName: 'brand-button',
    };

    await inFile(ctx, file, () => {
      const local = ctx.penpot.library.local;
      const color = local.createColor();
      color.name = library.colorName;
      color.color = '#112233';

      const typography = local.createTypography();
      typography.name = library.typographyName;

      const rect = ctx.penpot.createRectangle();
      const component = local.createComponent([rect]);
      component.name = library.componentName;
    });

    file.shared = true;
    const isListed = async () =>
      (await ctx.penpot.library.availableLibraries()).some(
        (l) => l.id === file.id,
      );
    await waitForAsync(isListed);
    if (!(await isListed())) {
      throw new Error('The shared library is not listed as available');
    }
    return library;
  });
}

describe('Management', () => {
  test('workspace describes the open file', (ctx) => {
    const workspace = ctx.penpotMgmt.workspace;
    expect(workspace.status).toBe('ready');
    expect(workspace.fileId).toBe(ctx.penpot.currentFile?.id);
    expect(workspace.fileName).toBe(ctx.penpot.currentFile?.name);
    expect(typeof workspace.teamId).toBe('string');
  });

  test('openFile resolves at once for the open file', async (ctx) => {
    const workspace = ctx.penpotMgmt.workspace;
    await ctx.penpotMgmt.openFile(workspace.fileId!, {
      teamId: workspace.teamId ?? undefined,
    });
    expect(ctx.penpotMgmt.workspace).toEqual(workspace);
  });

  test('management methods reject invalid identifiers', async (ctx) => {
    const mgmt = ctx.penpotMgmt;
    await expectReject(mgmt.openFile('not-a-uuid'), 'file UUID');
    await expectReject(mgmt.getFile('not-a-uuid'), 'file UUID');
    await expectReject(
      mgmt.listProjects({ teamId: 'not-a-uuid' }),
      'team UUID',
    );
  });

  describe.skipIfMocked('Discovery', () => {
    test('getFile loads the open file', async (ctx) => {
      const workspace = ctx.penpotMgmt.workspace;
      const file = await ctx.penpotMgmt.getFile(workspace.fileId!);

      expect(file.id).toBe(workspace.fileId);
      expect(file.name).toBe(workspace.fileName);
      expect(file.teamId).toBe(workspace.teamId);
      expect(file.shared).toBe(false);
      expect(Number.isNaN(Date.parse(file.modifiedAt))).toBe(false);

      const parent = await listedProject(ctx, file.projectId);
      expect(parent).toBeDefined();
      expect((await listedFile(parent!, file.id))?.name).toBe(file.name);
    });

    test('getFile loads a file that is not open', async (ctx) => {
      const created = await temporaryFile(ctx, 'Loaded by id');
      const file = await ctx.penpotMgmt.getFile(created.id);

      expect(file.name).toBe('Loaded by id');
      expect(file.projectId).toBe(created.projectId);
      expect(file.teamId).toBe(created.teamId);
    });
  });

  describe.skipIfMocked('Teams', () => {
    test('listTeams includes the current team', async (ctx) => {
      const team = await currentTeam(ctx);
      expect(typeof team.name).toBe('string');
      expect(typeof team.isDefault).toBe('boolean');
    });

    test('team and root list the same projects', async (ctx) => {
      const team = await currentTeam(ctx);
      const fromTeam = (await team.listProjects()).map((p) => p.id).sort();
      const fromRoot = (await ctx.penpotMgmt.listProjects())
        .map((p) => p.id)
        .sort();

      expect(fromTeam).toEqual(fromRoot);
      expect(
        (await ctx.penpotMgmt.listProjects()).some((p) => p.isDefault),
      ).toBe(true);
    });

    test('teams do not expose deletion', async (ctx) => {
      expect('remove' in (await currentTeam(ctx))).toBe(false);
    });

    test('assigning name renames the team', async (ctx) => {
      const team = await currentTeam(ctx);
      const original = team.name;
      const renamed = `${original} (renamed)`;

      try {
        team.name = renamed;
        expect(team.name).toBe(renamed);
        await waitForAsync(
          async () => (await currentTeam(ctx)).name === renamed,
        );
        expect((await currentTeam(ctx)).name).toBe(renamed);
      } finally {
        team.name = original;
        await waitForAsync(
          async () => (await currentTeam(ctx)).name === original,
        );
      }
    });
  });

  describe.skipIfMocked('Projects', () => {
    test('createProject and createFile return objects', async (ctx) => {
      const home = ctx.penpotMgmt.workspace;
      const team = await currentTeam(ctx);
      const name = temporaryName('created');
      const created = await team.createProject({ name: `  ${name}  ` });

      expect(created.name).toBe(name);
      expect(created.teamId).toBe(team.id);
      expect(created.isDefault).toBe(false);
      expect(created.pinned).toBe(false);
      expect(created.fileCount).toBe(0);

      const file = await created.createFile({ name: '  Login  ' });
      expect(file.name).toBe('Login');
      expect(file.projectId).toBe(created.id);
      expect(file.teamId).toBe(team.id);
      expect(file.shared).toBe(false);
      expect(created.fileCount).toBe(1);
      expect((await created.listFiles()).map((f) => f.id)).toEqual([file.id]);
      // Creating does not navigate.
      expect(ctx.penpotMgmt.workspace).toEqual(home);
    });

    test('assigning name and pinned updates the project', async (ctx) => {
      const team = await currentTeam(ctx);
      const created = await team.createProject({
        name: temporaryName('to rename'),
      });
      const renamed = temporaryName('renamed');

      created.name = renamed;
      created.pinned = true;
      expect(created.name).toBe(renamed);
      expect(created.pinned).toBe(true);

      await waitForAsync(async () => {
        const listed = await listedProject(ctx, created.id);
        return listed?.name === renamed && listed.pinned;
      });
      const listed = await listedProject(ctx, created.id);
      expect(listed?.name).toBe(renamed);
      expect(listed?.pinned).toBe(true);
    });

    test('duplicate copies a project with its files', async (ctx) => {
      const team = await currentTeam(ctx);
      const original = await team.createProject({
        name: temporaryName('original'),
      });
      await original.createFile({ name: 'Copied' });
      const name = temporaryName('copy');

      const copy = await original.duplicate({ name });
      expect(copy.id).not.toBe(original.id);
      expect(copy.name).toBe(name);
      expect((await copy.listFiles()).map((f) => f.name)).toEqual(['Copied']);
    });

    test('remove deletes a project', async (ctx) => {
      const team = await currentTeam(ctx);
      const created = await team.createProject({
        name: temporaryName('removed'),
      });

      await created.remove();
      expect(await listedProject(ctx, created.id)).toBeUndefined();
    });

    test('moveTo moves a project to another team', async (ctx) => {
      const team = await currentTeam(ctx);
      const target = await ctx.penpotMgmt.createTeam({
        name: temporaryName('team'),
      });

      try {
        const moved = await team.createProject({
          name: temporaryName('moved'),
        });
        await moved.moveTo(target);

        expect(moved.teamId).toBe(target.id);
        expect((await target.listProjects()).map((p) => p.id)).toContain(
          moved.id,
        );
        expect(await listedProject(ctx, moved.id)).toBeUndefined();
      } finally {
        await target.remove();
      }
    });
  });

  describe.skipIfMocked('Files', () => {
    test('assigning name renames a file', async (ctx) => {
      const file = await temporaryFile(ctx, 'To rename');
      const parent = await temporaryProject(ctx);

      file.name = '  Renamed  ';
      expect(file.name).toBe('Renamed');
      await waitForAsync(
        async () => (await listedFile(parent, file.id))?.name === 'Renamed',
      );
      expect((await listedFile(parent, file.id))?.name).toBe('Renamed');
    });

    test('duplicate copies a file into its project', async (ctx) => {
      const file = await temporaryFile(ctx, 'Original');
      const copy = await file.duplicate({ name: 'Duplicated' });

      expect(copy.id).not.toBe(file.id);
      expect(copy.name).toBe('Duplicated');
      expect(copy.projectId).toBe(file.projectId);
      expect(
        (await listedFile(await temporaryProject(ctx), copy.id))?.name,
      ).toBe('Duplicated');
    });

    test('moveTo moves a file to another project', async (ctx) => {
      const file = await temporaryFile(ctx, 'Moved');
      const team = await currentTeam(ctx);
      const target = await team.createProject({
        name: temporaryName('target'),
      });

      await file.moveTo(target);
      expect(file.projectId).toBe(target.id);
      expect(await listedFile(target, file.id)).toBeDefined();
      expect(
        await listedFile(await temporaryProject(ctx), file.id),
      ).toBeUndefined();
    });

    test('remove deletes a file', async (ctx) => {
      const file = await temporaryFile(ctx, 'Removed');

      await file.remove();
      expect(
        await listedFile(await temporaryProject(ctx), file.id),
      ).toBeUndefined();
    });

    test('renaming the open file updates the workspace', async (ctx) => {
      const workspace = ctx.penpotMgmt.workspace;
      const file = await ctx.penpotMgmt.getFile(workspace.fileId!);
      const original = file.name;

      try {
        file.name = `${original} (renamed)`;
        await waitFor(
          () => ctx.penpotMgmt.workspace.fileName === `${original} (renamed)`,
        );
        expect(ctx.penpotMgmt.workspace.fileName).toBe(`${original} (renamed)`);
      } finally {
        file.name = original;
      }
    });
  });

  describe.skipIfMocked('Navigation', () => {
    test('open switches to another file and back', async (ctx) => {
      const home = ctx.penpotMgmt.workspace;
      const other = await temporaryFile(ctx, 'Navigation');

      await other.open();
      expect(ctx.penpotMgmt.workspace).toEqual({
        status: 'ready',
        fileId: other.id,
        fileName: 'Navigation',
        teamId: other.teamId,
      });
      expect(ctx.penpot.currentFile?.id).toBe(other.id);
      expect(ctx.penpot.currentPage).not.toBeNull();

      await ctx.penpotMgmt.openFile(home.fileId!, {
        teamId: home.teamId ?? undefined,
      });
      expect(ctx.penpotMgmt.workspace).toEqual(home);
      expect(ctx.penpot.currentFile?.id).toBe(home.fileId);
    });

    test('file events fire when switching files', async (ctx) => {
      const home = ctx.penpotMgmt.workspace;
      const other = await temporaryFile(ctx, 'Events');

      const workspaces: string[] = [];
      const files: string[] = [];
      const finished: string[] = [];
      const workspaceListener = ctx.penpotMgmt.on('workspacechange', (w) => {
        workspaces.push(`${w.status}:${w.fileId}`);
      });
      const fileListener = ctx.penpot.on('filechange', (file) => {
        files.push(file.id);
      });
      const finishListener = ctx.penpot.on('finish', (fileId) => {
        finished.push(fileId);
      });

      try {
        await inFile(ctx, other, () => undefined);
        await waitFor(() => files.includes(home.fileId!));
      } finally {
        ctx.penpotMgmt.off(workspaceListener);
        ctx.penpot.off(fileListener);
        ctx.penpot.off(finishListener);
      }

      expect(workspaces).toContain(`loading:${other.id}`);
      expect(workspaces).toContain(`ready:${other.id}`);
      expect(workspaces).toContain(`ready:${home.fileId}`);
      expect(files).toContain(other.id);
      expect(files).toContain(home.fileId);
      expect(finished).toContain(home.fileId);
      expect(finished).toContain(other.id);
    });

    test('off stops workspacechange notifications', async (ctx) => {
      const other = await temporaryFile(ctx, 'Unsubscribed');
      let count = 0;
      const listener = ctx.penpotMgmt.on('workspacechange', () => {
        count += 1;
      });
      ctx.penpotMgmt.off(listener);

      await inFile(ctx, other, () => undefined);
      expect(count).toBe(0);
    });

    test('changes made in another file persist', async (ctx) => {
      const other = await temporaryFile(ctx, 'Persistence');
      let shapeId = '';

      await inFile(ctx, other, () => {
        const rect = ctx.penpot.createRectangle();
        rect.name = 'remote-shape';
        shapeId = rect.id;
      });

      await inFile(ctx, other, () => {
        const shape = ctx.penpot.currentPage?.getShapeById(shapeId);
        expect(shape?.name).toBe('remote-shape');
      });
    });
  });

  describe.skipIfMocked('Shared libraries', () => {
    test('assigning shared publishes and unpublishes a library', async (ctx) => {
      const file = await temporaryFile(ctx, 'Publishing');
      const isListed = async () =>
        (await ctx.penpot.library.availableLibraries()).some(
          (library) => library.id === file.id,
        );

      file.shared = true;
      expect(file.shared).toBe(true);
      await waitForAsync(isListed);
      expect(await isListed()).toBe(true);
      expect(
        (await listedFile(await temporaryProject(ctx), file.id))?.shared,
      ).toBe(true);

      file.shared = false;
      await waitForAsync(async () => !(await isListed()));
      expect(await isListed()).toBe(false);
    });

    test('availableLibraries summarises a shared library', async (ctx) => {
      const library = await sharedLibrary(ctx);
      const summary = (await ctx.penpot.library.availableLibraries()).find(
        (item) => item.id === library.file.id,
      );

      expect(summary).toBeDefined();
      expect(summary?.name).toBe('Brand library');
      expect(summary?.numColors).toBe(1);
      expect(summary?.numTypographies).toBe(1);
      expect(summary?.numComponents).toBe(1);
    });

    test('connectLibrary links a shared library to the file', async (ctx) => {
      const library = await sharedLibrary(ctx);
      const connected = await ctx.penpot.library.connectLibrary(
        library.file.id,
      );

      expect(connected.id).toBe(library.file.id);
      expect(connected.name).toBe('Brand library');
      expect(connected.colors.map((c) => c.name)).toContain(library.colorName);
      expect(connected.typographies.map((t) => t.name)).toContain(
        library.typographyName,
      );
      expect(connected.components.map((c) => c.name)).toContain(
        library.componentName,
      );
      expect(
        ctx.penpot.library.connected.some((l) => l.id === library.file.id),
      ).toBe(true);
    });

    test('connected library colors fill shapes by reference', async (ctx) => {
      const library = await sharedLibrary(ctx);
      const connected = await ctx.penpot.library.connectLibrary(
        library.file.id,
      );
      const color = connected.colors.find((c) => c.name === library.colorName);
      expect(color).toBeDefined();

      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      rect.fills = [color!.asFill()];

      expect(rect.fills[0]?.fillColor).toBe('#112233');
      expect(rect.fills[0]?.fillColorRefId).toBe(color!.id);
      expect(rect.fills[0]?.fillColorRefFile).toBe(library.file.id);
    });

    test('connected library components create linked instances', async (ctx) => {
      const library = await sharedLibrary(ctx);
      const connected = await ctx.penpot.library.connectLibrary(
        library.file.id,
      );
      const component = connected.components.find(
        (c) => c.name === library.componentName,
      );
      expect(component).toBeDefined();

      const instance = component!.instance();
      ctx.board.appendChild(instance);

      expect(instance.isComponentInstance()).toBe(true);
      expect(instance.component()?.id).toBe(component!.id);
      expect(instance.component()?.libraryId).toBe(library.file.id);
    });

    test('connected libraries reject plugin data writes', async (ctx) => {
      const library = await sharedLibrary(ctx);
      const connected = await ctx.penpot.library.connectLibrary(
        library.file.id,
      );

      expect(() => connected.setPluginData('key', 'value')).toThrow();
      expect(() =>
        connected.setSharedPluginData('ns', 'key', 'value'),
      ).toThrow();
    });
  });
});
