import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type {
  TokenCatalog,
  TokenColor,
  TokenSet,
  TokenShadow,
  TokenType,
  TokenTypography,
} from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Design tokens.
// The token catalog is reached through the local library. Sets/themes/tokens are
// self-provisioned; sets are created active so token references resolve.

function catalog(ctx: TestContext): TokenCatalog {
  return ctx.penpot.library.local.tokens;
}

function activeSet(ctx: TestContext, name: string): TokenSet {
  return catalog(ctx).addSet({ name, active: true });
}

// Names must be unique across runs too: sets/themes leak into the file (the
// API has no theme remove and set removal is best-effort), so a plain counter
// collides with leftovers from a previous run. Add a per-run random tag.
const runTag = Math.random().toString(36).slice(2, 8);
let counter = 0;
function unique(prefix: string): string {
  counter += 1;
  return `${prefix}-${runTag}-${counter}`;
}

/** Token application and theme/set wiring update the store asynchronously. */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe('Tokens', () => {
  describe('Catalog', () => {
    test('addSet creates a token set', (ctx) => {
      const cat = catalog(ctx);
      const set = cat.addSet({ name: unique('set'), active: true });
      expect(typeof set.id).toBe('string');
      expect(set.active).toBe(true);
      expect(cat.sets.length).toBeGreaterThan(0);
      expect(cat.getSetById(set.id)).toBeDefined();
    });

    test('addTheme creates a token theme', (ctx) => {
      const cat = catalog(ctx);
      const theme = cat.addTheme({ group: '', name: unique('theme') });
      expect(typeof theme.id).toBe('string');
      expect(cat.themes.length).toBeGreaterThan(0);
      expect(cat.getThemeById(theme.id)).toBeDefined();
    });
  });

  describe('Set', () => {
    test('name and active round-trip', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const newName = unique('renamed');
      set.name = newName;
      expect(set.name).toBe(newName);
      set.active = false;
      expect(set.active).toBe(false);
      set.toggleActive();
      expect(set.active).toBe(true);
    });

    test('addToken adds a token and lists it', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'color',
        name: unique('color.'),
        value: '#ff0000',
      });
      expect(typeof token.id).toBe('string');
      expect(set.tokens.length).toBeGreaterThan(0);
      expect(Array.isArray(set.tokensByType)).toBe(true);
      expect(set.getTokenById(token.id)).toBeDefined();
    });

    test('duplicate and remove a set', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const dup = set.duplicate();
      expect(dup).not.toBeNull();
      expect(dup.id).not.toBe(set.id);
      dup.remove();
    });

    // Invalid input — addToken must reject bad input.
    test('empty token name throws', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      expect(() =>
        set.addToken({ type: 'color', name: '', value: '#ff0000' }),
      ).toThrow();
    });

    test('duplicate token name in the same set throws', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const name = unique('color.dup');
      set.addToken({ type: 'color', name, value: '#ff0000' });
      expect(() =>
        set.addToken({ type: 'color', name, value: '#0000ff' }),
      ).toThrow();
    });

    test('opacity token value outside 0..1 throws', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      expect(() =>
        set.addToken({ type: 'opacity', name: unique('op.'), value: '2' }),
      ).toThrow();
    });

    test('invalid token type throws', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      expect(() =>
        set.addToken({
          type: 'not-a-type' as unknown as TokenType,
          name: unique('bad.'),
          value: '1',
        }),
      ).toThrow();
    });
  });

  describe('Theme', () => {
    test('group, name and active round-trip', (ctx) => {
      const theme = catalog(ctx).addTheme({ group: '', name: unique('theme') });
      theme.group = 'brand';
      theme.name = 'dark';
      expect(theme.group).toBe('brand');
      expect(theme.name).toBe('dark');
      theme.active = true;
      expect(theme.active).toBe(true);
      theme.toggleActive();
    });

    test('addSet and removeSet manage the theme sets', async (ctx) => {
      const cat = catalog(ctx);
      const theme = cat.addTheme({ group: '', name: unique('theme') });
      const set = cat.addSet({ name: unique('set'), active: false });
      theme.addSet(set);
      await sleep(300);
      expect(theme.activeSets.length).toBeGreaterThan(0);
      theme.removeSet(set);
    });

    test('duplicate and remove a theme', (ctx) => {
      const theme = catalog(ctx).addTheme({ group: '', name: unique('theme') });
      const dup = theme.duplicate();
      expect(dup.id).not.toBe(theme.id);
      dup.remove();
    });
  });

  describe('Token', () => {
    test('base properties round-trip', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'color',
        name: unique('color.'),
        value: '#00ff00',
      });
      token.description = 'a token';
      expect(token.description).toBe('a token');
      expect(typeof token.id).toBe('string');
      // resolvedValueString resolves against active sets.
      expect(token.resolvedValueString).toBeDefined();
    });

    test('color token exposes type and value', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'color',
        name: unique('color.'),
        value: '#123456',
      });
      expect(token.type).toBe('color');
      expect(token.value).toBe('#123456');
    });

    test('dimension and number tokens expose resolved values', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const dim = set.addToken({
        type: 'dimension',
        name: unique('dim.'),
        value: '16',
      });
      const num = set.addToken({
        type: 'rotation',
        name: unique('rot.'),
        value: '2',
      });
      expect(dim.type).toBe('dimension');
      expect(num.type).toBe('rotation');
      if (dim.type === 'dimension') {
        expect(dim.resolvedValue).toBeCloseTo(16, 0);
      }
    });

    test('applyToShapes applies a token to a shape', async (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'borderRadius',
        name: unique('radius.'),
        value: '8',
      });
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      token.applyToShapes([rect]);
      await sleep(300);
      expect(Object.keys(rect.tokens).length).toBeGreaterThan(0);
    });

    test('applyToSelected applies a token to the selection', async (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'opacity',
        name: unique('opacity.'),
        value: '0.5',
      });
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      ctx.penpot.selection = [rect];
      token.applyToSelected();
      await sleep(300);
      expect(Object.keys(rect.tokens).length).toBeGreaterThan(0);
    });

    test('applyToken applies a token through the shape', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'borderRadius',
        name: unique('radius.'),
        value: '12',
      });
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      rect.applyToken(token);
      expect(rect.tokens).toBeDefined();
    });

    test('duplicate and remove a token', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const token = set.addToken({
        type: 'color',
        name: unique('color.'),
        value: '#abcdef',
      });
      const dup = token.duplicate();
      expect(dup.id).not.toBe(token.id);
      dup.remove();
    });

    // Reference resolution — a token referencing another resolves transitively.
    test('a token referencing another token resolves transitively', (ctx) => {
      const set = activeSet(ctx, unique('set'));
      const baseName = unique('dim.base');
      set.addToken({ type: 'dimension', name: baseName, value: '16' });
      const ref = set.addToken({
        type: 'dimension',
        name: unique('dim.ref'),
        value: `{${baseName}}`,
      });
      if (ref.type === 'dimension') {
        expect(ref.resolvedValue).toBeCloseTo(16, 0);
      }
    });
  });
});

