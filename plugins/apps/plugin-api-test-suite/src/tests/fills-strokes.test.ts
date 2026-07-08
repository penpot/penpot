import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { TestContext } from '../framework/types';

// Fills & strokes.
// Fills/strokes are assigned as whole arrays of plain objects and read back
// through the shape proxy, so the Fill/Stroke getters are what coverage records
// (the per-property setters are not individually settable at runtime).
//
// Each group bundles its happy-path round-trips together with the related edge
// cases: "throws" tests assert invalid input is rejected, the "(currently
// unvalidated)" tests pin lenient behaviour, and the remaining ones cover
// non-trivial valid behaviour (ordering, type switching, multiple strokes,
// clearing).

function rect(ctx: TestContext) {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

describe('Fills & strokes', () => {
  describe('Fills', () => {
    test('solid fill color and opacity round-trip', (ctx) => {
      const r = rect(ctx);
      r.fills = [{ fillColor: '#ff0000', fillOpacity: 0.5 }];

      const fills = r.fills;
      expect(fills).toHaveLength(1);
      if (Array.isArray(fills)) {
        expect(fills[0].fillColor).toBe('#ff0000');
        expect(fills[0].fillOpacity).toBeCloseTo(0.5, 2);
      }
    });

    test('gradient fill is preserved', (ctx) => {
      const r = rect(ctx);
      r.fills = [
        {
          fillColorGradient: {
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
          },
        },
      ];

      const fills = r.fills;
      if (Array.isArray(fills)) {
        const gradient = fills[0].fillColorGradient;
        expect(gradient).toBeDefined();
        expect(gradient && gradient.type).toBe('linear');
      }
    });

    test('multiple fills can be stacked', (ctx) => {
      const r = rect(ctx);
      r.fills = [
        { fillColor: '#ff0000', fillOpacity: 0.5 },
        { fillColor: '#0000ff', fillOpacity: 0.5 },
      ];
      expect(r.fills).toHaveLength(2);
    });

    test('multiple fills preserve their order', (ctx) => {
      const r = rect(ctx);
      r.fills = [
        { fillColor: '#ff0000', fillOpacity: 1 },
        { fillColor: '#00ff00', fillOpacity: 1 },
        { fillColor: '#0000ff', fillOpacity: 1 },
      ];
      const fills = r.fills;
      expect(fills).toHaveLength(3);
      if (Array.isArray(fills)) {
        expect(fills.map((f) => f.fillColor)).toEqual([
          '#ff0000',
          '#00ff00',
          '#0000ff',
        ]);
      }
    });

    test('a fill can switch solid -> gradient -> solid', (ctx) => {
      const r = rect(ctx);
      r.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      r.fills = [
        {
          fillColorGradient: {
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
          },
        },
      ];
      let fills = r.fills;
      if (Array.isArray(fills)) {
        expect(fills[0].fillColorGradient).toBeDefined();
      }
      r.fills = [{ fillColor: '#00ff00', fillOpacity: 1 }];
      fills = r.fills;
      if (Array.isArray(fills)) {
        expect(fills[0].fillColor).toBe('#00ff00');
        // Switching back to a solid fill clears the gradient (read back as null).
        expect(fills[0].fillColorGradient).toBeFalsy();
      }
    });

    test('a gradient-only fill reads back no solid fillColor', (ctx) => {
      const r = rect(ctx);
      r.fills = [
        {
          fillColorGradient: {
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
          },
        },
      ];
      const fills = r.fills;
      if (Array.isArray(fills)) {
        expect(fills[0].fillColorGradient).toBeDefined();
        // A gradient fill carries no solid color.
        expect(fills[0].fillColor).toBeFalsy();
      }
    });

    test('fillOpacity above 1 throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.fills = [{ fillColor: '#ff0000', fillOpacity: 1.5 }];
      }).toThrow();
    });

    test('fillOpacity below 0 throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.fills = [{ fillColor: '#ff0000', fillOpacity: -0.5 }];
      }).toThrow();
    });

    test('setting fills on a group is accepted (currently unvalidated)', (ctx) => {
      // The plugin API does not block fills on groups, so the assignment is
      // accepted rather than rejected. This pins the current (lenient) behaviour.
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      const group = ctx.penpot.group([a, b]);
      expect(group).not.toBeNull();
      if (group) {
        expect(() => {
          group.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
        }).not.toThrow();
      }
    });

    test('assigning empty arrays clears fills and strokes', (ctx) => {
      const r = rect(ctx);
      r.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      r.strokes = [{ strokeColor: '#000000', strokeWidth: 1 }];
      r.fills = [];
      r.strokes = [];
      expect(r.fills).toHaveLength(0);
      expect(r.strokes).toHaveLength(0);
    });
  });

  describe('Strokes', () => {
    test('stroke properties round-trip', (ctx) => {
      const r = rect(ctx);
      r.strokes = [
        {
          strokeColor: '#0000ff',
          strokeOpacity: 1,
          strokeStyle: 'solid',
          strokeWidth: 3,
          strokeAlignment: 'center',
        },
      ];

      expect(r.strokes).toHaveLength(1);
      const stroke = r.strokes[0];
      expect(stroke.strokeColor).toBe('#0000ff');
      expect(stroke.strokeOpacity).toBeCloseTo(1, 2);
      expect(stroke.strokeStyle).toBe('solid');
      expect(stroke.strokeWidth).toBeCloseTo(3, 0);
      expect(stroke.strokeAlignment).toBe('center');
    });

    test('stroke caps round-trip on an open path', (ctx) => {
      const path = ctx.penpot.createPath();
      ctx.board.appendChild(path);
      path.d = 'M0 0 L40 0';
      path.strokes = [
        {
          strokeColor: '#000000',
          strokeWidth: 4,
          strokeCapStart: 'round',
          strokeCapEnd: 'triangle-arrow',
        },
      ];

      const stroke = path.strokes[0];
      expect(stroke.strokeCapStart).toBe('round');
      expect(stroke.strokeCapEnd).toBe('triangle-arrow');
    });

    test('dashed stroke style is preserved', (ctx) => {
      const r = rect(ctx);
      r.strokes = [
        { strokeColor: '#00ff00', strokeWidth: 2, strokeStyle: 'dashed' },
      ];
      expect(r.strokes[0].strokeStyle).toBe('dashed');
    });

    test('dotted stroke style is preserved', (ctx) => {
      const r = rect(ctx);
      r.strokes = [
        { strokeColor: '#0000ff', strokeWidth: 2, strokeStyle: 'dotted' },
      ];
      expect(r.strokes[0].strokeStyle).toBe('dotted');
    });

    test("stroke style 'none' is rejected at runtime (d.ts lists it)", (ctx) => {
      // The d.ts allows strokeStyle 'none', but the runtime rejects it as an
      // invalid value ("Value not valid"), so with throwValidationErrors it
      // throws. Pins the current d.ts/runtime mismatch.
      const r = rect(ctx);
      expect(() => {
        r.strokes = [
          { strokeColor: '#0000ff', strokeWidth: 2, strokeStyle: 'none' },
        ];
      }).toThrow();
    });

    test('two strokes with different alignment coexist', (ctx) => {
      const r = rect(ctx);
      r.strokes = [
        { strokeColor: '#000000', strokeWidth: 2, strokeAlignment: 'inner' },
        { strokeColor: '#ffffff', strokeWidth: 1, strokeAlignment: 'outer' },
      ];
      expect(r.strokes).toHaveLength(2);
      expect(r.strokes.map((s) => s.strokeAlignment).sort()).toEqual([
        'inner',
        'outer',
      ]);
    });

    test('negative strokeWidth is accepted (currently unvalidated)', (ctx) => {
      // The plugin API does not constrain strokeWidth to be non-negative, so a
      // negative value is stored as-is rather than rejected. This pins the current
      // (lenient) behaviour.
      const r = rect(ctx);
      r.strokes = [{ strokeColor: '#000000', strokeWidth: -3 }];
      expect(r.strokes).toHaveLength(1);
      expect(typeof r.strokes[0].strokeWidth).toBe('number');
    });

    test('invalid strokeStyle throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.strokes = [
          {
            strokeColor: '#000000',
            strokeWidth: 1,
            strokeStyle: 'wavy' as unknown as 'solid',
          },
        ];
      }).toThrow();
    });

    test('invalid strokeAlignment throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
        r.strokes = [
          {
            strokeColor: '#000000',
            strokeWidth: 1,
            strokeAlignment: 'middle' as unknown as 'center',
          },
        ];
      }).toThrow();
    });
  });
});
