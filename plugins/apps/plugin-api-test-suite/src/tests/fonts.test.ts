import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Text } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Fonts.
// Exercises the FontsContext lookups, the Font/FontVariant metadata, applying a
// font to a text shape / range, and generateFontFaces. Fonts are self-provided
// from `fonts.all` so the tests don't depend on a specific font being present.

function text(ctx: TestContext, value = 'Hello Penpot'): Text {
  const t = ctx.penpot.createText(value);
  if (!t) throw new Error('createText returned null');
  ctx.board.appendChild(t);
  return t;
}

describe('Fonts', () => {
  test('fonts.all lists available fonts', (ctx) => {
    const all = ctx.penpot.fonts.all;
    expect(all.length).toBeGreaterThan(0);
  });

  test('a font exposes metadata and variants', (ctx) => {
    const font = ctx.penpot.fonts.all[0];
    expect(typeof font.name).toBe('string');
    expect(typeof font.fontId).toBe('string');
    expect(typeof font.fontFamily).toBe('string');
    expect(typeof font.fontVariantId).toBe('string');
    expect(typeof font.fontWeight).toBe('string');
    // fontStyle is optional (string or null).
    expect(font.fontStyle == null || typeof font.fontStyle === 'string').toBe(
      true,
    );

    expect(font.variants.length).toBeGreaterThan(0);
    const variant = font.variants[0];
    expect(typeof variant.name).toBe('string');
    expect(typeof variant.fontVariantId).toBe('string');
    expect(typeof variant.fontWeight).toBe('string');
    expect(
      variant.fontStyle === 'normal' || variant.fontStyle === 'italic',
    ).toBe(true);
  });

  test('findById returns the matching font', (ctx) => {
    const font = ctx.penpot.fonts.all[0];
    const found = ctx.penpot.fonts.findById(font.fontId);
    expect(found).not.toBeNull();
    expect(found && found.fontId).toBe(font.fontId);
  });

  test('findByName returns the matching font', (ctx) => {
    const font = ctx.penpot.fonts.all[0];
    const found = ctx.penpot.fonts.findByName(font.name);
    expect(found).not.toBeNull();
    expect(found && found.name).toBe(font.name);
  });

  test('findAllById and findAllByName return arrays', (ctx) => {
    const font = ctx.penpot.fonts.all[0];
    expect(ctx.penpot.fonts.findAllById(font.fontId).length).toBeGreaterThan(0);
    expect(ctx.penpot.fonts.findAllByName(font.name).length).toBeGreaterThan(0);
  });

  test('applyToText sets the font on a text shape', (ctx) => {
    const t = text(ctx);
    const font = ctx.penpot.fonts.all[0];
    font.applyToText(t);
    expect(t.fontId).toBe(font.fontId);
  });

  test('applyToRange sets the font on a text range', (ctx) => {
    const t = text(ctx, 'Hello Penpot');
    const font = ctx.penpot.fonts.all[0];
    const range = t.getRange(0, 5);
    font.applyToRange(range);
    expect(range.fontId).toBe(font.fontId);
  });

  test('generateFontFaces returns a css string', async (ctx) => {
    const t = text(ctx);
    const faces = await ctx.penpot.generateFontFaces([t]);
    expect(typeof faces).toBe('string');
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests assert the documented null returns for
  // unknown lookups; the "success" test applies a specific variant and reads it
  // back.
  // ---------------------------------------------------------------------------
  test('findById of an unknown id returns null', (ctx) => {
    const found = ctx.penpot.fonts.findById('definitely-not-a-font-id');
    expect(found).toBeNull();
  });

  test('findByName of an unknown name returns null', (ctx) => {
    const found = ctx.penpot.fonts.findByName('No Such Font Name 12345');
    expect(found).toBeNull();
  });

  test('findAllById of an unknown id returns an empty array', (ctx) => {
    expect(ctx.penpot.fonts.findAllById('definitely-not-a-font-id')).toEqual(
      [],
    );
  });

  test('applying a specific variant sets the variant on the text', (ctx) => {
    const t = text(ctx);
    // Prefer a font that has more than one variant so the chosen variant is
    // meaningful; fall back to the first font otherwise.
    const font =
      ctx.penpot.fonts.all.find((f) => f.variants.length > 1) ??
      ctx.penpot.fonts.all[0];
    const variant = font.variants[font.variants.length - 1];

    font.applyToText(t, variant);
    expect(t.fontId).toBe(font.fontId);
    expect(t.fontVariantId).toBe(variant.fontVariantId);
    expect(t.fontWeight).toBe(variant.fontWeight);
  });
});
