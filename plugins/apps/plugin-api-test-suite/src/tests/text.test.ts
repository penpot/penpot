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

  test('Japanese paragraph layout properties round-trip', (ctx) => {
    const t = text(ctx, '日本語');
    t.writingMode = 'vertical-rl';
    t.textOrientation = 'upright';
    expect(t.writingMode).toBe('vertical-rl');
    expect(t.textOrientation).toBe('upright');
  });

  test('line adjustment round-trips', (ctx) => {
    const t = text(ctx, '日本語');
    expect(t.lineAdjustment).toBe('push-in-first');
    t.lineAdjustment = 'push-out-only';
    expect(t.lineAdjustment).toBe('push-out-only');
  });

  test('Japanese span layout properties round-trip', (ctx) => {
    const t = text(ctx, '日本語');
    t.textCombineUpright = 'digits2';
    t.textEmphasis = 'filled-dot';
    t.warichu = 'warichu';
    t.fontFeatures = 'vpal';
    t.annotationClearance = 'auto';
    t.ruby = 'にほんご';
    t.rubySize = 'third';
    t.rubyAlign = 'center';
    t.rubyOverhang = 'none';
    t.rubySide = 'under';
    expect(t.textCombineUpright).toBe('digits2');
    expect(t.textEmphasis).toBe('filled-dot');
    expect(t.warichu).toBe('warichu');
    expect(t.fontFeatures).toBe('vpal');
    expect(t.annotationClearance).toBe('auto');
    expect(t.ruby).toBe('にほんご');
    expect(t.rubySize).toBe('third');
    expect(t.rubyAlign).toBe('center');
    expect(t.rubyOverhang).toBe('none');
    expect(t.rubySide).toBe('under');
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

    test('range Japanese layout properties round-trip', (ctx) => {
      const t = text(ctx, '日本語');
      const range = t.getRange(0, 3);
      range.textCombineUpright = 'all';
      range.textEmphasis = 'open-circle';
      range.warichu = 'warichu';
      range.fontFeatures = 'palt';
      range.annotationClearance = 'auto';
      range.ruby = 'にほんご';
      range.rubySize = 'quarter';
      range.rubyAlign = 'space-between';
      range.rubyOverhang = 'none';
      range.rubySide = 'under';
      expect(range.textCombineUpright).toBe('all');
      expect(range.textEmphasis).toBe('open-circle');
      expect(range.warichu).toBe('warichu');
      expect(range.fontFeatures).toBe('palt');
      expect(range.annotationClearance).toBe('auto');
      expect(range.ruby).toBe('にほんご');
      expect(range.rubySize).toBe('quarter');
      expect(range.rubyAlign).toBe('space-between');
      expect(range.rubyOverhang).toBe('none');
      expect(range.rubySide).toBe('under');
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

  test('text numeric fields require complete numbers within editor bounds', (ctx) => {
    const t = text(ctx);
    for (const value of ['.', '-', '12px', '2', '1001']) {
      expect(() => {
        t.fontSize = value;
      }).toThrow();
    }
    for (const value of ['.', '-', '12px', '-201', '201']) {
      expect(() => {
        t.lineHeight = value;
      }).toThrow();
      expect(() => {
        t.letterSpacing = value;
      }).toThrow();
    }
  });

  test('text range numeric fields use the same validation', (ctx) => {
    const range = text(ctx, 'Hello').getRange(0, 5);
    expect(() => {
      range.fontSize = '12px';
    }).toThrow();
    expect(() => {
      range.lineHeight = '12px';
    }).toThrow();
    expect(() => {
      range.letterSpacing = '12px';
    }).toThrow();
  });

  test('text and range font fields reject missing fonts and variants', (ctx) => {
    const t = text(ctx, 'Hello');
    expect(() => {
      t.fontId = 'missing-font';
    }).toThrow();
    expect(() => {
      t.fontFamily = 'Missing Font Family';
    }).toThrow();
    expect(() => {
      t.fontVariantId = 'missing-variant';
    }).toThrow();

    const range = t.getRange(0, 5);
    expect(() => {
      range.fontId = 'missing-font';
    }).toThrow();
    expect(() => {
      range.fontFamily = 'Missing Font Family';
    }).toThrow();
    expect(() => {
      range.fontVariantId = 'missing-variant';
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
    expect(() => {
      t.getRange(0, 1).textTransform = 'UPPERCASE' as unknown as 'uppercase';
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

  describe('CJK', () => {
    test('a new text reports the CJK defaults', (ctx) => {
      const t = text(ctx, '日本語');
      expect(t.lang).toBeNull();
      expect(t.writingMode).toBe('horizontal-tb');
      expect(t.textOrientation).toBe('mixed');
      expect(t.lineAdjustment).toBe('push-in-first');
      expect(t.textCombineUpright).toBe('none');
      expect(t.textEmphasis).toBe('none');
      expect(t.textEmphasisPosition).toBe('auto');
      expect(t.textUnderlinePosition).toBe('auto');
      expect(t.warichu).toBe('none');
      expect(t.fontFeatures).toBe('none');
      expect(t.annotationClearance).toBe('none');
      expect(t.ruby).toBeNull();
      expect(t.rubyHidden).toBe(false);
      expect(t.rubySize).toBe('half');
      expect(t.rubyAlign).toBe('space-around');
      expect(t.rubyOverhang).toBe('auto');
      expect(t.rubyZhuyin).toBe(false);
      expect(t.rubySide).toBe('over');
    });

    test('a new range reports the CJK defaults', (ctx) => {
      const range = text(ctx, '日本語').getRange(0, 2);
      expect(range.lang).toBeNull();
      expect(range.textCombineUpright).toBe('none');
      expect(range.textEmphasis).toBe('none');
      expect(range.textEmphasisPosition).toBe('auto');
      expect(range.textUnderlinePosition).toBe('auto');
      expect(range.warichu).toBe('none');
      expect(range.fontFeatures).toBe('none');
      expect(range.annotationClearance).toBe('none');
      expect(range.ruby).toBeNull();
      expect(range.rubyHidden).toBe(false);
      expect(range.rubySize).toBe('half');
      expect(range.rubyAlign).toBe('space-around');
      expect(range.rubyOverhang).toBe('auto');
      expect(range.rubyZhuyin).toBe(false);
      expect(range.rubySide).toBe('over');
    });

    test('lang round-trips and is removed with an empty string or null', (ctx) => {
      const t = text(ctx, '香港的夜景');
      t.lang = 'zh-Hant-HK';
      expect(t.lang).toBe('zh-Hant-HK');
      t.lang = '';
      expect(t.lang).toBeNull();
      t.lang = 'ja';
      t.lang = null;
      expect(t.lang).toBeNull();
    });

    test('lang rejects a value that is not a language tag', (ctx) => {
      const t = text(ctx, '日本語');
      expect(() => {
        t.lang = 'not a tag';
      }).toThrow();
      expect(() => {
        t.getRange(0, 1).lang = 'ja_JP';
      }).toThrow();
      expect(t.lang).toBeNull();
    });

    test('range lang applies to the paragraphs it touches', (ctx) => {
      const t = text(ctx, '日本\n한국\n中文');
      t.getRange(3, 5).lang = 'ko';
      expect(t.getRange(0, 2).lang).toBeNull();
      expect(t.getRange(3, 4).lang).toBe('ko');
      expect(t.getRange(4, 5).lang).toBe('ko');
      expect(t.lang).toBe('mixed');
      t.getRange(0, t.characters.length).lang = 'ja';
      expect(t.lang).toBe('ja');
    });

    test('range align and direction apply to the paragraphs they touch', (ctx) => {
      const t = text(ctx, '日本語の文\n二行目');
      t.getRange(1, 2).align = 'right';
      t.getRange(1, 2).direction = 'rtl';
      expect(t.getRange(3, 5).align).toBe('right');
      expect(t.getRange(3, 5).direction).toBe('rtl');
      expect(t.getRange(6, 8).align).not.toBe('right');
      expect(t.align).toBe('mixed');
    });

    test('align accepts distribute on texts and ranges', (ctx) => {
      const t = text(ctx, '均等割付\n二行目');
      t.align = 'distribute';
      expect(t.align).toBe('distribute');
      t.getRange(5, 7).align = 'left';
      expect(t.getRange(0, 2).align).toBe('distribute');
      expect(t.getRange(5, 7).align).toBe('left');
    });

    test('Chinese annotation properties round-trip on texts', (ctx) => {
      const t = text(ctx, '美');
      t.lang = 'zh-Hant-TW';
      t.textEmphasisPosition = 'under-left';
      t.textUnderlinePosition = 'left';
      t.ruby = 'ㄇㄟˇ';
      t.rubyZhuyin = true;
      t.rubyHidden = true;
      expect(t.textEmphasisPosition).toBe('under-left');
      expect(t.textUnderlinePosition).toBe('left');
      expect(t.ruby).toBe('ㄇㄟˇ');
      expect(t.rubyZhuyin).toBe(true);
      expect(t.rubyHidden).toBe(true);
    });

    test('Chinese annotation properties round-trip on ranges', (ctx) => {
      const t = text(ctx, '香港的夜景很美');
      t.lang = 'zh-Hant-HK';
      const base = t.getRange(6, 7);
      base.ruby = 'ㄇㄟˇ';
      base.rubyZhuyin = true;
      base.rubyHidden = true;
      const marks = t.getRange(3, 5);
      marks.textEmphasis = 'filled-dot';
      marks.textEmphasisPosition = 'over-right';
      marks.textUnderlinePosition = 'under-right';
      expect(base.ruby).toBe('ㄇㄟˇ');
      expect(base.rubyZhuyin).toBe(true);
      expect(base.rubyHidden).toBe(true);
      expect(marks.textEmphasisPosition).toBe('over-right');
      expect(marks.textUnderlinePosition).toBe('under-right');
      expect(t.getRange(0, 2).rubyZhuyin).toBe(false);
    });

    test('every textCombineUpright value round-trips', (ctx) => {
      const t = text(ctx, '令和7年');
      for (const value of [
        'all',
        'digits',
        'digits2',
        'digits3',
        'none',
      ] as const) {
        t.textCombineUpright = value;
        expect(t.textCombineUpright).toBe(value);
        t.getRange(2, 3).textCombineUpright = value;
        expect(t.getRange(2, 3).textCombineUpright).toBe(value);
      }
    });

    test('a span property set on part of a text reads mixed', (ctx) => {
      const t = text(ctx, '日本語の文');
      t.getRange(0, 3).textEmphasis = 'open-sesame';
      t.getRange(0, 3).fontFeatures = 'palt';
      t.getRange(0, 3).rubySize = 'third';
      expect(t.textEmphasis).toBe('mixed');
      expect(t.fontFeatures).toBe('mixed');
      expect(t.rubySize).toBe('mixed');
      expect(t.getRange(0, 5).textEmphasis).toBe('mixed');
      expect(t.getRange(3, 5).textEmphasis).toBe('none');
    });

    test('textOrientation stays a value, not a mixed report', (ctx) => {
      const t = text(ctx, '縦書き\n二行目');
      t.writingMode = 'vertical-rl';
      t.textOrientation = 'upright';
      expect(t.writingMode).toBe('vertical-rl');
      expect(t.textOrientation).toBe('upright');
      t.textOrientation = 'mixed';
      expect(t.textOrientation).toBe('mixed');
    });

    test('a reading on part of a text makes the text ruby mixed', (ctx) => {
      const t = text(ctx, '紅葉の山');
      t.getRange(0, 2).ruby = 'もみじ';
      expect(t.getRange(0, 2).ruby).toBe('もみじ');
      expect(t.getRange(1, 2).ruby).toBe('もみじ');
      expect(t.getRange(2, 4).ruby).toBeNull();
      expect(t.ruby).toBe('mixed');
    });

    test('several readings can be added one after another', (ctx) => {
      const t = text(ctx, '紅葉の山');
      t.getRange(0, 2).ruby = 'もみじ';
      t.getRange(3, 4).ruby = 'やま';
      expect(t.getRange(0, 2).ruby).toBe('もみじ');
      expect(t.getRange(3, 4).ruby).toBe('やま');
      expect(t.getRange(2, 3).ruby).toBeNull();
    });

    test('a reading is changed or removed on the range it covers', (ctx) => {
      const t = text(ctx, '日本の山');
      const base = t.getRange(0, 2);
      base.ruby = 'にっぽん';
      base.ruby = 'にほん';
      expect(base.ruby).toBe('にほん');
      base.ruby = null;
      expect(base.ruby).toBeNull();

      const whole = text(ctx, '日本');
      whole.ruby = 'にほん';
      expect(whole.ruby).toBe('にほん');
      whole.ruby = '';
      expect(whole.ruby).toBeNull();
    });

    test('a reading or warichu note over several spans is rejected', (ctx) => {
      const t = text(ctx, '日本語の文');
      t.getRange(2, 3).textEmphasis = 'filled-dot';
      expect(() => {
        t.ruby = 'にほんごのぶん';
      }).toThrow('single text span');
      expect(() => {
        t.warichu = 'warichu';
      }).toThrow('single text span');
      expect(() => {
        t.getRange(0, 4).ruby = 'にほんごの';
      }).toThrow('single text span');
      expect(() => {
        t.getRange(1, 3).warichu = 'warichu';
      }).toThrow('single text span');
      expect(t.ruby).toBeNull();
      expect(t.warichu).toBe('none');
    });

    test('removing warichu or a reading is allowed over several spans', (ctx) => {
      const t = text(ctx, '日本語の文');
      t.getRange(0, 2).warichu = 'warichu';
      t.getRange(3, 5).ruby = 'のぶん';
      t.warichu = 'none';
      t.ruby = null;
      expect(t.warichu).toBe('none');
      expect(t.ruby).toBeNull();
    });

    test('assigning characters drops the readings and notes', (ctx) => {
      const t = text(ctx, '紅葉');
      t.ruby = 'もみじ';
      t.characters = '秋の空\n二行目';
      expect(t.ruby).toBeNull();

      const note = text(ctx, '割注');
      note.warichu = 'warichu';
      note.characters = '新しい文';
      expect(note.warichu).toBe('none');
    });

    test('CJK properties reject unknown values', (ctx) => {
      const t = text(ctx, '日本語');
      const range = t.getRange(0, 1);
      const spanProps = [
        'textCombineUpright',
        'textEmphasis',
        'textEmphasisPosition',
        'textUnderlinePosition',
        'warichu',
        'fontFeatures',
        'annotationClearance',
        'rubySize',
        'rubyAlign',
        'rubyOverhang',
        'rubySide',
        'rubyHidden',
        'rubyZhuyin',
      ];
      const textProps = [
        'writingMode',
        'textOrientation',
        'lineAdjustment',
        ...spanProps,
      ];
      for (const prop of textProps) {
        expect(() => {
          (t as unknown as Record<string, unknown>)[prop] = 'bogus';
        }).toThrow();
      }
      for (const prop of spanProps) {
        expect(() => {
          (range as unknown as Record<string, unknown>)[prop] = 'bogus';
        }).toThrow();
      }
      expect(() => {
        (range as unknown as Record<string, unknown>)['ruby'] = 42;
      }).toThrow();
      expect(t.writingMode).toBe('horizontal-tb');
      expect(t.textEmphasis).toBe('none');
      expect(range.ruby).toBeNull();
    });
  });
});
