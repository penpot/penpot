import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type {
  Board,
  LibraryComponent,
  LibraryVariantComponent,
  VariantContainer,
} from '@penpot/plugin-types';
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

interface ComponentWithMain {
  comp: LibraryComponent;
  main: Board;
}

function componentWithMain(ctx: TestContext): ComponentWithMain {
  const rect = ctx.penpot.createRectangle();
  ctx.board.appendChild(rect);
  const comp = ctx.penpot.library.local.createComponent([rect]);
  return { comp, main: comp.mainInstance() as Board };
}

function variantComponentIds(container: VariantContainer): string[] {
  const components = container.variants?.variantComponents() ?? [];
  return components.map((c) => c.id);
}

function sameIds(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((id, i) => id === b[i]);
}

// Polls until the condition holds. On timeout it returns normally so the
// assertion that follows reports the actual mismatch.
async function waitFor(
  condition: () => boolean,
  attempts = 100,
): Promise<void> {
  for (let i = 0; i < attempts; i++) {
    try {
      if (condition()) return;
    } catch {
      // Treat a throwing condition as not-yet-ready and keep polling.
    }
    await new Promise<void>((resolve) => setTimeout(resolve, 20));
  }
}

// transformInVariant is async, so `variants` is only populated after a tick.
async function variantComponent(
  ctx: TestContext,
): Promise<LibraryVariantComponent> {
  const rect = ctx.penpot.createRectangle();
  ctx.board.appendChild(rect);
  const comp = ctx.penpot.library.local.createComponent([rect]);
  comp.transformInVariant();
  await waitFor(() => comp.isVariant());
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

  // The order of the resulting variant components must follow the input order,
  // so that variant property values can be paired positionally (#10506).
  test('createVariantFromComponents preserves the input component order', async (ctx) => {
    const a = componentWithMain(ctx);
    const b = componentWithMain(ctx);
    const c = componentWithMain(ctx);
    const container = ctx.penpot.createVariantFromComponents([
      c.main,
      a.main,
      b.main,
    ]);
    const expected = [c.comp.id, a.comp.id, b.comp.id];
    await waitFor(() => sameIds(variantComponentIds(container), expected));
    expect(variantComponentIds(container)).toEqual(expected);
  });

  test('combineAsVariants orders components head first, then the given ids', async (ctx) => {
    const a = componentWithMain(ctx);
    const b = componentWithMain(ctx);
    const c = componentWithMain(ctx);
    const container = a.main.combineAsVariants([c.main.id, b.main.id]);
    const expected = [a.comp.id, c.comp.id, b.comp.id];
    await waitFor(() => sameIds(variantComponentIds(container), expected));
    expect(variantComponentIds(container)).toEqual(expected);
  });

  test('combineAsVariants deduplicates repeated ids', async (ctx) => {
    const a = componentWithMain(ctx);
    const b = componentWithMain(ctx);
    const container = a.main.combineAsVariants([
      b.main.id,
      a.main.id,
      b.main.id,
    ]);
    const expected = [a.comp.id, b.comp.id];
    await waitFor(() => sameIds(variantComponentIds(container), expected));
    expect(variantComponentIds(container)).toEqual(expected);
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
    // A well-formed variant carries no error (get only; no runtime setter).
    expect(vc.variantError ?? undefined).toBeUndefined();

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
      const before = v.properties.length;
      v.addProperty();
      await waitFor(() => v.properties.length > before);
      const count = v.properties.length;
      expect(count).toBeGreaterThan(0);

      v.renameProperty(0, 'Size');
      v.removeProperty(count - 1);
      await waitFor(() => v.properties.length < count);
      expect(v.properties.length).toBe(count - 1);
    }
  });

  test('addVariant and setVariantProperty mutate the variant', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      const before = v.variantComponents().length;
      vc.addVariant();
      await waitFor(() => v.variantComponents().length > before);
      expect(v.variantComponents().length).toBeGreaterThan(before);

      if (v.properties.length > 0) {
        vc.setVariantProperty(0, 'large');
      }
    }
  });

  test('switchVariant on a variant instance does not throw', async (ctx) => {
    const vc = await variantComponent(ctx);
    // Add a second variant so there is another value to switch to.
    vc.addVariant();
    await waitFor(() => (vc.variants?.variantComponents().length ?? 0) > 1);

    const instance = vc.instance();
    ctx.board.appendChild(instance);
    // Valid args (nat-int pos, string value): switches to the nearest variant
    // with that value at the property position, or no-ops — never throws.
    expect(() => instance.switchVariant(0, 'large')).not.toThrow();
  });

  // Community report (forum #10700, issue #3): switchVariant on an instance
  // living inside a cloned board was said to hang the plugin bridge
  // indefinitely. Did not reproduce; kept as a regression pin.
  test('switchVariant works on an instance inside a cloned board', async (ctx) => {
    const vc = await variantComponent(ctx);
    vc.addVariant();
    await waitFor(() => (vc.variants?.variantComponents().length ?? 0) > 1);

    const wrapper = ctx.penpot.createBoard();
    ctx.board.appendChild(wrapper);
    wrapper.resize(400, 300);
    const instance = vc.instance();
    wrapper.appendChild(instance);

    const cloned = wrapper.clone() as Board;
    ctx.board.appendChild(cloned);
    const clonedInstance = cloned.children.find((s) => s.isComponentInstance());
    expect(clonedInstance).toBeDefined();
    if (clonedInstance) {
      expect(() => clonedInstance.switchVariant(0, 'large')).not.toThrow();
    }
  });

  test('utils.types.isVariantComponent identifies a variant component', async (ctx) => {
    const vc = await variantComponent(ctx);
    expect(ctx.penpot.utils.types.isVariantComponent(vc)).toBeTruthy();
  });

  test('variantError stores an invalid variant name', async (ctx) => {
    const mainA = componentMain(ctx);
    const mainB = componentMain(ctx);
    const container = ctx.penpot.createVariantFromComponents([mainA, mainB]);
    await waitFor(() => {
      const v = container.variants;
      return !!v && v.variantComponents().length > 0;
    });

    const variants = container.variants;
    expect(variants).not.toBeNull();
    if (variants) {
      const comps = variants.variantComponents();
      expect(comps.length).toBeGreaterThan(0);

      // Renaming a variant's main instance to something that doesn't follow
      // the "[property]=[value], …" structure surfaces the rejected name in
      // variantError instead of applying it.
      const invalidName = 'not a valid variant structure';
      comps[0].mainInstance().name = invalidName;
      await waitFor(() =>
        variants
          .variantComponents()
          .some(
            (c) => (c as LibraryVariantComponent).variantError === invalidName,
          ),
      );

      const after = variants.variantComponents();
      expect(
        after.map((c) => (c as LibraryVariantComponent).variantError),
      ).toContain(invalidName);
    }
  });

  // Blocked by API bug: setting `path` on a variant component renames only that
  // component (via rename-component), leaving its variant container and main
  // instance untouched. For a variant, path/name are shared across the whole
  // group, so the container's name must stay equal to the component's full
  // path/name. A plain `vc.path === 'Group'` round-trip would pass even with the
  // bug (the component *is* renamed); the container is the tell. `.name` routes
  // through the variant-aware rename; `.path` must do the same.
  test('setting path on a variant component renames the whole variant', async (ctx) => {
    const vc = await variantComponent(ctx);
    const container = vc.mainInstance().parent as VariantContainer;

    // mergePathItem(path, name): "path / name" when both present, else the leaf.
    // Reconstructed from the component so the assertion holds whether the fix
    // propagates the path or strips it (as the variant rename currently does).
    const fullName = () => (vc.path ? `${vc.path} / ${vc.name}` : vc.name);

    vc.path = 'Group';
    await waitFor(() => container.name === fullName());
    expect(container.name).toBe(fullName());
  });

  // ---------------------------------------------------------------------------
  // Edge cases. Out-of-bounds property positions and degenerate
  // container input should be rejected.
  // ---------------------------------------------------------------------------
  // createVariantFromComponents([]) is rejected (validated). The positional
  // property ops bounds-check `pos`; an out-of-range index is rejected rather
  // than reaching the data layer (where it would surface as an error toast).
  test('createVariantFromComponents of an empty array throws', (ctx) => {
    expect(() => ctx.penpot.createVariantFromComponents([])).toThrow();
  });

  test('removeProperty out of bounds throws', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      expect(() => v.removeProperty(999)).toThrow();
    }
  });

  test('renameProperty out of bounds throws', async (ctx) => {
    const vc = await variantComponent(ctx);
    const v = vc.variants;
    expect(v).not.toBeNull();
    if (v) {
      expect(() => v.renameProperty(999, 'Nope')).toThrow();
    }
  });

  test('setVariantProperty out of bounds throws', async (ctx) => {
    const vc = await variantComponent(ctx);
    expect(() => vc.setVariantProperty(999, 'large')).toThrow();
  });
});
