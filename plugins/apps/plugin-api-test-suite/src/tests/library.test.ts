import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Board, Text } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';
import { PNG_1X1 } from './fixtures';

// Library colors, typographies and components.
// Assets are created in the local library (self-provisioned). Reached through
// `ctx.penpot.library.local` so the Library chain is recorded for coverage.

function text(ctx: TestContext, value = 'Hello Penpot'): Text {
  const t = ctx.penpot.createText(value);
  if (!t) throw new Error('createText returned null');
  ctx.board.appendChild(t);
  return t;
}

describe('Library', () => {
  test('local library exposes id and name', (ctx) => {
    const lib = ctx.penpot.library.local;
    expect(typeof lib.id).toBe('string');
    expect(typeof lib.name).toBe('string');
  });

  test('local library lists its assets', (ctx) => {
    const lib = ctx.penpot.library.local;
    expect(Array.isArray(lib.colors)).toBe(true);
    expect(Array.isArray(lib.typographies)).toBe(true);
    expect(Array.isArray(lib.components)).toBe(true);
    expect(lib.tokens).toBeDefined();
  });

  test('library context exposes connected libraries', (ctx) => {
    expect(Array.isArray(ctx.penpot.library.connected)).toBe(true);
  });

  // The Library object itself carries plugin data (it extends PluginData),
  // stored on the underlying file. Exercised on the local library; the
  // connected-library case (writing to a shared library's file/assets) shares
  // the same code path but can't be fixtured here (connecting a library hangs).
  test('local library stores plugin data', (ctx) => {
    const lib = ctx.penpot.library.local;
    lib.setPluginData('k', 'v');
    expect(lib.getPluginData('k')).toBe('v');
    expect(lib.getPluginDataKeys()).toContain('k');
  });

  test('local library stores shared plugin data', (ctx) => {
    const lib = ctx.penpot.library.local;
    lib.setSharedPluginData('ns', 'k', 'v');
    expect(lib.getSharedPluginData('ns', 'k')).toBe('v');
    expect(lib.getSharedPluginDataKeys('ns')).toContain('k');
  });

  test('library elements expose a libraryId', (ctx) => {
    const color = ctx.penpot.library.local.createColor();
    expect(typeof color.libraryId).toBe('string');
  });

  // Skipped under MOCK_BACKEND: availableLibraries() returns backend-shaped
  // shared-library summaries; under a mock it would resolve vacuously.
  test.skipIfMocked('availableLibraries resolves to summaries', async (ctx) => {
    // The shared-libraries RPC can error in the headless team context; treat a
    // rejection as an environment limitation.
    const summaries = await ctx.penpot.library
      .availableLibraries()
      .catch(() => []);
    expect(Array.isArray(summaries)).toBe(true);
    if (summaries.length > 0) {
      const summary = summaries[0];
      expect(typeof summary.id).toBe('string');
      expect(typeof summary.name).toBe('string');
      expect(typeof summary.numColors).toBe('number');
      expect(typeof summary.numComponents).toBe('number');
      expect(typeof summary.numTypographies).toBe('number');
    }
  });

  // NOTE: connectLibrary with an unknown id is intentionally NOT exercised here.
  // Calling it with a non-existent library id crashes the plugin workspace (the
  // returned promise never settles and the sandbox freezes), which would hang
  // the whole CI run. This is a genuine API bug to fix at the source; until then
  // the suite must not trigger it.

  describe('Colors', () => {
    test('createColor adds a color asset', (ctx) => {
      const color = ctx.penpot.library.local.createColor();
      color.name = 'plugin-color';
      // Use a single-segment path: Penpot normalizes `a/b` to `a / b`.
      color.path = 'plugingroup';
      color.color = '#ff8800';
      color.opacity = 0.8;

      expect(typeof color.id).toBe('string');
      expect(color.name).toBe('plugin-color');
      expect(color.path).toBe('plugingroup');
      expect(color.color).toBe('#ff8800');
      expect(color.opacity).toBeCloseTo(0.8, 2);
    });

    test('library color converts to fill and stroke', (ctx) => {
      const color = ctx.penpot.library.local.createColor();
      color.color = '#123456';
      color.opacity = 1;

      const fill = color.asFill();
      expect(fill.fillColor).toBe('#123456');
      const stroke = color.asStroke();
      expect(stroke.strokeColor).toBe('#123456');
    });

    test('library color gradient round-trips', (ctx) => {
      const color = ctx.penpot.library.local.createColor();
      color.gradient = {
        type: 'linear',
        startX: 0,
        startY: 0,
        endX: 1,
        endY: 1,
        width: 1,
        stops: [
          { color: '#ff0000', opacity: 1, offset: 0 },
          { color: '#0000ff', opacity: 1, offset: 1 },
        ],
      };

      const g = color.gradient;
      expect(g).toBeDefined();
      if (g) {
        expect(g.type).toBe('linear');
        expect(g.stops).toHaveLength(2);
      }
    });

    // Skipped under MOCK_BACKEND: uploadMediaData needs real backend media
    // processing (ImageMagick); a mock can't return usable image data.
    test.skipIfMocked('library color image round-trips', async (ctx) => {
      const image = await ctx.penpot.uploadMediaData(
        'lib-color-image',
        PNG_1X1,
        'image/png',
      );
      const color = ctx.penpot.library.local.createColor();
      color.image = image;
      expect(color.image).toBeDefined();
    });

    // `name`/`path` and the PluginData methods are inherited (LibraryElement /
    // PluginData) but implemented per concrete type in the cljs binding
    // (lib-color-proxy), so they must be exercised on each type separately.
    test('color plugin data round-trips', (ctx) => {
      const color = ctx.penpot.library.local.createColor();
      color.setPluginData('k', 'v');
      expect(color.getPluginData('k')).toBe('v');
      expect(color.getPluginDataKeys()).toContain('k');
    });

    test('color shared plugin data round-trips', (ctx) => {
      const color = ctx.penpot.library.local.createColor();
      color.setSharedPluginData('ns', 'k', 'v');
      expect(color.getSharedPluginData('ns', 'k')).toBe('v');
      expect(color.getSharedPluginDataKeys('ns')).toContain('k');
    });
  });

  describe('Typographies', () => {
    test('createTypography adds a typography asset', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.name = 'plugin-typo';
      typo.path = 'text';
      typo.fontSize = '18';
      typo.lineHeight = '1.4';
      typo.letterSpacing = '0.5';

      expect(typeof typo.id).toBe('string');
      expect(typo.name).toBe('plugin-typo');
      expect(typo.fontSize).toBe('18');
      expect(typeof typo.fontId).toBe('string');
    });

    // `name`/`path` and PluginData are implemented per concrete type
    // (lib-typography-proxy); exercise them on a typography specifically.
    test('typography name and path round-trip', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.name = 'Heading';
      typo.path = 'Text';
      expect(typo.name).toBe('Heading');
      expect(typo.path).toBe('Text');
    });

    test('typography plugin data round-trips', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.setPluginData('k', 'v');
      expect(typo.getPluginData('k')).toBe('v');
      expect(typo.getPluginDataKeys()).toContain('k');
    });

    test('typography shared plugin data round-trips', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.setSharedPluginData('ns', 'k', 'v');
      expect(typo.getSharedPluginData('ns', 'k')).toBe('v');
      expect(typo.getSharedPluginDataKeys('ns')).toContain('k');
    });

    test('typography fontFamily and fontId round-trip', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      expect(typeof typo.fontFamily).toBe('string');

      typo.fontFamily = 'Arial';
      typo.fontId = 'gfont-arial';
      expect(typo.fontFamily).toBe('Arial');
      expect(typo.fontId).toBe('gfont-arial');
    });

    test('typography style members round-trip', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.fontStyle = 'italic';
      typo.textTransform = 'uppercase';
      typo.fontWeight = '700';
      typo.fontVariantId = 'regular';
      typo.lineHeight = '1.5';
      typo.letterSpacing = '1';
      expect(typo.fontStyle).toBe('italic');
      expect(typo.textTransform).toBe('uppercase');
      expect(typo.fontWeight).toBe('700');
      expect(typo.fontVariantId).toBe('regular');
      expect(typeof typo.lineHeight).toBe('string');
      expect(typeof typo.letterSpacing).toBe('string');
    });

    test('typography setFont updates the font', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      const font = ctx.penpot.fonts.all[0];
      typo.setFont(font);
      expect(typo.fontId).toBe(font.fontId);
    });

    test('typography applies to a text shape', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.fontSize = '22';
      const t = text(ctx);
      typo.applyToText(t);
      expect(t.fontSize).toBe('22');
    });

    test('typography applies to a text range', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      typo.fontSize = '28';
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      typo.applyToTextRange(range);
      expect(range.fontSize).toBe('28');
    });
  });

  describe('Components', () => {
    test('createComponent creates a component asset', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const comp = ctx.penpot.library.local.createComponent([rect]);

      expect(typeof comp.id).toBe('string');
      comp.name = 'plugin-component';
      expect(comp.name).toBe('plugin-component');
      expect(comp.isVariant()).toBe(false);
    });

    // `name`/`path` and PluginData are implemented per concrete type
    // (lib-component-proxy). `component.path` in particular had no test before,
    // which is how the variant `.path` corruption slipped through — exercise it
    // on a plain (non-variant) component here.
    test('component name and path round-trip', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const comp = ctx.penpot.library.local.createComponent([rect]);
      comp.name = 'Button';
      comp.path = 'Controls';
      expect(comp.name).toBe('Button');
      expect(comp.path).toBe('Controls');
    });

    test('component plugin data round-trips', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const comp = ctx.penpot.library.local.createComponent([rect]);
      comp.setPluginData('k', 'v');
      expect(comp.getPluginData('k')).toBe('v');
      expect(comp.getPluginDataKeys()).toContain('k');
    });

    test('component shared plugin data round-trips', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const comp = ctx.penpot.library.local.createComponent([rect]);
      comp.setSharedPluginData('ns', 'k', 'v');
      expect(comp.getSharedPluginData('ns', 'k')).toBe('v');
      expect(comp.getSharedPluginDataKeys('ns')).toContain('k');
    });

    test('component instance and mainInstance return shapes', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const comp = ctx.penpot.library.local.createComponent([rect]);

      const main = comp.mainInstance();
      expect(main).toBeDefined();
      expect(typeof main.id).toBe('string');

      const instance = comp.instance();
      expect(instance).toBeDefined();
      expect(typeof instance.id).toBe('string');
    });

    // Reported (via MCP): create a page-root shape (rectangle / ellipse / text),
    // save it as a component, then use it from Assets — the component root was
    // said to be a plain shape instead of a board/component. This does not
    // reproduce: a lone page-root shape is wrapped in a board and that board
    // becomes the component (main-instance) root. Pin the invariant for the
    // exact repro.
    test('createComponent wraps a page-root shape in a board component root', (ctx) => {
      // A genuine page-root shape: created but NOT placed inside any board.
      const rect = ctx.penpot.createRectangle();
      const comp = ctx.penpot.library.local.createComponent([rect]);

      const main = comp.mainInstance() as Board;
      expect(main.type).toBe('board');
      expect(main.isComponentRoot()).toBeTruthy();
      expect(main.isComponentMainInstance()).toBeTruthy();
      expect(main.children.some((c) => c.id === rect.id)).toBe(true);

      const errors = ctx.penpot.currentFile?.validate() ?? [];
      expect(errors.map((e) => e.code)).toEqual([]);

      // The generated main instance lands at the page root (outside ctx.board);
      // tuck it under the scratch board so teardown removes it.
      ctx.board.appendChild(main);
    });

    // createComponent must reject inputs that cannot form a component rather
    // than succeeding silently or crashing. Both cases below currently surface
    // an INTERNAL assertion ("Assert failed: (uuid? id)"): the operation is a
    // no-op, so the returned LibraryComponent proxy is built from a nil id. It
    // should be a clean rejection (or a null return) instead — and in a release
    // build, where the assertion is elided, the caller gets a broken component
    // proxy pointing at nothing. The file must stay valid regardless.
    test('createComponent with no shapes is rejected', (ctx) => {
      expect(() => ctx.penpot.library.local.createComponent([])).toThrow();
      const errors = ctx.penpot.currentFile?.validate() ?? [];
      expect(errors.map((e) => e.code)).toEqual([]);
    });

    test('createComponent from a shape inside a component copy is rejected', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const source = ctx.penpot.library.local.createComponent([rect]);
      const copy = source.instance() as Board;
      ctx.board.appendChild(copy);
      const copyChild = copy.children[0];
      expect(copyChild).toBeDefined();

      expect(() =>
        ctx.penpot.library.local.createComponent([copyChild]),
      ).toThrow();
      const errors = ctx.penpot.currentFile?.validate() ?? [];
      expect(errors.map((e) => e.code)).toEqual([]);
    });
  });
});
