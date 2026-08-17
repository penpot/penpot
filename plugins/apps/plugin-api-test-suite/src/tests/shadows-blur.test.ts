import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { TestContext } from '../framework/types';

// Shadows & blur.
// Like fills/strokes, shadows are assigned as a whole array and read back; the
// nested Shadow.color yields a Color whose members are then exercised on read.

function rect(ctx: TestContext) {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

describe('Shadows', () => {
  test('drop shadow round-trips with a color', (ctx) => {
    const r = rect(ctx);
    r.shadows = [
      {
        style: 'drop-shadow',
        offsetX: 4,
        offsetY: 6,
        blur: 8,
        spread: 1,
        hidden: false,
        color: { color: '#000000', opacity: 0.5 },
      },
    ];

    expect(r.shadows).toHaveLength(1);
    const shadow = r.shadows[0];
    expect(shadow.style).toBe('drop-shadow');
    expect(shadow.offsetX).toBeCloseTo(4, 0);
    expect(shadow.offsetY).toBeCloseTo(6, 0);
    expect(shadow.blur).toBeCloseTo(8, 0);
    expect(shadow.spread).toBeCloseTo(1, 0);
    expect(shadow.hidden).toBe(false);
    expect(shadow.color).toBeDefined();
    expect(shadow.color && shadow.color.color).toBe('#000000');
    expect(shadow.color && shadow.color.opacity).toBeCloseTo(0.5, 2);
  });

  test('inner shadow can be hidden', (ctx) => {
    const r = rect(ctx);
    r.shadows = [
      {
        style: 'inner-shadow',
        offsetX: 0,
        offsetY: 0,
        blur: 4,
        spread: 0,
        hidden: true,
        color: { color: '#ff0000', opacity: 1 },
      },
    ];

    const shadow = r.shadows[0];
    expect(shadow.style).toBe('inner-shadow');
    expect(shadow.hidden).toBe(true);
  });
});

describe('Blur', () => {
  test('layer blur round-trips', (ctx) => {
    const r = rect(ctx);
    r.blur = { value: 10 };

    expect(r.blur).toBeDefined();
    expect(r.blur && r.blur.value).toBeCloseTo(10, 0);
    // hidden defaults to false when omitted.
    expect(r.blur && r.blur.hidden).toBeFalsy();
  });

  test('background blur round-trips', (ctx) => {
    const r = rect(ctx);
    r.backgroundBlur = { value: 5 };

    expect(r.backgroundBlur).toBeDefined();
    expect(r.backgroundBlur && r.backgroundBlur.value).toBeCloseTo(5, 0);
  });
});
