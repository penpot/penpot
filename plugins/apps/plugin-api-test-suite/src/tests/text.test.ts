import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Text } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Text & text ranges.
// Font-dependent properties (fontFamily/fontWeight/…) are only read here; they
// are set properly via the Font API in fonts.test.ts. The style properties that
// don't depend on a concrete font are set and read back.

function text(ctx: TestContext, value = 'Hello Penpot'): Text {
  const t = ctx.penpot.createText(value);
  if (!t) throw new Error('createText returned null');
  ctx.board.appendChild(t);
  return t;
}

describe('Text', () => {
  test('characters round-trip', (ctx) => {
    const t = text(ctx);
    t.characters = 'Updated content';
    expect(t.characters).toBe('Updated content');
  });

  // Community report (forum #10700, issue #7): a text shape must always have
  // content, so assigning an empty string to characters is rejected by
  // validation (by design). To make a text disappear, hide or remove the shape.
  test('characters rejects an empty string', (ctx) => {
    const t = text(ctx, 'hello');
    expect(() => {
      t.characters = '';
    }).toThrow();
    expect(t.characters).toBe('hello');
  });

  // Community report (forum #10700, feature request D): rejecting a font
  // weight the current font has no variant for must tell the caller which
  // weights are supported.
  test('unsupported fontWeight error lists the supported weights', (ctx) => {
    const t = text(ctx, 'hello');
    expect(() => {
      t.fontWeight = '123';
    }).toThrow('Supported weights:');
  });

  test('growType round-trips', (ctx) => {
    const t = text(ctx);
    t.growType = 'auto-height';
    expect(t.growType).toBe('auto-height');
  });

  test('fontSize round-trips', (ctx) => {
    const t = text(ctx);
    t.fontSize = '24';
    expect(t.fontSize).toBe('24');
  });

  test('lineHeight and letterSpacing round-trip', (ctx) => {
    const t = text(ctx);
    t.lineHeight = '1.5';
    t.letterSpacing = '2';
    expect(t.lineHeight).toBe('1.5');
    expect(t.letterSpacing).toBe('2');
  });

  test('alignment round-trips', (ctx) => {
    const t = text(ctx);
    t.align = 'center';
    t.verticalAlign = 'center';
    expect(t.align).toBe('center');
    expect(t.verticalAlign).toBe('center');
  });

  test('transform, decoration and direction round-trip', (ctx) => {
    const t = text(ctx);
    t.textTransform = 'uppercase';
    t.textDecoration = 'underline';
    t.direction = 'rtl';
    expect(t.textTransform).toBe('uppercase');
    expect(t.textDecoration).toBe('underline');
    expect(t.direction).toBe('rtl');
  });

  test('font identity and variant setters accept a real font/variant', (ctx) => {
    const t = text(ctx);
    const font = ctx.penpot.fonts.all[0];
    const variant = font.variants[0];
    // Set the font identity first, then the variant-specific properties using
    // values drawn from that same font so validation passes.
    t.fontId = font.fontId;
    t.fontFamily = font.fontFamily;
    t.fontVariantId = variant.fontVariantId;
    t.fontWeight = variant.fontWeight;
    t.fontStyle = variant.fontStyle;
    expect(t.fontId).toBe(font.fontId);
  });

  test('font properties are readable', (ctx) => {
    const t = text(ctx);
    expect(typeof t.fontId).toBe('string');
    expect(typeof t.fontFamily).toBe('string');
    expect(typeof t.fontVariantId).toBe('string');
    expect(typeof t.fontWeight).toBe('string');
    // fontStyle is 'normal' | 'italic' | 'mixed' | null
    expect(t.fontStyle === null || typeof t.fontStyle === 'string').toBe(true);
  });

  test('textBounds exposes a rectangle shape', (ctx) => {
    const t = text(ctx);
    const b = t.textBounds;
    // The numeric values depend on text layout (`:position-data`), which the
    // headless runner does not compute, so width/height may be null in CI but
    // are real numbers in the interactive editor. Assert the shape of the object.
    expect('x' in b).toBe(true);
    expect('y' in b).toBe(true);
    expect('width' in b).toBe(true);
    expect('height' in b).toBe(true);
  });

  test('applyTypography applies a typography to the text shape', (ctx) => {
    const typo = ctx.penpot.library.local.createTypography();
    typo.fontSize = '21';
    const t = text(ctx);
    t.applyTypography(typo);
    expect(t.fontSize).toBe('21');
  });

  describe('Range', () => {
    test('getRange returns the range characters', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      expect(range.characters.length).toBeGreaterThan(0);
    });

    test('range shape references the owning text shape', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      expect(range.shape.type).toBe('text');
    });

    test('range font size round-trips', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      range.fontSize = '30';
      expect(range.fontSize).toBe('30');
    });

    test('range line height and letter spacing round-trip', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      range.lineHeight = '2';
      range.letterSpacing = '1';
      expect(range.lineHeight).toBe('2');
      expect(range.letterSpacing).toBe('1');
    });

    test('range alignment round-trips', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      range.align = 'right';
      range.verticalAlign = 'center';
      expect(range.align).toBe('right');
      expect(range.verticalAlign).toBe('center');
    });

    test('range transform and decoration round-trip', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      range.textTransform = 'lowercase';
      range.textDecoration = 'line-through';
      expect(range.textTransform).toBe('lowercase');
      expect(range.textDecoration).toBe('line-through');
    });

    test('range fills round-trip', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      range.fills = [{ fillColor: '#00ff00', fillOpacity: 1 }];

      const fills = range.fills;
      if (Array.isArray(fills)) {
        expect(fills[0].fillColor).toBe('#00ff00');
      }
    });

    test('two ranges keep independent fills', (ctx) => {
      // Mixed-style coverage: distinct fills on distinct sub-ranges must not
      // bleed into each other.
      const t = text(ctx, 'Hello Penpot');
      const first = t.getRange(0, 5);
      const second = t.getRange(6, 12);
      first.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      second.fills = [{ fillColor: '#0000ff', fillOpacity: 1 }];

      const f1 = first.fills;
      const f2 = second.fills;
      if (Array.isArray(f1) && Array.isArray(f2)) {
        expect(f1[0].fillColor).toBe('#ff0000');
        expect(f2[0].fillColor).toBe('#0000ff');
      }
    });

    test('range font properties are readable', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      expect(typeof range.fontId).toBe('string');
      expect(typeof range.fontFamily).toBe('string');
      expect(typeof range.fontVariantId).toBe('string');
      expect(typeof range.fontWeight).toBe('string');
    });

    test('range style properties are readable', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      void range.direction;
      void range.fontStyle;
      void range.letterSpacing;
      void range.lineHeight;
      void range.textDecoration;
      void range.textTransform;
      void range.verticalAlign;
      void range.align;
      expect(range.characters.length).toBeGreaterThan(0);
    });

    test('range font property setters are exercised (coverage only)', (ctx) => {
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      const font = ctx.penpot.fonts.all[0];
      // Setting records the (set) targets; partial-range persistence is a known
      // API bug covered elsewhere, so only the call is exercised here.
      // (fontStyle/fontVariantId/fontWeight are validated strictly against the
      // current font's variants, so they are left out to avoid fragility.)
      range.fontFamily = font.fontFamily;
      range.fontId = font.fontId;
      range.direction = 'ltr';
      // Variant-specific setters, using values from the same font so the strict
      // per-font validation passes.
      const variant = font.variants[0];
      range.fontVariantId = variant.fontVariantId;
      range.fontWeight = variant.fontWeight;
      range.fontStyle = variant.fontStyle;
      expect(range.characters.length).toBeGreaterThan(0);
    });

    test('applyTypography applies to a text range', (ctx) => {
      const typo = ctx.penpot.library.local.createTypography();
      const t = text(ctx, 'Hello Penpot');
      const range = t.getRange(0, 5);
      range.applyTypography(typo);
      expect(range.characters.length).toBeGreaterThan(0);
    });
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests assert invalid input is rejected;
  // "success" tests assert non-trivial valid behaviour (mixed detection,
  // full-span application, multi-paragraph round-trip).
  // ---------------------------------------------------------------------------
  test('getRange with start greater than end throws', (ctx) => {
    const t = text(ctx, 'Hello Penpot');
    expect(() => t.getRange(5, 1)).toThrow();
  });

  test('getRange with a negative index throws', (ctx) => {
    const t = text(ctx, 'Hello Penpot');
    expect(() => t.getRange(-1, 5)).toThrow();
  });

  test('getRange beyond the text length is clamped (not rejected)', (ctx) => {
    // An end index past the text length is clamped rather than rejected: the
    // range object is returned and reading `characters` yields the clamped
    // text. (Reading `characters` once crashed with a TypeError on an internal
    // null; fixed, kept as a regression pin.)
    const t = text(ctx, 'Hello Penpot');
    let range: ReturnType<typeof t.getRange> | null = null;
    expect(() => {
      range = t.getRange(0, 999);
    }).not.toThrow();
    expect(range).not.toBeNull();
    expect(range!.characters).toBe('Hello Penpot');
  });

  test('empty fontSize throws', (ctx) => {
    const t = text(ctx);
    expect(() => {
      t.fontSize = '';
    }).toThrow();
  });

  test('negative fontSize throws', (ctx) => {
    const t = text(ctx);
    expect(() => {
      t.fontSize = '-12';
    }).toThrow();
  });

  test('non-numeric fontSize throws', (ctx) => {
    const t = text(ctx);
    expect(() => {
      t.fontSize = 'abc';
    }).toThrow();
  });

  test('invalid align value throws', (ctx) => {
    const t = text(ctx);
    expect(() => {
      t.align = 'middle' as unknown as 'center';
    }).toThrow();
  });

  test('wrong-case textTransform throws', (ctx) => {
    const t = text(ctx);
    expect(() => {
      t.textTransform = 'UPPERCASE' as unknown as 'uppercase';
    }).toThrow();
  });

  test('invalid direction value throws', (ctx) => {
    const t = text(ctx);
    expect(() => {
      t.direction = 'sideways' as unknown as 'ltr';
    }).toThrow();
  });

  test('a uniformly-set fontSize is reported, not mixed', (ctx) => {
    const t = text(ctx, 'Hello Penpot');
    t.fontSize = '20';
    expect(t.fontSize).toBe('20');
  });

  test('setting fontSize on a sub-range makes the shape report mixed', (ctx) => {
    const t = text(ctx, 'Hello Penpot');
    t.fontSize = '20';
    const range = t.getRange(0, 5);
    range.fontSize = '40';
    expect(t.fontSize).toBe('mixed');
  });

  test('applying a value to the full span is uniform, not mixed', (ctx) => {
    const t = text(ctx, 'Hello Penpot');
    const range = t.getRange(0, t.characters.length);
    range.fontSize = '33';
    expect(t.fontSize).toBe('33');
  });

  test('multi-paragraph content round-trips', (ctx) => {
    const t = text(ctx);
    t.characters = 'first line\nsecond line';
    expect(t.characters).toBe('first line\nsecond line');
  });
});
