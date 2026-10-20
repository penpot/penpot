import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import { waitFor } from './wait';
import type { Board, Shape } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

const COPY_STRUCTURE_ERROR = 'Cannot change the structure of a component copy';

// Component instances and the ShapeBase component methods.
// A component is built from a rectangle and instantiated; the instance exposes
// the component predicates and navigation methods.

function makeComponent(ctx: TestContext) {
  const rect = ctx.penpot.createRectangle();
  ctx.board.appendChild(rect);
  return ctx.penpot.library.local.createComponent([rect]);
}

function instanceOf(ctx: TestContext): Shape {
  const comp = makeComponent(ctx);
  const inst = comp.instance();
  ctx.board.appendChild(inst);
  return inst;
}

function nestedComponentMain(ctx: TestContext): Board {
  const nested = makeComponent(ctx).instance();
  const host = ctx.penpot.createBoard();
  ctx.board.appendChild(host);
  host.appendChild(nested);
  return ctx.penpot.library.local
    .createComponent([host])
    .mainInstance() as Board;
}

function boardComponentMainWithChildren(ctx: TestContext): Board {
  const first = ctx.penpot.createRectangle();
  const second = ctx.penpot.createRectangle();
  const host = ctx.penpot.createBoard();
  ctx.board.appendChild(host);
  host.appendChild(first);
  host.appendChild(second);
  return ctx.penpot.library.local
    .createComponent([host])
    .mainInstance() as Board;
}

function componentMainFromClonedNestedMain(ctx: TestContext): Board {
  const cloned = nestedComponentMain(ctx).clone();
  return ctx.penpot.library.local
    .createComponent([cloned])
    .mainInstance() as Board;
}

