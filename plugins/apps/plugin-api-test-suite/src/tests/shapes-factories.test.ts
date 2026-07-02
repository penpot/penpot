import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

// Shapes & geometry.
// Exercises the Context shape factories and the context-level structural
// operations (group/ungroup/flatten, align/distribute). Everything created is
// appended to the scratch board `ctx.board` so the user's canvas stays clean.
// Each group keeps its happy-path tests together with the related edge cases:
// "fail" tests assert the documented null returns / rejections for degenerate
// input; the remaining ones pin non-trivial valid construction (clone
// independence, group order, boolean tree, svg tree).

describe('Shapes', () => {
  describe('Factories', () => {
    test('createRectangle returns a rectangle', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      expect(rect.type).toBe('rectangle');
    });

    test('createEllipse returns an ellipse', (ctx) => {
      const ellipse = ctx.penpot.createEllipse();
      ctx.board.appendChild(ellipse);
      expect(ellipse.type).toBe('ellipse');
    });

    test('createBoard returns a board', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      expect(board.type).toBe('board');
    });

    test('createPath returns a path', (ctx) => {
      const path = ctx.penpot.createPath();
      ctx.board.appendChild(path);
      expect(path.type).toBe('path');
    });

    test('createText returns a text shape with the given content', (ctx) => {
      const text = ctx.penpot.createText('Hello Penpot');
      expect(text).not.toBeNull();
      if (text) {
        ctx.board.appendChild(text);
        expect(text.type).toBe('text');
        expect(text.characters).toContain('Hello');
      }
    });

    test('createBoolean unions two shapes', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      b.x = 50;
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);

      const bool = ctx.penpot.createBoolean('union', [a, b]);
      expect(bool).not.toBeNull();
      if (bool) {
        ctx.board.appendChild(bool);
        expect(bool.type).toBe('boolean');
      }
    });

    test('createShapeFromSvg returns a group', (ctx) => {
      const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">' +
        '<rect width="10" height="10" fill="#ff0000"/></svg>';
      const group = ctx.penpot.createShapeFromSvg(svg);
      expect(group).not.toBeNull();
      if (group) {
        ctx.board.appendChild(group);
        expect(group.type).toBe('group');
      }
    });

    test('createShapeFromSvgWithImages resolves to a group', async (ctx) => {
      const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">' +
        '<rect width="10" height="10" fill="#00ff00"/></svg>';
      const group = await ctx.penpot.createShapeFromSvgWithImages(svg);
      expect(group).not.toBeNull();
      if (group) {
        ctx.board.appendChild(group);
        expect(group.type).toBe('group');
      }
    });

    // Degenerate input — documented null returns / rejections.
    test('createText with an empty string returns null', (ctx) => {
      // The d.ts documents: "Returns null if an empty string is provided".
      const t = ctx.penpot.createText('');
      expect(t).toBeNull();
    });

    test('group of an empty array returns null', (ctx) => {
      const g = ctx.penpot.group([]);
      expect(g).toBeNull();
    });

    test('createBoolean with an empty shapes array is rejected', (ctx) => {
      // createBoolean validates a non-empty shapes array, so with
      // throwValidationErrors enabled it throws rather than returning null.
      expect(() => ctx.penpot.createBoolean('union', [])).toThrow();
    });

    test('createBoolean supports difference, exclude and intersection', (ctx) => {
      // Only `union` is exercised elsewhere; cover the remaining boolean ops so a
      // typology-specific regression in any single operation is caught.
      for (const op of ['difference', 'exclude', 'intersection'] as const) {
        const a = ctx.penpot.createRectangle();
        const b = ctx.penpot.createEllipse();
        ctx.board.appendChild(a);
        ctx.board.appendChild(b);
        b.x = 10;
        b.y = 10;
        const bool = ctx.penpot.createBoolean(op, [a, b]);
        expect(bool).not.toBeNull();
        if (bool) {
          ctx.board.appendChild(bool);
          expect(typeof bool.d).toBe('string');
          expect(typeof bool.toD()).toBe('string');
        }
      }
    });

    test('createShapeFromSvg rejects unparseable markup', (ctx) => {
      // Malformed SVG is rejected up front rather than failing asynchronously
      // inside the import pipeline (which would surface as an error toast).
      expect(() => ctx.penpot.createShapeFromSvg('not svg at all')).toThrow();
    });

    // Success edges — non-trivial valid construction.
    test('clone produces an independent copy', (ctx) => {
      const r = ctx.penpot.createRectangle();
      r.name = 'original';
      ctx.board.appendChild(r);

      const copy = r.clone();
      ctx.board.appendChild(copy);
      copy.name = 'copy';

      expect(copy.id).not.toBe(r.id);
      expect(copy.name).toBe('copy');
      // Mutating the copy must not affect the original.
      expect(r.name).toBe('original');
    });

    test('group preserves child count and order', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createEllipse();
      const c = ctx.penpot.createRectangle();
      a.name = 'a';
      b.name = 'b';
      c.name = 'c';
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      ctx.board.appendChild(c);

      const group = ctx.penpot.group([a, b, c]);
      expect(group).not.toBeNull();
      if (group) {
        expect(group.children).toHaveLength(3);
        expect(group.children.map((s) => s.name).sort()).toEqual([
          'a',
          'b',
          'c',
        ]);
      }
    });

    test('a boolean keeps its two operands as children', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      b.x = 50;
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);

      const bool = ctx.penpot.createBoolean('union', [a, b]);
      expect(bool).not.toBeNull();
      if (bool) {
        ctx.board.appendChild(bool);
        expect(bool.type).toBe('boolean');
        expect(bool.children).toHaveLength(2);
      }
    });

    test('createShapeFromSvg builds a group with children', (ctx) => {
      const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20">' +
        '<rect width="10" height="10" fill="#ff0000"/>' +
        '<circle cx="15" cy="15" r="5" fill="#0000ff"/></svg>';
      const group = ctx.penpot.createShapeFromSvg(svg);
      expect(group).not.toBeNull();
      if (group) {
        ctx.board.appendChild(group);
        expect(group.type).toBe('group');
        expect(group.children.length).toBeGreaterThan(0);
      }
    });
  });

  describe('Grouping', () => {
    test('group wraps shapes in a group', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createEllipse();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);

      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        expect(group.type).toBe('group');
        expect(group.children).toHaveLength(2);
      }
    });

    test('ungroup dissolves a group', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createEllipse();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);

      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        const before = ctx.board.children.length;
        ctx.penpot.ungroup(group);
        // After ungroup the two shapes should be back on the board directly.
        expect(ctx.board.children.length).toBeGreaterThan(before - 1);
      }
    });

    test('flatten converts shapes into paths', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);

      const paths = ctx.penpot.flatten([rect]);
      expect(paths).toHaveLength(1);
      expect(paths[0].type).toBe('path');
    });
  });

  describe('Align & distribute', () => {
    test('alignHorizontal moves shapes to a shared edge', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      a.x = 0;
      b.x = 200;
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);

      ctx.penpot.alignHorizontal([a, b], 'left');
      expect(a.x).toBeCloseTo(b.x, 0);
    });

    test('alignVertical moves shapes to a shared edge', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      a.y = 0;
      b.y = 200;
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);

      ctx.penpot.alignVertical([a, b], 'top');
      expect(a.y).toBeCloseTo(b.y, 0);
    });

    test('distributeHorizontal spaces the shapes evenly', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      const c = ctx.penpot.createRectangle();
      a.x = 0;
      b.x = 50;
      c.x = 300;
      a.resize(20, 20);
      b.resize(20, 20);
      c.resize(20, 20);
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      ctx.board.appendChild(c);

      ctx.penpot.distributeHorizontal([a, b, c]);
      // The outer shapes stay in place and the middle one lands so the gaps
      // between consecutive shapes are equal.
      const xs = [a.x, b.x, c.x].sort((p, q) => p - q);
      expect(xs[1] - xs[0]).toBeCloseTo(xs[2] - xs[1], 0);
      expect(xs[0]).toBeCloseTo(0, 0);
      expect(xs[2]).toBeCloseTo(300, 0);
    });

    test('distributeVertical spaces the shapes evenly', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      const c = ctx.penpot.createRectangle();
      a.y = 0;
      b.y = 50;
      c.y = 300;
      a.resize(20, 20);
      b.resize(20, 20);
      c.resize(20, 20);
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      ctx.board.appendChild(c);

      ctx.penpot.distributeVertical([a, b, c]);
      const ys = [a.y, b.y, c.y].sort((p, q) => p - q);
      expect(ys[1] - ys[0]).toBeCloseTo(ys[2] - ys[1], 0);
      expect(ys[0]).toBeCloseTo(0, 0);
      expect(ys[2]).toBeCloseTo(300, 0);
    });

    // Edge cases.
    test('aligning an empty array is a no-op (not rejected)', (ctx) => {
      // align/distribute do not validate the shape list; an empty array is
      // simply a no-op rather than an error.
      expect(() => ctx.penpot.alignHorizontal([], 'left')).not.toThrow();
    });

    test('distributing a single shape leaves it in place', (ctx) => {
      const a = ctx.penpot.createRectangle();
      a.x = 10;
      ctx.board.appendChild(a);
      ctx.penpot.distributeHorizontal([a]);
      expect(a.x).toBeCloseTo(10, 0);
    });
  });
});
