import { expect, expectReject } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Board, Font, Shape, Text } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// waitForLayoutUpdate (context-level and per-shape).
// The promise must resolve only once the pending reflow work has been applied,
// so every test asserts the *observable geometry* after awaiting: if the
// promise settles too early the read-back is stale and the test goes red.
// Reflow work is scheduled by three pipelines: flex/grid layout updates
// (buffered ~100ms), wasm text resizes (debounced), and font-change
// re-measurement. The probes below cover each one, including cascaded reflows
// (a child resize that must also re-hug its ancestors) and waits racing the
// font-loading retry loop.

function flexBoard(ctx: TestContext): Board {
  const b = ctx.penpot.createBoard();
  ctx.board.appendChild(b);
  return b;
}

/** Creates a horizontal flex board with two 50x50 rects and a 10px gap. */
function flexRow(ctx: TestContext): {
  board: Board;
  rects: [Shape, Shape];
} {
  const b = flexBoard(ctx);
  const flex = b.addFlexLayout();
  flex.dir = 'row';
  flex.columnGap = 10;
  const r1 = ctx.penpot.createRectangle();
  r1.resize(50, 50);
  flex.appendChild(r1);
  const r2 = ctx.penpot.createRectangle();
  r2.resize(50, 50);
  flex.appendChild(r2);
  return { board: b, rects: [r1, r2] };
}

/** Orders the two rects of a flex row by their current x position. */
function byX(rects: [Shape, Shape]): { left: Shape; right: Shape } {
  const [a, b] = rects;
  return a.x <= b.x ? { left: a, right: b } : { left: b, right: a };
}

/**
 * Picks a font from the end of the catalog that differs from the text's
 * current font, so assigning it triggers a real (not-yet-loaded) font fetch.
 * `offset` lets consecutive tests pick distinct fonts: fonts loaded by an
 * earlier test stay cached for the whole run and would drain the pending mark
 * near-instantly if reused.
 */
function unloadedFont(ctx: TestContext, t: Text, offset: number): Font {
  const all = ctx.penpot.fonts.all;
  for (let i = all.length - 1 - offset; i >= 0; i--) {
    const f = all[i];
    if (f.fontId !== t.fontId && f.variants.length > 0) return f;
  }
  throw new Error('no alternative font available');
}

function autoWidthText(ctx: TestContext, value: string): Text {
  const t = ctx.penpot.createText(value);
  if (!t) throw new Error('createText returned null');
  ctx.board.appendChild(t);
  t.growType = 'auto-width';
  t.fontSize = '36';
  return t;
}