describe('Component instances', () => {
  test('component predicates identify an instance', (ctx) => {
    const inst = instanceOf(ctx);
    expect(inst.isComponentInstance()).toBeTruthy();
    expect(inst.isComponentRoot()).toBeTruthy();
    expect(inst.isComponentHead()).toBeTruthy();
    // A fresh instance is a copy, not the main instance.
    expect(inst.isComponentMainInstance()).toBeFalsy();
    expect(inst.isComponentCopyInstance()).toBeTruthy();
    expect(inst.isVariantHead()).toBeFalsy();
  });

  test('component navigation methods return shapes', (ctx) => {
    const inst = instanceOf(ctx);
    expect(inst.componentRoot()).toBeDefined();
    expect(inst.componentHead()).toBeDefined();
    expect(inst.componentRefShape()).toBeDefined();
  });

  // Community report (forum #10700, issue #8): cloning a component's main
  // instance was said to yield a shape with null type/name that appendChild
  // silently drops. Did not reproduce (clone attaches to the same parent and
  // can be re-parented); kept as a regression pin.
  test('cloning a component main instance yields a valid shape', (ctx) => {
    const comp = makeComponent(ctx);
    const main = comp.mainInstance();
    const copy = main.clone();
    expect(copy.type).not.toBeNull();
    expect(copy.type).toBe(main.type);
    expect(copy.name).not.toBeNull();
    expect(copy.id).not.toBe(main.id);

    const target = ctx.penpot.createBoard();
    ctx.board.appendChild(target);
    target.resize(200, 200);
    const before = target.children.length;
    target.appendChild(copy);
    expect(target.children.length).toBe(before + 1);
    expect(target.children.some((s) => s.id === copy.id)).toBe(true);
  });

  test('appendChild rejects moving children out of a cloned main instance', (ctx) => {
    const main = nestedComponentMain(ctx);
    const cloned = main.clone() as Board;
    const child = cloned.children[0];
    expect(child).toBeDefined();

    expect(() => ctx.board.appendChild(child)).toThrow(COPY_STRUCTURE_ERROR);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('insertChild rejects moving children out of a cloned main instance', (ctx) => {
    const main = nestedComponentMain(ctx);
    const cloned = main.clone() as Board;
    const child = cloned.children[0];
    const target = ctx.penpot.createBoard();
    ctx.board.appendChild(target);
    expect(child).toBeDefined();

    expect(() => target.insertChild(0, child)).toThrow(COPY_STRUCTURE_ERROR);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('children assignment rejects reordering a cloned main instance', (ctx) => {
    const main = nestedComponentMain(ctx);
    const cloned = main.clone() as Board;

    expect(() => {
      cloned.children = [...cloned.children];
    }).toThrow(COPY_STRUCTURE_ERROR);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('setParentIndex rejects reordering children inside a component copy', (ctx) => {
    const main = boardComponentMainWithChildren(ctx);
    const copy = main.component()?.instance() as Board | undefined;
    expect(copy).toBeDefined();
    if (!copy) return;

    ctx.board.appendChild(copy);
    const child = copy.children[1];
    expect(child).toBeDefined();

    expect(() => child.setParentIndex(0)).toThrow(COPY_STRUCTURE_ERROR);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('group rejects children inside a cloned main instance', (ctx) => {
    const main = nestedComponentMain(ctx);
    const cloned = main.clone() as Board;
    const child = cloned.children[0];
    expect(child).toBeDefined();

    expect(() => ctx.penpot.group([child])).toThrow(COPY_STRUCTURE_ERROR);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('remove hides children inside a cloned main instance', (ctx) => {
    const main = nestedComponentMain(ctx);
    const cloned = main.clone() as Board;
    const child = cloned.children[0];
    expect(child).toBeDefined();

    child.remove();
    expect(child.hidden).toBe(true);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('detaching a cloned main instance makes child reparenting safe', (ctx) => {
    const main = nestedComponentMain(ctx);
    const cloned = main.clone() as Board;
    cloned.detach();
    const child = cloned.children[0];
    expect(child).toBeDefined();

    ctx.board.appendChild(child);
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('cloned nested main instances can be used as variant component sources', async (ctx) => {
    const mainA = componentMainFromClonedNestedMain(ctx);
    const mainB = componentMainFromClonedNestedMain(ctx);
    const container = ctx.penpot.createVariantFromComponents([mainA, mainB]);

    await waitFor(
      () => (container.variants?.variantComponents().length ?? 0) >= 2,
    );
    const errors = ctx.penpot.currentFile?.validate() ?? [];
    expect(errors.map((e) => e.code)).toEqual([]);
  });

  test('component() returns the library component', (ctx) => {
    const inst = instanceOf(ctx);
    const comp = inst.component();
    expect(comp).not.toBeNull();
    if (comp) {
      expect(typeof comp.id).toBe('string');
    }
  });

  test('restore restores a deleted component without changing the view', async (ctx) => {
    const originalPage = ctx.penpot.currentPage;
    expect(originalPage).not.toBeNull();
    if (!originalPage) return;

    const comp = makeComponent(ctx);
    const main = comp.mainInstance() as Board;
    const mainId = main.id;
    const childIds = main.children.map((child) => child.id);
    const inst = comp.instance();
    ctx.board.appendChild(inst);
    comp.remove();
    await ctx.penpot.waitForLayoutUpdate();
    expect(
      ctx.penpot.library.local.components.some((c) => c.id === comp.id),
    ).toBe(false);
    expect(originalPage.getShapeById(mainId)).toBeNull();

    const otherPage = ctx.penpot.createPage();
    try {
      await ctx.penpot.openPage(otherPage);
      const selected = ctx.penpot.createRectangle();
      (otherPage.root as Board).appendChild(selected);
      ctx.penpot.selection = [selected];
      ctx.penpot.viewport.zoom = 2;
      ctx.penpot.viewport.center = { x: 300, y: 200 };
      await ctx.penpot.waitForLayoutUpdate();

      const zoom = ctx.penpot.viewport.zoom;
      const center = ctx.penpot.viewport.center;
      const deleted = inst.component();
      expect(deleted).not.toBeNull();
      if (!deleted) return;

      deleted.restore();
      await waitFor(() =>
        ctx.penpot.library.local.components.some((c) => c.id === comp.id),
      );
      await ctx.penpot.waitForLayoutUpdate();

      expect(
        ctx.penpot.library.local.components.some((c) => c.id === comp.id),
      ).toBe(true);
      expect(originalPage.getShapeById(mainId)?.id).toBe(mainId);
      expect(
        (deleted.mainInstance() as Board).children.map((child) => child.id),
      ).toEqual(childIds);
      expect(ctx.penpot.currentPage?.id).toBe(otherPage.id);
      expect(ctx.penpot.selection.map((shape) => shape.id)).toEqual([
        selected.id,
      ]);
      expect(ctx.penpot.viewport.zoom).toBeCloseTo(zoom);
      expect(ctx.penpot.viewport.center.x).toBeCloseTo(center.x);
      expect(ctx.penpot.viewport.center.y).toBeCloseTo(center.y);
    } finally {
      await ctx.penpot.openPage(originalPage);
      otherPage.remove();
    }
  });

  test('detach turns an instance into a basic shape', (ctx) => {
    const inst = instanceOf(ctx);
    inst.detach();
    expect(inst.isComponentInstance()).toBeFalsy();
  });

  test('swapComponent replaces the instance component', (ctx) => {
    const inst = instanceOf(ctx);
    const other = makeComponent(ctx);
    inst.swapComponent(other);
    const comp = inst.component();
    expect(comp).not.toBeNull();
    if (comp) {
      expect(comp.id).toBe(other.id);
    }
  });

  test('resetOverrides restores a copy to its main component', (ctx) => {
    const comp = makeComponent(ctx);
    const main = comp.mainInstance();
    const inst = comp.instance();
    ctx.board.appendChild(inst);

    const mainFill = main.fills?.[0];
    const mainColor =
      typeof mainFill === 'string' ? mainFill : mainFill?.fillColor;
    inst.fills = [{ fillColor: '#FF0000', fillOpacity: 1 }];
    // The override applied (fill getter normalizes to lowercase).
    const overrideFill = inst.fills?.[0];
    const overrideColor =
      typeof overrideFill === 'string' ? overrideFill : overrideFill?.fillColor;
    expect(overrideColor?.toLowerCase()).toBe('#ff0000');

    inst.resetOverrides();
    const resetFill = inst.fills?.[0];
    expect(
      typeof resetFill === 'string' ? resetFill : resetFill?.fillColor,
    ).toBe(mainColor);
  });

  test('resetOverrides on a plain shape throws', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(() => rect.resetOverrides()).toThrow();
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests exercise the component methods on shapes
  // that are not component instances (documented null/self returns, invalid
  // swap target); the "success" test checks instance independence.
  // ---------------------------------------------------------------------------
  test('component() on a plain shape returns null', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(rect.component()).toBeNull();
  });

  test('componentRoot() on a plain shape returns null', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    // componentRoot (like component(), componentHead(), componentRefShape())
    // is null for a shape that is not part of any component. The d.ts
    // "returns itself" note applies to a shape that IS the root of a component.
    expect(rect.componentRoot()).toBeNull();
  });

  test('swapComponent with a non-component target throws', (ctx) => {
    const inst = instanceOf(ctx);
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(() =>
      inst.swapComponent(rect as unknown as ReturnType<typeof makeComponent>),
    ).toThrow();
  });

  test('swapComponent rejects a component nesting loop', (ctx) => {
    const leaf = ctx.penpot.createRectangle();
    ctx.board.appendChild(leaf);
    const inner = ctx.penpot.library.local.createComponent([leaf]);

    const wrapper = ctx.penpot.createBoard();
    ctx.board.appendChild(wrapper);
    wrapper.appendChild(inner.instance());
    const outer = ctx.penpot.library.local.createComponent([wrapper]);
    const nested = (outer.mainInstance() as Board).children.find((child) =>
      child.isComponentInstance(),
    );

    expect(nested).toBeDefined();
    if (nested) expect(() => nested.swapComponent(outer)).toThrow();
  });

  test('two instances of one component are independent but share the source', (ctx) => {
    const comp = makeComponent(ctx);
    const first = comp.instance();
    const second = comp.instance();
    ctx.board.appendChild(first);
    ctx.board.appendChild(second);

    first.name = 'first';
    second.name = 'second';
    expect(first.id).not.toBe(second.id);
    expect(first.name).toBe('first');
    expect(second.name).toBe('second');

    const c1 = first.component();
    const c2 = second.component();
    expect(c1).not.toBeNull();
    expect(c2).not.toBeNull();
    if (c1 && c2) {
      expect(c1.id).toBe(c2.id);
    }
  });

  test('detaching a copy breaks the component link', (ctx) => {
    const inst = instanceOf(ctx);
    inst.detach();
    expect(inst.isComponentInstance()).toBeFalsy();
  });
});

describe('Component assets', () => {
  test('duplicate creates an independent component with its annotation', async (ctx) => {
    const source = makeComponent(ctx);
    source.annotation = 'Button usage';
    const original = source.mainInstance() as Board;
    const duplicate = source.duplicate();
    await ctx.penpot.waitForLayoutUpdate();
    const main = duplicate.mainInstance() as Board;
    ctx.board.appendChild(main);

    expect(duplicate.id).not.toBe(source.id);
    expect(main.id).not.toBe(original.id);
    expect(main.isComponentMainInstance()).toBe(true);
    expect(main.component()?.id).toBe(duplicate.id);
    expect(main.children).toHaveLength(original.children.length);
    expect(main.children[0].id).not.toBe(original.children[0].id);
    expect(duplicate.annotation).toBe('Button usage');
    duplicate.name = 'Duplicate button';
    expect(source.name).not.toBe(duplicate.name);
  });

  test('annotations can be created, edited, and removed', async (ctx) => {
    const comp = makeComponent(ctx);
    expect(comp.annotation).toBeNull();
    comp.annotation = 'Use for primary actions';
    expect(comp.annotation).toBe('Use for primary actions');
    comp.annotation = 'Use for secondary actions';
    expect(comp.annotation).toBe('Use for secondary actions');
    expect(() => {
      comp.annotation = 42 as unknown as string;
    }).toThrow();
    expect(() => {
      comp.annotation = 'x'.repeat(301);
    }).toThrow();
    expect(comp.annotation).toBe('Use for secondary actions');
    comp.annotation = null;
    await ctx.penpot.waitForLayoutUpdate();
    expect(comp.annotation).toBeNull();
  });

  test('deletedComponents lists and restores a component without surviving copies', async (ctx) => {
    const comp = makeComponent(ctx);
    const mainId = comp.mainInstance().id;
    const page = ctx.penpot.currentPage;
    comp.remove();
    await ctx.penpot.waitForLayoutUpdate();
    expect(page?.getShapeById(mainId)).toBeNull();
    expect(
      ctx.penpot.library.local.components.some((c) => c.id === comp.id),
    ).toBe(false);

    const deleted = ctx.penpot.library.local.deletedComponents.find(
      (c) => c.id === comp.id,
    );
    expect(deleted).toBeDefined();
    if (!deleted) return;
    deleted.remove();
    deleted.restore();
    await ctx.penpot.waitForLayoutUpdate();
    expect(page?.getShapeById(mainId)?.id).toBe(mainId);
    expect(
      ctx.penpot.library.local.components.some((c) => c.id === comp.id),
    ).toBe(true);
    expect(
      ctx.penpot.library.local.deletedComponents.some((c) => c.id === comp.id),
    ).toBe(false);
  });

  test('updateMain applies a copy override to the main and other copies', async (ctx) => {
    const childColor = (board: Board) => {
      const fill = board.children[0].fills[0];
      return typeof fill === 'string' ? fill : fill?.fillColor;
    };
    const comp = makeComponent(ctx);
    const main = comp.mainInstance() as Board;
    const copy = comp.instance() as Board;
    const other = comp.instance() as Board;
    ctx.board.appendChild(copy);
    ctx.board.appendChild(other);
    copy.children[0].fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
    copy.updateMain();
    await ctx.penpot.waitForLayoutUpdate();
    await waitFor(() => childColor(main) === '#ff0000');
    expect(childColor(main)).toBe('#ff0000');
    expect(childColor(other)).toBe('#ff0000');

    main.children[0].fills = [{ fillColor: '#00ff00', fillOpacity: 1 }];
    await waitFor(() => childColor(copy) === '#00ff00');
    expect(childColor(copy)).toBe('#00ff00');
  });

  test('updateMain rejects plain shapes, main instances, and copy children', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    expect(() => rect.updateMain()).toThrow();
    const comp = makeComponent(ctx);
    expect(() => comp.mainInstance().updateMain()).toThrow();
    const copy = comp.instance() as Board;
    ctx.board.appendChild(copy);
    expect(() => copy.children[0].updateMain()).toThrow();
  });

  test('deleted components reject duplication, annotation edits, and updateMain', async (ctx) => {
    const comp = makeComponent(ctx);
    const copy = comp.instance();
    ctx.board.appendChild(copy);
    comp.remove();
    await ctx.penpot.waitForLayoutUpdate();
    expect(() => comp.duplicate()).toThrow('deleted component');
    expect(() => {
      comp.annotation = 'Usage';
    }).toThrow('deleted component');
    expect(() => copy.updateMain()).toThrow('main component');
  });

  test('updateMain rejects a copy on an inactive page and inside another copy', async (ctx) => {
    const main = nestedComponentMain(ctx);
    const outerCopy = main.component()?.instance() as Board;
    ctx.board.appendChild(outerCopy);
    expect(() => outerCopy.children[0].updateMain()).toThrow(
      'inside another component copy',
    );

    const original = ctx.penpot.currentPage;
    expect(original).not.toBeNull();
    if (!original) return;
    const page = ctx.penpot.createPage();
    try {
      await ctx.penpot.openPage(page);
      expect(() => outerCopy.updateMain()).toThrow('not currently active');
    } finally {
      await ctx.penpot.openPage(original);
      page.remove();
    }
  });

  test('component assets can be edited without activating their main instance page', async (ctx) => {
    const original = ctx.penpot.currentPage;
    expect(original).not.toBeNull();
    if (!original) return;
    const comp = makeComponent(ctx);
    const mainId = comp.mainInstance().id;
    const page = ctx.penpot.createPage();
    try {
      await ctx.penpot.openPage(page);
      const selected = ctx.penpot.createRectangle();
      (page.root as Board).appendChild(selected);
      ctx.penpot.selection = [selected];
      const zoom = ctx.penpot.viewport.zoom;
      const center = ctx.penpot.viewport.center;

      comp.annotation = 'Edited from another page';
      const duplicate = comp.duplicate();
      await ctx.penpot.waitForLayoutUpdate();
      expect(original.getShapeById(duplicate.mainInstance().id)).not.toBeNull();
      expect(comp.annotation).toBe('Edited from another page');
      duplicate.remove();
      comp.remove();
      await ctx.penpot.waitForLayoutUpdate();
      expect(original.getShapeById(mainId)).toBeNull();
      expect(ctx.penpot.currentPage?.id).toBe(page.id);
      expect(ctx.penpot.selection.map((shape) => shape.id)).toEqual([
        selected.id,
      ]);
      expect(ctx.penpot.viewport.zoom).toBeCloseTo(zoom);
      expect(ctx.penpot.viewport.center.x).toBeCloseTo(center.x);
      expect(ctx.penpot.viewport.center.y).toBeCloseTo(center.y);
    } finally {
      await ctx.penpot.openPage(original);
      page.remove();
    }
  });
});
