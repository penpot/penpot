import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

// Viewport and guides (ruler guides + board guides).

describe('Viewport', () => {
  test('zoom is readable and writable', (ctx) => {
    const vp = ctx.penpot.viewport;
    expect(typeof vp.zoom).toBe('number');
    vp.zoom = 2;
    expect(vp.zoom).toBeCloseTo(2, 1);
    vp.zoomReset();
  });

  test('center is readable and writable', (ctx) => {
    const vp = ctx.penpot.viewport;
    vp.center = { x: 100, y: 200 };
    expect(vp.center.x).toBeCloseTo(100, 0);
    expect(vp.center.y).toBeCloseTo(200, 0);
  });

  test('bounds are readable', (ctx) => {
    const vp = ctx.penpot.viewport;
    expect(typeof vp.bounds.width).toBe('number');
    expect(typeof vp.bounds.height).toBe('number');
  });

  test('zoom helpers run without error', (ctx) => {
    const vp = ctx.penpot.viewport;
    vp.zoomToFitAll();
    vp.zoomIntoView([ctx.board]);
    vp.zoomReset();
  });
});

describe('Ruler guides', () => {
  test('board addRulerGuide returns a guide', (ctx) => {
    const guide = ctx.board.addRulerGuide('vertical', 50);
    expect(guide.orientation).toBe('vertical');
    // A board-attached ruler guide exposes its board.
    void guide.board;
    guide.remove();
  });

  test('board ruler guide can be reassigned to another board', (ctx) => {
    const guide = ctx.board.addRulerGuide('vertical', 50);
    const other = ctx.penpot.createBoard();
    ctx.board.appendChild(other);
    guide.board = other;
    expect(guide.board && guide.board.id).toBe(other.id);
    guide.remove();
  });

  test('board ruler guide position round-trips', (ctx) => {
    const guide = ctx.board.addRulerGuide('vertical', 50);
    guide.position = 60;
    expect(guide.position).toBeCloseTo(60, 0);
    guide.remove();
  });

  test('board lists its ruler guides', (ctx) => {
    const guide = ctx.board.addRulerGuide('horizontal', 30);
    expect(ctx.board.rulerGuides.length).toBeGreaterThan(0);
    guide.remove();
  });

  test('board removeRulerGuide removes a guide', (ctx) => {
    const guide = ctx.board.addRulerGuide('vertical', 50);
    ctx.board.removeRulerGuide(guide);
    expect(ctx.board.rulerGuides.length).toBe(0);
  });

  test('page ruler guides can be added and removed', (ctx) => {
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      const guide = page.addRulerGuide('horizontal', 120);
      expect(guide.orientation).toBe('horizontal');
      expect(page.rulerGuides.length).toBeGreaterThan(0);
      page.removeRulerGuide(guide);
    }
  });
});

describe('Board guides', () => {
  test('column, row and square guides round-trip', (ctx) => {
    ctx.board.guides = [
      {
        type: 'column',
        display: true,
        params: {
          color: { color: '#ff0000', opacity: 1 },
          type: 'stretch',
          size: 12,
          gutter: 8,
        },
      },
      {
        type: 'row',
        display: true,
        params: {
          color: { color: '#00ff00', opacity: 1 },
          type: 'stretch',
          size: 12,
          gutter: 8,
        },
      },
      {
        type: 'square',
        display: true,
        params: { color: { color: '#0000ff', opacity: 1 }, size: 16 },
      },
    ];

    const guides = ctx.board.guides;
    expect(guides).toHaveLength(3);
    expect(guides[0].type).toBe('column');
    expect(guides[1].type).toBe('row');
    expect(guides[2].type).toBe('square');
    expect(guides[0].display).toBe(true);
    expect(guides[0].params.color.color).toBe('#ff0000');

    // Read every guide's display + params fields so the per-type guide and
    // params getters are all exercised.
    for (const g of guides) {
      void g.display;
      if (g.type === 'column' || g.type === 'row') {
        void g.params.color;
        void g.params.type;
        void g.params.size;
        void g.params.gutter;
        void g.params.margin;
        void g.params.itemLength;
      } else if (g.type === 'square') {
        void g.params.color;
        void g.params.size;
      }
    }
  });
});
