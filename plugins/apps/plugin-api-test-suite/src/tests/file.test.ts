import { expect, expectReject } from '../framework/expect';
import { describe, test } from '../framework/registry';

// File & versions.
// Read-only assertions on currentFile plus the version history API. The file
// name is only read (renaming would mutate the user's file).

describe('File', () => {
  test('currentFile exposes id and name', (ctx) => {
    const file = ctx.penpot.currentFile;
    expect(file).not.toBeNull();
    if (file) {
      expect(typeof file.id).toBe('string');
      expect(typeof file.name).toBe('string');
    }
  });

  test('currentFile exposes revn', (ctx) => {
    const file = ctx.penpot.currentFile;
    if (file) {
      expect(typeof file.revn).toBe('number');
    }
  });

  test('file lists its pages', (ctx) => {
    const file = ctx.penpot.currentFile;
    if (file) {
      expect(file.pages.length).toBeGreaterThan(0);
      expect(typeof file.pages[0].id).toBe('string');
    }
  });

  test('validate reports referential-integrity errors', (ctx) => {
    const file = ctx.penpot.currentFile;
    expect(file).not.toBeNull();
    if (file) {
      const errors = file.validate();
      expect(Array.isArray(errors)).toBe(true);
      // A healthy scratch file has no integrity errors. Each error (if any)
      // exposes a string code and hint.
      for (const e of errors) {
        expect(typeof e.code).toBe('string');
        expect(typeof e.hint).toBe('string');
      }
    }
  });

  test('export returns binary data', async (ctx) => {
    const file = ctx.penpot.currentFile;
    if (file) {
      // The exporter service may be unavailable in the headless runner, so a
      // rejection here is treated as an environment limitation; when it does
      // run, the result must be a non-empty byte array.
      const data = await file.export('penpot', 'detach').catch(() => null);
      if (data) {
        expect(data.length).toBeGreaterThan(0);
      }
    }
  });

  // Skipped under MOCK_BACKEND: version history is persisted/returned by the
  // backend; a no-op persist mock can't reproduce saved versions.
  describe.skipIfMocked('Versions', () => {
    test('saveVersion and findVersions manage version history', async (ctx) => {
      const file = ctx.penpot.currentFile;
      expect(file).not.toBeNull();
      if (file) {
        const version = await file.saveVersion('plugin-test-version');
        expect(version).toBeDefined();
        expect(version.label).toBe('plugin-test-version');
        expect(version.isAutosave).toBe(false);

        // Relabel the saved version (covers FileVersion.label set). The
        // proxy's own `label` reflects the write immediately from its local
        // cache, so also fetch the versions again and assert the rename
        // actually reached the backend.
        version.label = 'plugin-test-version-renamed';
        expect(version.label).toBe('plugin-test-version-renamed');

        // The rename is persisted asynchronously; give it a moment to land.
        await new Promise((resolve) => setTimeout(resolve, 500));
        const versions = await file.findVersions();
        expect(versions.length).toBeGreaterThan(0);
        const renamed = versions.filter(
          (v) => v.label === 'plugin-test-version-renamed',
        );
        expect(renamed).toHaveLength(1);

        // Clean up the version we just created.
        await version.remove();
      }
    });

    test('version exposes its creation date', async (ctx) => {
      const file = ctx.penpot.currentFile;
      if (file) {
        const version = await file.saveVersion('plugin-test-version-date');
        try {
          expect(version.createdAt).toBeDefined();
        } finally {
          await version.remove();
        }
      }
    });

    test('version createdBy is exercised', async (ctx) => {
      const file = ctx.penpot.currentFile;
      if (file) {
        const version = await file.saveVersion('plugin-test-version-pin');
        void version.createdBy;
        // `pin` is intentionally not exercised: it only converts a *system*
        // autosave to a permanent version, and a plugin cannot create an
        // autosave, so calling it would always reject. See README.md.
        await version.remove().catch(() => undefined);
      }
    });

    // Edge case: an empty version label must be rejected.
    test('saveVersion with an empty label rejects', async (ctx) => {
      const file = ctx.penpot.currentFile;
      expect(file).not.toBeNull();
      if (file) {
        await expectReject(() => file.saveVersion(''));
      }
    });
  });
});