// Every token type and the composite value types.
describe('Token types', () => {
  const simpleCases: [TokenType, string, string][] = [
    ['borderRadius', '8', '12'],
    ['color', '#ff0000', '#00ff00'],
    ['dimension', '16', '24'],
    ['fontFamilies', 'Arial', 'Helvetica'],
    ['fontSizes', '14', '18'],
    ['fontWeights', '700', '400'],
    ['letterSpacing', '2', '3'],
    ['number', '3', '4'],
    ['opacity', '0.5', '0.8'],
    ['rotation', '45', '90'],
    ['sizing', '100', '120'],
    ['spacing', '8', '12'],
    ['borderWidth', '2', '3'],
    ['textCase', 'uppercase', 'lowercase'],
    ['textDecoration', 'underline', 'none'],
  ];

  for (const [type, value, value2] of simpleCases) {
    test(`${type} token exposes type, value and resolvedValue`, (ctx) => {
      const set = activeSet(ctx, unique('set'));
      // Cast to a concrete variant for property access; the recorder attributes
      // members to the real runtime type via the `type` discriminant.
      const token = set.addToken({
        type,
        name: unique(`${type}.`),
        value,
      }) as TokenColor;
      expect(typeof token.type).toBe('string');
      void token.value;
      token.value = value2;
      token.name = unique('renamed.');
      expect(typeof token.name).toBe('string');
      // Record the resolvedValue (get) target for every type. fontFamilies
      // returns the wrong shape (see the dedicated red test below), but reading
      // it no longer throws, so a plain read is enough here.
      void token.resolvedValue;
    });
  }

  // A fontFamilies token's `resolvedValue` is the resolved family list
  // (`string[] | undefined`, e.g. ['Arial']). The binding used to leak the raw
  // tokenscript list symbol; it now returns the documented array.
  test('fontFamilies token resolvedValue is the family list', (ctx) => {
    const set = activeSet(ctx, unique('set'));
    const token = set.addToken({
      type: 'fontFamilies',
      name: unique('fontFamilies.'),
      value: 'Arial',
    });
    const resolved = token.resolvedValue;
    expect(Array.isArray(resolved)).toBe(true);
    expect(resolved as unknown as string[]).toContain('Arial');
  });

  test('shadow token exposes its composite value', (ctx) => {
    const set = activeSet(ctx, unique('set'));
    const token = set.addToken({
      type: 'shadow',
      name: unique('shadow.'),
      value: {
        color: '#000000',
        inset: 'false',
        offsetX: '1',
        offsetY: '2',
        spread: '0',
        blur: '4',
      },
    }) as TokenShadow;
    expect(token.type).toBe('shadow');

    // Round-trip the value (covers TokenShadow.value get + set) without changing
    // it — the setter validates against the token's value schema, so assigning
    // back exactly what the getter returned is guaranteed valid.
    const v = token.value;
    token.value = v;

    if (typeof v !== 'string' && v.length > 0) {
      const sv = v[0];
      void sv.color;
      void sv.inset;
      void sv.offsetX;
      void sv.offsetY;
      void sv.spread;
      void sv.blur;
      sv.color = '#111111';
      sv.inset = 'true';
      sv.offsetX = '3';
      sv.offsetY = '4';
      sv.spread = '1';
      sv.blur = '5';
    }

    // resolvedValue resolves the composite into a TokenShadowValue[]; each entry
    // exposes the shadow members with their resolved (unit-converted) values.
    const rv = token.resolvedValue;
    expect(Array.isArray(rv)).toBe(true);
    expect(rv).toBeDefined();
    if (rv && rv.length > 0) {
      const s = rv[0];
      expect(s.color).toBe('#000000');
      expect(s.inset).toBe(false);
      expect(s.offsetX).toBeCloseTo(1, 0);
      expect(s.offsetY).toBeCloseTo(2, 0);
      expect(s.spread).toBeCloseTo(0, 0);
      expect(s.blur).toBeCloseTo(4, 0);
      // Exercise the writable members (records the set targets).
      s.color = '#222222';
      s.inset = true;
      s.offsetX = 9;
      s.offsetY = 8;
      s.spread = 2;
      s.blur = 6;
    }
  });

  test('typography token exposes its composite value', (ctx) => {
    const set = activeSet(ctx, unique('set'));
    const token = set.addToken({
      type: 'typography',
      name: unique('typo.'),
      value: {
        letterSpacing: '1',
        fontFamilies: 'Arial',
        fontSizes: '14',
        fontWeight: '400',
        lineHeight: '1.2',
        textCase: 'none',
        textDecoration: 'none',
      },
    }) as TokenTypography;
    expect(token.type).toBe('typography');

    const v = token.value;
    if (typeof v !== 'string') {
      void v.letterSpacing;
      void v.fontFamilies;
      void v.fontSizes;
      void v.fontWeight;
      void v.lineHeight;
      void v.textCase;
      void v.textDecoration;
      v.letterSpacing = '2';
      v.fontFamilies = 'Helvetica';
      v.fontSizes = '16';
      v.fontWeight = '700';
      v.lineHeight = '1.5';
      v.textCase = 'uppercase';
      v.textDecoration = 'underline';
    }

    // resolvedValue resolves the composite into a TokenTypographyValue[]; each
    // entry exposes the typography members with their resolved (unit-converted)
    // values.
    const rv = token.resolvedValue;
    expect(Array.isArray(rv)).toBe(true);
    expect(rv).toBeDefined();
    if (rv && rv.length > 0) {
      const t = rv[0];
      expect(t.fontSizes).toBeCloseTo(14, 0);
      expect(t.letterSpacing).toBeCloseTo(1, 0);
      expect(t.lineHeight).toBeCloseTo(1.2, 1);
      expect(Array.isArray(t.fontFamilies)).toBe(true);
      expect(t.fontFamilies).toContain('Arial');
      expect(typeof t.fontWeights).toBe('string');
      expect(t.textCase).toBe('none');
      expect(t.textDecoration).toBe('none');
      // Exercise the writable members (records the set targets).
      t.letterSpacing = 3;
      t.fontFamilies = ['Helvetica'];
      t.fontSizes = 18;
      t.fontWeights = '500';
      t.lineHeight = 2;
      t.textCase = 'lowercase';
      t.textDecoration = 'line-through';
    }

    token.value = {
      letterSpacing: '2',
      fontFamilies: 'Helvetica',
      fontSizes: '16',
      fontWeight: '700',
      lineHeight: '1.5',
      textCase: 'uppercase',
      textDecoration: 'underline',
    };
  });

  test('a token set can be removed', (ctx) => {
    const cat = catalog(ctx);
    const set = cat.addSet({ name: unique('set'), active: true });
    set.remove();
  });

  test('theme externalId, activeSets and removeSet', (ctx) => {
    const cat = catalog(ctx);
    const theme = cat.addTheme({ group: '', name: unique('theme') });
    const set = cat.addSet({ name: unique('set'), active: false });
    void theme.externalId;
    // theme.activeSets has no runtime setter (declared writable) — API bug.
    void theme.activeSets;
    theme.addSet(set);
    theme.removeSet(set);
    expect(typeof theme.id).toBe('string');
  });
});
