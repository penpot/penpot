import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { TestContext } from '../framework/types';
import { PNG_1X1 } from './fixtures';

// Value-object property setters.
// Fills/strokes/gradients/shadows are returned as live proxies (their setters
// persist to the shape); blur and the shadow `color` are returned as plain
// snapshots (setting records the member and round-trips on the returned
// object). Either way every writable member is exercised by reading the value
// object and setting each property.

function rect(ctx: TestContext) {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

describe('Value objects', () => {
  describe('Fill', () => {
    test('solid fill members round-trip', (ctx) => {
      const r = rect(ctx);
      r.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      const fills = r.fills;
      if (Array.isArray(fills)) {
        const fill = fills[0];
        fill.fillColor = '#00ff00';
        fill.fillOpacity = 0.5;
        expect(fill.fillColor).toBe('#00ff00');
        expect(fill.fillOpacity).toBeCloseTo(0.5, 2);
      }
    });

    test('assigning a gradient on a live solid fill switches it', (ctx) => {
      const r = rect(ctx);
      r.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      const fills = r.fills;
      if (Array.isArray(fills)) {
        fills[0].fillColorGradient = {
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
        expect(fills[0].fillColorGradient).toBeDefined();
      }
    });

    test('fill color reference members round-trip', (ctx) => {
      const r = rect(ctx);
      r.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      const fills = r.fills;
      if (Array.isArray(fills)) {
        const fill = fills[0];
        fill.fillColorRefId = '00000000-0000-0000-0000-000000000001';
        fill.fillColorRefFile = '00000000-0000-0000-0000-000000000002';
        expect(fill.fillColorRefId).toBe(
          '00000000-0000-0000-0000-000000000001',
        );
        expect(fill.fillColorRefFile).toBe(
          '00000000-0000-0000-0000-000000000002',
        );
      }
    });

    test('fill gradient members round-trip', (ctx) => {
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
        if (gradient) {
          gradient.type = 'radial';
          gradient.startX = 0.2;
          gradient.startY = 0.3;
          gradient.endX = 0.8;
          gradient.endY = 0.9;
          gradient.width = 0.5;
          expect(gradient.type).toBe('radial');
          expect(gradient.startX).toBeCloseTo(0.2, 2);
          expect(gradient.endY).toBeCloseTo(0.9, 2);
          expect(gradient.width).toBeCloseTo(0.5, 2);
          expect(gradient.stops.length).toBeGreaterThan(0);
        }
      }
    });
  });

  describe('Stroke', () => {
    test('stroke members round-trip', (ctx) => {
      const r = rect(ctx);
      r.strokes = [{ strokeColor: '#000000', strokeWidth: 1 }];
      const stroke = r.strokes[0];
      stroke.strokeColor = '#112233';
      stroke.strokeOpacity = 0.7;
      stroke.strokeStyle = 'dotted';
      stroke.strokeWidth = 4;
      stroke.strokeAlignment = 'inner';
      stroke.strokeCapStart = 'round';
      stroke.strokeCapEnd = 'square';
      expect(stroke.strokeColor).toBe('#112233');
      expect(stroke.strokeOpacity).toBeCloseTo(0.7, 2);
      expect(stroke.strokeStyle).toBe('dotted');
      expect(stroke.strokeWidth).toBeCloseTo(4, 0);
      expect(stroke.strokeAlignment).toBe('inner');
      expect(stroke.strokeCapStart).toBe('round');
      expect(stroke.strokeCapEnd).toBe('square');
    });

    test('stroke reference and gradient members round-trip', (ctx) => {
      const r = rect(ctx);
      r.strokes = [{ strokeColor: '#000000', strokeWidth: 1 }];
      const stroke = r.strokes[0];
      stroke.strokeColorRefId = '00000000-0000-0000-0000-000000000001';
      stroke.strokeColorRefFile = '00000000-0000-0000-0000-000000000002';
      expect(stroke.strokeColorRefId).toBe(
        '00000000-0000-0000-0000-000000000001',
      );
      expect(stroke.strokeColorRefFile).toBe(
        '00000000-0000-0000-0000-000000000002',
      );

      stroke.strokeColorGradient = {
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
      expect(stroke.strokeColorGradient).toBeDefined();
    });
  });

  describe('Shadow', () => {
    test('shadow members round-trip on the returned shadow', (ctx) => {
      const r = rect(ctx);
      r.shadows = [
        {
          style: 'drop-shadow',
          offsetX: 1,
          offsetY: 1,
          blur: 2,
          spread: 0,
          hidden: false,
          color: { color: '#000000', opacity: 1 },
        },
      ];
      const shadow = r.shadows[0];
      shadow.style = 'inner-shadow';
      shadow.offsetX = 5;
      shadow.offsetY = 6;
      shadow.blur = 7;
      shadow.spread = 2;
      shadow.hidden = true;
      shadow.id = '00000000-0000-0000-0000-000000000003';
      expect(shadow.style).toBe('inner-shadow');
      expect(shadow.offsetX).toBeCloseTo(5, 0);
      expect(shadow.offsetY).toBeCloseTo(6, 0);
      expect(shadow.blur).toBeCloseTo(7, 0);
      expect(shadow.spread).toBeCloseTo(2, 0);
      expect(shadow.hidden).toBe(true);
    });

    // Skipped under MOCK_BACKEND: exercises uploadMediaData, which needs real
    // backend media processing (ImageMagick) a mock can't reproduce.
    test.skipIfMocked('shadow color members round-trip', async (ctx) => {
      const image = await ctx.penpot.uploadMediaData(
        'shadow-color-image',
        PNG_1X1,
        'image/png',
      );
      const r = rect(ctx);
      r.shadows = [
        {
          style: 'drop-shadow',
          offsetX: 1,
          offsetY: 1,
          blur: 2,
          spread: 0,
          hidden: false,
          color: { color: '#000000', opacity: 1 },
        },
      ];
      const color = r.shadows[0].color;
      expect(color).toBeDefined();
      if (color) {
        color.color = '#abcdef';
        color.opacity = 0.4;
        color.id = '00000000-0000-0000-0000-000000000004';
        color.name = 'shadow-color';
        color.path = 'group';
        color.refId = '00000000-0000-0000-0000-000000000005';
        color.refFile = '00000000-0000-0000-0000-000000000006';
        color.fileId = '00000000-0000-0000-0000-000000000007';
        // Color is a plain snapshot, so image set/read round-trips on it like
        // the other members.
        color.image = image;
        expect(color.color).toBe('#abcdef');
        expect(color.opacity).toBeCloseTo(0.4, 2);
        expect(color.name).toBe('shadow-color');
        expect(color.path).toBe('group');
        expect(color.image).toBeDefined();
      }
    });
  });

  describe('Blur', () => {
    test('blur members round-trip on the returned blur', (ctx) => {
      const r = rect(ctx);
      r.blur = { value: 5 };
      const blur = r.blur;
      expect(blur).toBeDefined();
      if (blur) {
        blur.value = 12;
        blur.hidden = true;
        blur.id = '00000000-0000-0000-0000-000000000008';
        expect(blur.value).toBeCloseTo(12, 0);
        expect(blur.hidden).toBe(true);
        expect(blur.id).toBe('00000000-0000-0000-0000-000000000008');
      }
    });

    test('negative blur value is accepted (currently unvalidated)', (ctx) => {
      // The blur setter does not reject a negative value; this pins the current
      // lenient behaviour (a candidate for future hardening).
      const r = rect(ctx);
      expect(() => {
        r.blur = { value: -5 };
      }).not.toThrow();
    });
  });

  // ---------------------------------------------------------------------------
  // Edge cases — gradients.
  // ---------------------------------------------------------------------------
  describe('Gradient — edge cases', () => {
    test('a gradient stop offset outside 0..1 throws', (ctx) => {
      const r = rect(ctx);
      expect(() => {
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
                { color: '#0000ff', opacity: 1, offset: 1.5 },
              ],
            },
          },
        ];
      }).toThrow();
    });

    test('a gradient with many stops at boundary offsets round-trips', (ctx) => {
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
              { color: '#00ff00', opacity: 1, offset: 0.5 },
              { color: '#0000ff', opacity: 1, offset: 1 },
            ],
          },
        },
      ];
      const fills = r.fills;
      if (Array.isArray(fills)) {
        const gradient = fills[0].fillColorGradient;
        expect(gradient).toBeDefined();
        if (gradient) {
          expect(gradient.stops.length).toBe(3);
          expect(gradient.stops[0].offset).toBeCloseTo(0, 2);
          expect(gradient.stops[2].offset).toBeCloseTo(1, 2);
        }
      }
    });
  });
});
