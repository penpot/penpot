import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Shape } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

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

  test('component() returns the library component', (ctx) => {
    const inst = instanceOf(ctx);
    const comp = inst.component();
    expect(comp).not.toBeNull();
    if (comp) {
      expect(typeof comp.id).toBe('string');
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

    const mainColor = main.fills?.[0]?.fillColor;
    inst.fills = [{ fillColor: '#FF0000', fillOpacity: 1 }];
    // The override applied (fill getter normalizes to lowercase).
    expect(inst.fills?.[0]?.fillColor?.toLowerCase()).toBe('#ff0000');

    inst.resetOverrides();
    expect(inst.fills?.[0]?.fillColor).toBe(mainColor);
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
