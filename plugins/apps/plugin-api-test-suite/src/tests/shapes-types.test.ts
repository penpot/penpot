import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Shape } from '@penpot/plugin-types';

// Shapes & geometry.
// Exercises members specific to the concrete shape types (Board, Group, Boolean,
// Path, Ellipse, SvgRaw) beyond the common `ShapeBase` surface.

/** Depth-first search for the first descendant matching `type`. */
function findByType(shape: Shape, type: string): Shape | null {
  if (shape.type === type) return shape;
  const children = 'children' in shape ? shape.children : undefined;
  if (children) {
    for (const child of children) {
      const found = findByType(child, type);
      if (found) return found;
    }
  }
  return null;
}

describe('Shapes', () => {
  describe('Board', () => {
    test('clipContent is readable and writable', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      board.clipContent = false;
      expect(board.clipContent).toBe(false);
      board.clipContent = true;
      expect(board.clipContent).toBe(true);
    });

    test('showInViewMode is readable and writable', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      board.showInViewMode = false;
      expect(board.showInViewMode).toBe(false);
    });

    test('appendChild and children reflect added shapes', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const child = ctx.penpot.createRectangle();
      board.appendChild(child);
      expect(board.children).toHaveLength(1);
      expect(board.children[0].id).toBe(child.id);
    });

    test('children setter accepts a reorder and rejects a different set', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      board.appendChild(a);
      board.appendChild(b);

      const ids = board.children.map((c) => c.id).sort();

      // `children` is writable only for *reordering*: assigning the same shapes
      // in a new order is accepted and preserves the set. (The visible order is
      // governed by the naturalChildOrdering flag, so only the set is asserted.)
      board.children = [...board.children].reverse();
      expect(board.children).toHaveLength(2);
      expect(board.children.map((c) => c.id).sort()).toEqual(ids);

      // Assigning a set that doesn't match the current children is rejected.
      expect(() => {
        board.children = [a];
      }).toThrow();
    });

    test('insertChild places a shape at a given index', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const first = ctx.penpot.createRectangle();
      const second = ctx.penpot.createRectangle();
      board.appendChild(first);
      board.insertChild(0, second);
      // Use the structural parentIndex; the children array sort direction
      // depends on the naturalChildOrdering flag.
      expect(second.parentIndex).toBe(0);
    });

    test('horizontalSizing and verticalSizing are readable and writable', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      board.horizontalSizing = 'fix';
      board.verticalSizing = 'fix';
      expect(board.horizontalSizing).toBe('fix');
      expect(board.verticalSizing).toBe('fix');
    });

    test('isVariantContainer is false for a plain board', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      expect(board.isVariantContainer()).toBe(false);
    });
  });

  describe('Group', () => {
    test('children and appendChild work on a group', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        expect(group.children).toHaveLength(2);
        const extra = ctx.penpot.createRectangle();
        group.appendChild(extra);
        expect(group.children).toHaveLength(3);
      }
    });

    test('insertChild places a shape into a group', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        const extra = ctx.penpot.createRectangle();
        group.insertChild(0, extra);
        expect(extra.parentIndex).toBe(0);
      }
    });

    test('makeMask and removeMask run without error', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        group.makeMask();
        group.removeMask();
      }
    });

    test('isMask reports whether the group is a mask', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        expect(group.isMask()).toBe(false);
        group.makeMask();
        expect(group.isMask()).toBe(true);
      }
    });
  });

  describe('Boolean', () => {
    test('boolean exposes path data and child shapes', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      b.x = 40;
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const bool = ctx.penpot.createBoolean('union', [a, b]);
      expect(bool).not.toBeNull();
      if (bool) {
        ctx.board.appendChild(bool);
        expect(bool.children.length).toBeGreaterThan(1);
        expect(typeof bool.d).toBe('string');
        expect(typeof bool.toD()).toBe('string');
        expect(Array.isArray(bool.commands)).toBe(true);
      }
    });
  });

  describe('Path', () => {
    test('d round-trips and populates commands', (ctx) => {
      const path = ctx.penpot.createPath();
      ctx.board.appendChild(path);
      path.d = 'M0 0 L10 0 L10 10 Z';
      expect(path.d).toContain('M');
      expect(path.commands.length).toBeGreaterThan(0);
      expect(typeof path.toD()).toBe('string');
    });

    test('content alias is readable and writable', (ctx) => {
      const path = ctx.penpot.createPath();
      ctx.board.appendChild(path);
      path.content = 'M0 0 L20 20';
      expect(typeof path.content).toBe('string');
    });
  });

  describe('Ellipse', () => {
    test('an ellipse reports its type', (ctx) => {
      const ellipse = ctx.penpot.createEllipse();
      ctx.board.appendChild(ellipse);
      expect(ellipse.type).toBe('ellipse');
    });
  });

  describe('SvgRaw', () => {
    test('an SVG import contains svg-raw descendants', (ctx) => {
      // Native tags (rect/circle/path/…) import as their own shape types; only
      // tags without a native mapping (e.g. <text>) become raw svg nodes, so the
      // fixture must include one to exercise SvgRaw.
      const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20">' +
        '<rect width="10" height="10" fill="#0000ff"/>' +
        '<text x="0" y="18">hi</text></svg>';
      const group = ctx.penpot.createShapeFromSvg(svg);
      expect(group).not.toBeNull();
      if (group) {
        ctx.board.appendChild(group);
        const raw = findByType(group, 'svg-raw');
        expect(raw).not.toBeNull();
        expect(raw && raw.type).toBe('svg-raw');
      }
    });
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests assert that building a circular shape
  // hierarchy is rejected; "success" tests assert the type predicates classify
  // shapes correctly and that masking round-trips (incl. nested in a board).
  // ---------------------------------------------------------------------------
  describe('Hierarchy — circular references', () => {
    // The plugin appendChild does not explicitly reject cycle-creating moves;
    // the underlying relocate drops them without throwing, leaving the
    // hierarchy untouched. These pin both halves: the call is not rejected
    // (cycle-prevention at the API boundary is a candidate for future
    // hardening) AND the tree stays intact.
    test('appending a board into itself is a safe no-op', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      expect(() => board.appendChild(board)).not.toThrow();
      expect(board.parent?.id).toBe(ctx.board.id);
      expect(board.children.some((c) => c.id === board.id)).toBe(false);
    });

    test('appending an ancestor into its descendant is a safe no-op', (ctx) => {
      const outer = ctx.penpot.createBoard();
      const inner = ctx.penpot.createBoard();
      ctx.board.appendChild(outer);
      outer.appendChild(inner);
      expect(() => inner.appendChild(outer)).not.toThrow();
      expect(outer.parent?.id).toBe(ctx.board.id);
      expect(inner.parent?.id).toBe(outer.id);
    });
  });

  describe('Type predicates — success edges', () => {
    test('utils.types classifies shapes by their concrete type', (ctx) => {
      const types = ctx.penpot.utils.types;

      const rect = ctx.penpot.createRectangle();
      const ellipse = ctx.penpot.createEllipse();
      const path = ctx.penpot.createPath();
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(rect);
      ctx.board.appendChild(ellipse);
      ctx.board.appendChild(path);
      ctx.board.appendChild(board);

      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const group = ctx.penpot.group([a, b]);

      expect(types.isRectangle(rect)).toBe(true);
      expect(types.isBoard(rect)).toBe(false);
      expect(types.isEllipse(ellipse)).toBe(true);
      expect(types.isPath(path)).toBe(true);
      expect(types.isBoard(board)).toBe(true);
      expect(types.isGroup(board)).toBe(false);
      if (group) {
        expect(types.isGroup(group)).toBe(true);
        expect(types.isMask(group)).toBe(false);
      }
    });

    test('makeMask / removeMask toggles isMask, including nested in a board', (ctx) => {
      const host = ctx.penpot.createBoard();
      ctx.board.appendChild(host);

      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      host.appendChild(a);
      host.appendChild(b);
      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        expect(group.isMask()).toBe(false);
        group.makeMask();
        expect(group.isMask()).toBe(true);
        expect(ctx.penpot.utils.types.isMask(group)).toBe(true);
        group.removeMask();
        expect(group.isMask()).toBe(false);
      }
    });
  });
});
