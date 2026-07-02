import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { TestContext } from '../framework/types';

// Shapes & geometry.
// Exercises the `ShapeBase` identity / geometry / transform / ordering members
// that are common to every shape, using a rectangle on the scratch board.

/** Creates a rectangle, appends it to the scratch board and returns it. */
function rect(ctx: TestContext) {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

describe('Shapes', () => {
  describe('Identity', () => {
    test('exposes a stable id', (ctx) => {
      const r = rect(ctx);
      expect(typeof r.id).toBe('string');
      expect(r.id.length).toBeGreaterThan(0);
    });

    test('name is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.name = 'sample-rect';
      expect(r.name).toBe('sample-rect');
    });

    test('parent points at the containing board', (ctx) => {
      const r = rect(ctx);
      expect(r.parent).not.toBeNull();
      expect(r.parent && r.parent.id).toBe(ctx.board.id);
    });

    test('parentIndex is a distinct structural index per sibling', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      // Two siblings on a fresh board occupy indices 0 and 1 (direction depends
      // on naturalChildOrdering, so assert the set rather than which is which).
      expect([a.parentIndex, b.parentIndex].sort()).toEqual([0, 1]);
    });
  });

  describe('Geometry', () => {
    test('x and y are readable and writable', (ctx) => {
      const r = rect(ctx);
      r.x = 120;
      r.y = 80;
      expect(r.x).toBeCloseTo(120, 0);
      expect(r.y).toBeCloseTo(80, 0);
    });

    test('resize changes width and height', (ctx) => {
      const r = rect(ctx);
      r.resize(200, 100);
      expect(r.width).toBeCloseTo(200, 0);
      expect(r.height).toBeCloseTo(100, 0);
    });

    test('bounds describes a rectangular area', (ctx) => {
      const r = rect(ctx);
      r.x = 10;
      r.y = 20;
      r.resize(50, 40);
      const b = r.bounds;
      expect(b.width).toBeCloseTo(50, 0);
      expect(b.height).toBeCloseTo(40, 0);
    });

    test('center sits in the middle of the shape', (ctx) => {
      const r = rect(ctx);
      r.x = 0;
      r.y = 0;
      r.resize(100, 100);
      const c = r.center;
      expect(c.x).toBeCloseTo(50, 0);
      expect(c.y).toBeCloseTo(50, 0);
    });

    test('boardX and boardY are readable and writable', (ctx) => {
      const r = rect(ctx);
      r.boardX = 15;
      r.boardY = 25;
      expect(r.boardX).toBeCloseTo(15, 0);
      expect(r.boardY).toBeCloseTo(25, 0);
    });

    test('parentX and parentY are readable and writable', (ctx) => {
      const r = rect(ctx);
      r.parentX = 12;
      r.parentY = 22;
      expect(r.parentX).toBeCloseTo(12, 0);
      expect(r.parentY).toBeCloseTo(22, 0);
    });
  });

  describe('Transform', () => {
    test('rotation is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.rotation = 45;
      expect(r.rotation).toBeCloseTo(45, 0);
    });

    test('rotate() applies an angle', (ctx) => {
      const r = rect(ctx);
      r.rotate(90);
      expect(r.rotation).toBeCloseTo(90, 0);
    });

    test('flipX is readable and writable', (ctx) => {
      const r = rect(ctx);
      expect(r.flipX).toBe(false);
      r.flipX = true;
      expect(r.flipX).toBe(true);
    });

    test('flipY is readable and writable', (ctx) => {
      const r = rect(ctx);
      expect(r.flipY).toBe(false);
      r.flipY = true;
      expect(r.flipY).toBe(true);
    });
  });

  // The geometry/transform members above run on a rectangle. Re-exercise the
  // core ones on other shape types so a type-specific regression is caught.
  describe('Geometry across shape types', () => {
    test('resize and rotate work on an ellipse', (ctx) => {
      const e = ctx.penpot.createEllipse();
      ctx.board.appendChild(e);
      e.resize(120, 60);
      expect(e.width).toBeCloseTo(120, 0);
      expect(e.height).toBeCloseTo(60, 0);
      e.rotate(45);
      expect(e.rotation).toBeCloseTo(45, 0);
    });

    test('flip works on an ellipse', (ctx) => {
      // Kept separate from rotation: flipping an already-rotated shape does not
      // round-trip through flipX, so exercise flip on an unrotated ellipse.
      const e = ctx.penpot.createEllipse();
      ctx.board.appendChild(e);
      expect(e.flipX).toBe(false);
      e.flipX = true;
      expect(e.flipX).toBe(true);
      e.flipY = true;
      expect(e.flipY).toBe(true);
    });

    test('resize and reposition work on a nested board', (ctx) => {
      const b = ctx.penpot.createBoard();
      ctx.board.appendChild(b);
      b.resize(200, 150);
      b.x = 25;
      b.y = 35;
      expect(b.width).toBeCloseTo(200, 0);
      expect(b.height).toBeCloseTo(150, 0);
      expect(b.x).toBeCloseTo(25, 0);
      expect(b.y).toBeCloseTo(35, 0);
    });
  });

  describe('Appearance flags', () => {
    test('blocked is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.blocked = true;
      expect(r.blocked).toBe(true);
    });

    test('hidden is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.hidden = true;
      expect(r.hidden).toBe(true);
    });

    test('visible is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.visible = false;
      expect(r.visible).toBe(false);
    });

    test('proportionLock is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.proportionLock = true;
      expect(r.proportionLock).toBe(true);
    });

    test('fixedWhenScrolling is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.fixedWhenScrolling = true;
      expect(r.fixedWhenScrolling).toBe(true);
    });

    test('opacity is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.opacity = 0.5;
      expect(r.opacity).toBeCloseTo(0.5, 2);
    });

    test('blendMode is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.blendMode = 'multiply';
      expect(r.blendMode).toBe('multiply');
    });
  });

  describe('Constraints', () => {
    test('constraintsHorizontal is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.constraintsHorizontal = 'center';
      expect(r.constraintsHorizontal).toBe('center');
    });

    test('constraintsVertical is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.constraintsVertical = 'center';
      expect(r.constraintsVertical).toBe('center');
    });
  });

  describe('Corner radius', () => {
    test('borderRadius is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.borderRadius = 8;
      expect(r.borderRadius).toBeCloseTo(8, 0);
    });

    test('per-corner border radius is readable and writable', (ctx) => {
      const r = rect(ctx);
      r.borderRadiusTopLeft = 1;
      r.borderRadiusTopRight = 2;
      r.borderRadiusBottomRight = 3;
      r.borderRadiusBottomLeft = 4;
      expect(r.borderRadiusTopLeft).toBeCloseTo(1, 0);
      expect(r.borderRadiusTopRight).toBeCloseTo(2, 0);
      expect(r.borderRadiusBottomRight).toBeCloseTo(3, 0);
      expect(r.borderRadiusBottomLeft).toBeCloseTo(4, 0);
    });

    // Border radius setters accept fractional numbers, not just integers.
    test('border radius accepts fractional values', (ctx) => {
      const r = rect(ctx);
      r.borderRadius = 4.5;
      expect(r.borderRadius).toBeCloseTo(4.5, 2);
      r.borderRadiusTopLeft = 1.25;
      r.borderRadiusTopRight = 2.5;
      r.borderRadiusBottomRight = 3.75;
      r.borderRadiusBottomLeft = 0.5;
      expect(r.borderRadiusTopLeft).toBeCloseTo(1.25, 2);
      expect(r.borderRadiusTopRight).toBeCloseTo(2.5, 2);
      expect(r.borderRadiusBottomRight).toBeCloseTo(3.75, 2);
      expect(r.borderRadiusBottomLeft).toBeCloseTo(0.5, 2);
    });
  });

  describe('Ordering', () => {
    test('setParentIndex moves the shape to the given index', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      void a;
      b.setParentIndex(0);
      expect(b.parentIndex).toBe(0);
    });

    test('bringToFront / sendToBack move the shape to opposite extremes', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      void b;
      const last = ctx.board.children.length - 1;

      a.bringToFront();
      const front = a.parentIndex;
      expect(front === 0 || front === last).toBe(true);

      a.sendToBack();
      const back = a.parentIndex;
      expect(back === 0 || back === last).toBe(true);

      // Front and back must be different extremes.
      expect(front).not.toBe(back);
    });

    test('bringForward / sendBackward move the shape one step', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      const c = rect(ctx);
      void b;
      void c;

      const start = a.parentIndex;
      a.bringForward();
      expect(Math.abs(a.parentIndex - start)).toBe(1);

      const mid = a.parentIndex;
      a.sendBackward();
      expect(Math.abs(a.parentIndex - mid)).toBe(1);
    });
  });

  describe('Lifecycle', () => {
    test('clone duplicates a shape', (ctx) => {
      const r = rect(ctx);
      r.name = 'original';
      const copy = r.clone();
      ctx.board.appendChild(copy);
      expect(copy.id).not.toBe(r.id);
      expect(copy.type).toBe('rectangle');
    });

    test('remove detaches the shape from its parent', (ctx) => {
      const r = rect(ctx);
      const before = ctx.board.children.length;
      r.remove();
      expect(ctx.board.children.length).toBe(before - 1);
    });
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests assert invalid numeric/enum input is
  // rejected; "success" tests assert documented boundary behaviour
  // (setParentIndex clamps to last, rotation about the center, opacity 0/1).
  // ---------------------------------------------------------------------------
  describe('Numeric & enum — invalid values (fail)', () => {
    test('opacity below 0 throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.opacity = -0.1;
      }).toThrow();
    });

    test('opacity above 1 throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.opacity = 1.5;
      }).toThrow();
    });

    test('NaN opacity throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.opacity = NaN;
      }).toThrow();
    });

    test('NaN rotation throws', (ctx) => {
      // The rotation setter rejects non-finite numbers; a NaN would otherwise
      // reach the geometry layer as an invalid move vector.
      const r = rect(ctx);
      expect(() => {
        r.rotation = NaN;
      }).toThrow();
    });

    test('invalid blendMode throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.blendMode = 'not-a-mode' as unknown as 'normal';
      }).toThrow();
    });

    test('negative borderRadius throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.borderRadius = -8;
      }).toThrow();
    });

    test('setParentIndex with a negative index is accepted (currently unvalidated)', (ctx) => {
      // setParentIndex does not reject a negative index; this pins the current
      // lenient behaviour (a candidate for future hardening) and that the
      // hierarchy survives it: the shape stays a child and no sibling is
      // duplicated or dropped.
      const a = rect(ctx);
      const b = rect(ctx);
      void b;
      const countBefore = ctx.board.children.length;
      expect(() => a.setParentIndex(-1)).not.toThrow();
      expect(a.parent?.id).toBe(ctx.board.id);
      expect(ctx.board.children).toHaveLength(countBefore);
      expect(ctx.board.children.filter((c) => c.id === a.id)).toHaveLength(1);
    });
  });

  describe('Geometry & ordering — success edges', () => {
    test('opacity accepts the 0 and 1 boundaries', (ctx) => {
      const r = rect(ctx);
      r.opacity = 0;
      expect(r.opacity).toBeCloseTo(0, 2);
      r.opacity = 1;
      expect(r.opacity).toBeCloseTo(1, 2);
    });

    test('setParentIndex past the end positions the shape last', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      const c = rect(ctx);
      void b;
      void c;
      // The d.ts documents: "If the index is greater than the number of
      // elements it will positioned last."
      a.setParentIndex(999);
      expect(a.parentIndex).toBe(ctx.board.children.length - 1);
    });

    test('setParentIndex reorders siblings while keeping a contiguous index set', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      const c = rect(ctx);
      void a;
      void c;
      b.setParentIndex(0);
      expect(b.parentIndex).toBe(0);
      const indices = ctx.board.children.map((s) => s.parentIndex).sort();
      expect(indices).toEqual([0, 1, 2]);
    });

    test('rotating 360 degrees leaves the center unchanged', (ctx) => {
      const r = rect(ctx);
      r.x = 0;
      r.y = 0;
      r.resize(100, 100);
      r.rotation = 360;
      const c = r.center;
      expect(c.x).toBeCloseTo(50, 0);
      expect(c.y).toBeCloseTo(50, 0);
    });
  });
});