describe('WaitForLayoutUpdate', () => {
  describe('Basics', () => {
    test('context method returns a promise that resolves', async (ctx) => {
      const p = ctx.penpot.waitForLayoutUpdate();
      expect(typeof p.then).toBe('function');
      const value = await p;
      expect(value).toBeUndefined();
    });

    // For the next two, resolving (not rejecting, not hitting the runner
    // timeout) is itself the behaviour under test, so they have no expects.

    test('a timeout wait resolves via the fast path when nothing is pending', async (ctx) => {
      // Drain any residual work first; once the untimed wait resolves the
      // pending map is empty, so the settle signal replays synchronously and
      // must win the race against the deadline: even a 1ms timeout has to
      // resolve, never reject.
      await ctx.penpot.waitForLayoutUpdate();
      await ctx.penpot.waitForLayoutUpdate(1);
    });

    test('shape method resolves for a shape with no pending work', async (ctx) => {
      const r = ctx.penpot.createRectangle();
      ctx.board.appendChild(r);
      await r.waitForLayoutUpdate();
    });

    test('consecutive waits each track their own pending work', async (ctx) => {
      // Each call opens a fresh subscription to the pending map, so a wait must
      // settle on the work scheduled right before it and never carry state from
      // an earlier, already-resolved wait. Two successive resizes to different
      // sizes prove it: the second wait has to reflect the second reflow.
      const { rects } = flexRow(ctx);
      await ctx.penpot.waitForLayoutUpdate();

      const { left, right } = byX(rects);
      left.resize(120, 50);
      await ctx.penpot.waitForLayoutUpdate();
      expect(right.x - left.x).toBeCloseTo(130, 0);

      left.resize(70, 50);
      await ctx.penpot.waitForLayoutUpdate();
      expect(right.x - left.x).toBeCloseTo(80, 0);
    });
  });

  describe('Flex reflow', () => {
    test('sibling is repositioned after awaiting a child resize', async (ctx) => {
      const { rects } = flexRow(ctx);
      await ctx.penpot.waitForLayoutUpdate();

      const { left, right } = byX(rects);
      left.resize(120, 50);
      await ctx.penpot.waitForLayoutUpdate();
      expect(right.x - left.x).toBeCloseTo(130, 0);
    });

    test('board-level wait settles the reflow of its layout', async (ctx) => {
      const { board: b, rects } = flexRow(ctx);
      await b.waitForLayoutUpdate();

      const { left, right } = byX(rects);
      left.resize(120, 50);
      // The layout pipeline marks the laid-out board (the parent) as pending,
      // so the board proxy is the shape-level handle for this reflow.
      await b.waitForLayoutUpdate();
      expect(right.x - left.x).toBeCloseTo(130, 0);
    });

    test('back-to-back resizes settle to the last value', async (ctx) => {
      // Overlapping operations on the same shapes: the wait must not resolve
      // after the first one drains while the second is still in flight.
      const { rects } = flexRow(ctx);
      await ctx.penpot.waitForLayoutUpdate();

      const { left, right } = byX(rects);
      left.resize(80, 50);
      left.resize(120, 50);
      await ctx.penpot.waitForLayoutUpdate();
      expect(right.x - left.x).toBeCloseTo(130, 0);
    });

    test('grid layout reflow settles child positions', async (ctx) => {
      const b = flexBoard(ctx);
      const grid = b.addGridLayout();
      grid.addRow('fixed', 60);
      grid.addColumn('fixed', 60);
      grid.addColumn('fixed', 60);
      const r1 = ctx.penpot.createRectangle();
      r1.resize(50, 50);
      grid.appendChild(r1, 1, 1);
      const r2 = ctx.penpot.createRectangle();
      r2.resize(50, 50);
      grid.appendChild(r2, 1, 2);
      await ctx.penpot.waitForLayoutUpdate();
      // Cells are 60px wide, so the second child sits one track further right.
      expect(Math.abs(r2.x - r1.x)).toBeCloseTo(60, 0);
    });
  });

  describe('Hug sizing', () => {
    test('hug board fits its content after awaiting the initial layout', async (ctx) => {
      const { board: b } = flexRow(ctx);
      const flex = b.flex;
      if (!flex) throw new Error('expected a flex layout on the board');
      flex.horizontalSizing = 'auto';
      flex.verticalSizing = 'auto';
      await ctx.penpot.waitForLayoutUpdate();
      // 50 + 10 gap + 50, no padding.
      expect(b.width).toBeCloseTo(110, 0);
    });

    test('hug board grows after awaiting a child resize', async (ctx) => {
      const { board: b, rects } = flexRow(ctx);
      const flex = b.flex;
      if (!flex) throw new Error('expected a flex layout on the board');
      flex.horizontalSizing = 'auto';
      flex.verticalSizing = 'auto';
      await ctx.penpot.waitForLayoutUpdate();

      const { left } = byX(rects);
      left.resize(120, 50);
      await ctx.penpot.waitForLayoutUpdate();
      expect(b.width).toBeCloseTo(180, 0);
    });

    test('nested hug boards both grow after awaiting a child resize', async (ctx) => {
      // Cascade probe: the child resize reflows the inner board, whose new
      // size must then reflow the outer board. The wait has to cover the whole
      // chain, not just the first reflow round.
      const outer = flexBoard(ctx);
      const outerFlex = outer.addFlexLayout();
      outerFlex.dir = 'row';
      outerFlex.horizontalSizing = 'auto';
      outerFlex.verticalSizing = 'auto';

      const inner = ctx.penpot.createBoard();
      outerFlex.appendChild(inner);
      const innerFlex = inner.addFlexLayout();
      innerFlex.dir = 'row';
      innerFlex.horizontalSizing = 'auto';
      innerFlex.verticalSizing = 'auto';

      const rect = ctx.penpot.createRectangle();
      rect.resize(50, 50);
      innerFlex.appendChild(rect);
      await ctx.penpot.waitForLayoutUpdate();
      expect(inner.width).toBeCloseTo(50, 0);
      expect(outer.width).toBeCloseTo(50, 0);

      rect.resize(200, 50);
      await ctx.penpot.waitForLayoutUpdate();
      expect(inner.width).toBeCloseTo(200, 0);
      expect(outer.width).toBeCloseTo(200, 0);
    });

    test.skipIfMocked(
      'hug board hugs an auto-width text after a characters change',
      async (ctx) => {
        // Cascade probe across pipelines: the text resize is scheduled by the
        // text pipeline and only then does the board reflow to hug it. Skipped
        // under a mocked backend: hugging a text depends on real glyph
        // measurement (no fonts are served), so the text width never grows.
        const b = flexBoard(ctx);
        const flex = b.addFlexLayout();
        flex.horizontalSizing = 'auto';
        flex.verticalSizing = 'auto';
        const t = ctx.penpot.createText('hi');
        if (!t) throw new Error('createText returned null');
        flex.appendChild(t);
        t.growType = 'auto-width';
        await ctx.penpot.waitForLayoutUpdate();
        const w0 = b.width;

        t.characters = 'a much much much longer text content than before';
        await ctx.penpot.waitForLayoutUpdate();
        expect(t.width).toBeGreaterThan(w0);
        expect(b.width).toBeCloseTo(t.width, 0);
      },
    );
  });

  // Skipped under a mocked backend: every case here asserts that a text is
  // re-measured (width changes) after a mutation, which depends on real glyph
  // measurement against fonts the backend serves. Mocked mode serves no fonts,
  // so the measured width stays put and the assertions can't hold.
  describe.skipIfMocked('Text', () => {
    test('auto-width text grows after awaiting a fontSize bump', async (ctx) => {
      const t = autoWidthText(ctx, 'The quick brown fox');
      await ctx.penpot.waitForLayoutUpdate();
      const w0 = t.width;

      t.fontSize = '72';
      await ctx.penpot.waitForLayoutUpdate();
      expect(t.width).toBeGreaterThan(w0);
    });

    test('auto-width text grows after awaiting a characters change', async (ctx) => {
      const t = autoWidthText(ctx, 'short');
      await ctx.penpot.waitForLayoutUpdate();
      const w0 = t.width;

      t.characters = 'short plus a considerably longer tail 0123456789';
      await ctx.penpot.waitForLayoutUpdate();
      expect(t.width).toBeGreaterThan(w0);
    });

    test('shape-level wait covers the text own resize', async (ctx) => {
      const t = autoWidthText(ctx, 'The quick brown fox');
      await t.waitForLayoutUpdate();
      const w0 = t.width;

      t.fontSize = '72';
      await t.waitForLayoutUpdate();
      expect(t.width).toBeGreaterThan(w0);
    });

    test('auto-width text is re-measured after awaiting a font change', async (ctx) => {
      // Probe for the font-loading window: right after a font change the new
      // font is typically not loaded yet, and the wait must cover the load +
      // re-measure, not resolve while the loader is still retrying.
      const t = autoWidthText(
        ctx,
        'The quick brown fox jumps over the lazy dog 0123456789',
      );
      await ctx.penpot.waitForLayoutUpdate();
      const w0 = t.width;

      const font = ctx.penpot.fonts.all.find(
        (f) => f.fontId !== t.fontId && f.variants.length > 0,
      );
      if (!font) throw new Error('no alternative font available');
      t.fontId = font.fontId;
      t.fontFamily = font.fontFamily;
      await ctx.penpot.waitForLayoutUpdate();
      // A different family practically never yields the same measured width
      // for a 54-char string at 36px.
      expect(Math.abs(t.width - w0)).toBeGreaterThan(0.5);
    });

    test('range font change is re-measured after awaiting', async (ctx) => {
      // Same probe through the text-range pipeline instead of the shape-level
      // font setter.
      const t = autoWidthText(
        ctx,
        'The quick brown fox jumps over the lazy dog 0123456789',
      );
      await ctx.penpot.waitForLayoutUpdate();
      const w0 = t.width;

      const font = ctx.penpot.fonts.all.find(
        (f) => f.fontId !== t.fontId && f.variants.length > 0,
      );
      if (!font) throw new Error('no alternative font available');
      const range = t.getRange(0, t.characters.length);
      range.fontFamily = font.fontFamily;
      range.fontId = font.fontId;
      await ctx.penpot.waitForLayoutUpdate();
      expect(Math.abs(t.width - w0)).toBeGreaterThan(0.5);
    });
  });

  describe('Timeout', () => {
    // Pure geometry mutations (resize & co.) are settled synchronously by the
    // renderer, so right after them nothing is pending and a short-timeout
    // wait legitimately resolves. The deterministic pending source is a font
    // change: it marks the text pending until the font is fetched and the
    // shape re-measured, which can never complete within 1ms. These tests hit
    // the real backend for the font asset, hence skipIfMocked.

    test.skipIfMocked(
      'rejects with a timeout error while a font is loading',
      async (ctx) => {
        const t = autoWidthText(ctx, 'The quick brown fox');
        await ctx.penpot.waitForLayoutUpdate();

        const font = unloadedFont(ctx, t, 0);
        t.fontId = font.fontId;
        t.fontFamily = font.fontFamily;
        await expectReject(ctx.penpot.waitForLayoutUpdate(1), /timeout/i);
      },
    );

    test.skipIfMocked(
      'shape-level wait also rejects on timeout',
      async (ctx) => {
        const t = autoWidthText(ctx, 'The quick brown fox');
        await ctx.penpot.waitForLayoutUpdate();

        const font = unloadedFont(ctx, t, 1);
        t.fontId = font.fontId;
        t.fontFamily = font.fontFamily;
        await expectReject(t.waitForLayoutUpdate(1), /timeout/i);
      },
    );

    test('resolves normally when work settles before the timeout', async (ctx) => {
      const { rects } = flexRow(ctx);
      await ctx.penpot.waitForLayoutUpdate();

      const { left, right } = byX(rects);
      left.resize(120, 50);
      await ctx.penpot.waitForLayoutUpdate(5000);
      expect(right.x - left.x).toBeCloseTo(130, 0);
    });

    test.skipIfMocked(
      'a rejected wait leaves later waits working',
      async (ctx) => {
        const t = autoWidthText(ctx, 'The quick brown fox');
        await ctx.penpot.waitForLayoutUpdate();
        const w0 = t.width;

        const font = unloadedFont(ctx, t, 2);
        t.fontId = font.fontId;
        t.fontFamily = font.fontFamily;
        await expectReject(ctx.penpot.waitForLayoutUpdate(1), /timeout/i);
        // A second wait with a generous timeout still tracks the same pending
        // work and resolves once the font lands and the text is re-measured.
        await ctx.penpot.waitForLayoutUpdate(12000);
        expect(Math.abs(t.width - w0)).toBeGreaterThan(0.5);
      },
    );

    test.skipIfMocked(
      'per-shape wait is not blocked by another shape pending work',
      async (ctx) => {
        const t = autoWidthText(ctx, 'The quick brown fox');
        const bystander = ctx.penpot.createRectangle();
        ctx.board.appendChild(bystander);
        await ctx.penpot.waitForLayoutUpdate();

        const font = unloadedFont(ctx, t, 3);
        t.fontId = font.fontId;
        t.fontFamily = font.fontFamily;
        // The text has pending font work but the bystander does not; its wait
        // must resolve on the fast path instead of waiting for the font.
        const started = Date.now();
        await bystander.waitForLayoutUpdate();
        expect(Date.now() - started).toBeLessThan(90);
      },
    );
  });
});
