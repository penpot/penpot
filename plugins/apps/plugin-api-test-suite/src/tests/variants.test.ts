import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Board, LibraryVariantComponent } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Variants.
// A standard component is created and transformed into a variant; the resulting
// VariantComponent exposes the Variants interface. Variant containers are also
// built from main-instance boards via createVariantFromComponents.

function componentMain(ctx: TestContext): Board {
  const rect = ctx.penpot.createRectangle();
  ctx.board.appendChild(rect);
  const comp = ctx.penpot.library.local.createComponent([rect]);
  return comp.mainInstance() as Board;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// transformInVariant is async, so `variants` is only populated after a tick.
async function variantComponent(
  ctx: TestContext,
): Promise<LibraryVariantComponent> {
  const rect = ctx.penpot.createRectangle();
  ctx.board.appendChild(rect);
  const comp = ctx.penpot.library.local.createComponent([rect]);
  comp.transformInVariant();
  await sleep(400);
  return comp as LibraryVariantComponent;
}

describe('Variants', () => {
  test('transformInVariant turns a component into a variant', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    const comp = ctx.penpot.library.local.createComponent([rect]);
    comp.transformInVariant();
    expect(comp.isVariant()).toBe(true);
  });

  test('createVariantFromComponents builds a variant container', (ctx) => {
    const mainA = componentMain(ctx);
    const mainB = componentMain(ctx);
    const container = ctx.penpot.createVariantFromComponents([mainA, mainB]);
    expect(container).toBeDefined();
    expect(container.isVariantContainer()).toBe(true);
    expect(container.variants).not.toBeNull();
  });

  test('combineAsVariants builds a variant container', (ctx) => {
    const mainA = componentMain(ctx);
    const mainB = componentMain(ctx);
    const container = mainA.combineAsVariants([mainB.id]);
    expect(container).toBeDefined();
    expect(container.isVariantContainer()).toBe(true);
  });

  test('variant component exposes variant props and Variants', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    const comp = ctx.penpot.library.local.createComponent([rect]);
    comp.transformInVariant();
    expect(comp.isVariant()).toBe(true);

    const variantComp = comp as LibraryVariantComponent;
    expect(variantComp.variants).not.toBeNull();
    expect(typeof variantComp.variantProps).toBe('object');

    const variants = variantComp.variants;
    if (variants) {
      expect(typeof variants.id).toBe('string');
      expect(typeof variants.libraryId).toBe('string');
      expect(Array.isArray(variants.properties)).toBe(true);
      expect(Array.isArray(variants.variantComponents())).toBe(true);
    }
  });

  test('variant property can be added and read', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    const comp = ctx.penpot.library.local.createComponent([rect]);
    comp.transformInVariant();

    const variants = (comp as LibraryVariantComponent).variants;
    expect(variants).not.toBeNull();
    if (variants) {
      const before = variants.properties.length;
      variants.addProperty();
      expect(variants.properties.length).toBe(before + 1);
      variants.currentValues(variants.properties[0]);
    }
  });

  test('variant component exposes the Variants interface', async (ctx) => {
    const vc = await variantComponent(ctx);
    expect(vc.isVariant()).toBe(true);
    expect(typeof vc.variantProps).toBe('object');
    void vc.variantError; // get only (no runtime setter)

    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      expect(typeof v.id).toBe('string');
      expect(typeof v.libraryId).toBe('string');
      expect(Array.isArray(v.properties)).toBe(true);
      expect(Array.isArray(v.variantComponents())).toBe(true);
      if (v.properties.length > 0) {
        expect(Array.isArray(v.currentValues(v.properties[0]))).toBe(true);
      }
    }
  });

  test('variant properties can be added, renamed and removed', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      v.addProperty();
      await sleep(300);
      const count = v.properties.length;
      expect(count).toBeGreaterThan(0);

      v.renameProperty(0, 'Size');
      await sleep(300);
      v.removeProperty(count - 1);
      await sleep(300);
    }
  });

  test('addVariant and setVariantProperty mutate the variant', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      const before = v.variantComponents().length;
      vc.addVariant();
      await sleep(300);
      expect(v.variantComponents().length).toBeGreaterThan(before);

      if (v.properties.length > 0) {
        vc.setVariantProperty(0, 'large');
        await sleep(300);
      }
    }
  });

  test('switchVariant on a variant instance does not throw', async (ctx) => {
    const vc = await variantComponent(ctx);
    // Add a second variant so there is another value to switch to.
    vc.addVariant();
    await sleep(300);

    const instance = vc.instance();
    ctx.board.appendChild(instance);
    // Valid args (nat-int pos, string value): switches to the nearest variant
    // with that value at the property position, or no-ops — never throws.
    expect(() => instance.switchVariant(0, 'large')).not.toThrow();
  });

  test('utils.types.isVariantComponent identifies a variant component', async (ctx) => {
    const vc = await variantComponent(ctx);
    expect(ctx.penpot.utils.types.isVariantComponent(vc)).toBeTruthy();
  });

  // ---------------------------------------------------------------------------
  // Edge cases. Out-of-bounds property positions and degenerate
  // container input should be rejected.
  // ---------------------------------------------------------------------------
  // createVariantFromComponents([]) is rejected (validated), but the
  // positional property ops do not bounds-check `pos`; an out-of-range index
  // is a no-op rather than an error. These pin the current behaviour
  // (bounds-checking the position is a candidate for future hardening).
  test('createVariantFromComponents of an empty array throws', (ctx) => {
    expect(() => ctx.penpot.createVariantFromComponents([])).toThrow();
  });

  test('removeProperty out of bounds is a no-op (not rejected)', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      expect(() => v.removeProperty(999)).not.toThrow();
    }
  });

  test('renameProperty out of bounds is a no-op (not rejected)', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      expect(() => v.renameProperty(999, 'Nope')).not.toThrow();
    }
  });

  test('setVariantProperty out of bounds is a no-op (not rejected)', async (ctx) => {
    const vc = await variantComponent(ctx);
    expect(() => vc.setVariantProperty(999, 'large')).not.toThrow();
  });
});
