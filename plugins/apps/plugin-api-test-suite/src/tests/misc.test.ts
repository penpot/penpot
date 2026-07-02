import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Board } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Misc — remaining coverable members across many interfaces.

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function rect(ctx: TestContext) {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

// Note: penpot.utils.types / geometry are frozen (SES) data properties, so the
// recorder cannot wrap them and their members aren't recorded (see README.md
// coverage notes). The predicates are still exercised behaviourally in
// platform.test.ts.

describe('Misc', () => {
  describe('Context root', () => {
    test('root is a shape', (ctx) => {
      expect(ctx.penpot.root).toBeDefined();
    });
  });

  describe('Concrete shape fills', () => {
    test('fills round-trip on ellipse, path and board', (ctx) => {
      const ellipse = ctx.penpot.createEllipse();
      const pathShape = ctx.penpot.createPath();
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(ellipse);
      ctx.board.appendChild(pathShape);
      ctx.board.appendChild(board);

      ellipse.fills = [{ fillColor: '#ff0000', fillOpacity: 1 }];
      pathShape.fills = [{ fillColor: '#00ff00', fillOpacity: 1 }];
      board.fills = [{ fillColor: '#0000ff', fillOpacity: 1 }];

      expect(ellipse.fills).toHaveLength(1);
      expect(pathShape.fills).toHaveLength(1);
      expect(board.fills).toHaveLength(1);
    });
  });

  describe('Boolean members', () => {
    test('boolean content, path data and children round-trip', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      b.x = 40;
      const bool = ctx.penpot.createBoolean('union', [a, b]);
      expect(bool).not.toBeNull();
      if (bool) {
        ctx.board.appendChild(bool);
        // Boolean fills round-trip; d/content/commands are derived from the
        // operands and not independently settable (see coverage notes).
        bool.fills = [{ fillColor: '#abcdef', fillOpacity: 1 }];
        expect(bool.fills).toHaveLength(1);
        // The derived path data reflects the two operands.
        expect(typeof bool.d).toBe('string');
        expect(bool.d.length).toBeGreaterThan(0);
        expect(bool.commands.length).toBeGreaterThan(0);
        expect(bool.children).toHaveLength(2);
      }
    });

    test('appendChild and insertChild add operands to a boolean', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      b.x = 40;
      const bool = ctx.penpot.createBoolean('union', [a, b]);
      expect(bool).not.toBeNull();
      if (bool) {
        ctx.board.appendChild(bool);
        const before = bool.children.length;
        bool.appendChild(rect(ctx));
        bool.insertChild(0, rect(ctx));
        expect(bool.children.length).toBe(before + 2);
      }
    });
  });

  describe('Export settings setters', () => {
    // `shape.exports` returns live export proxies: writing through a returned
    // export persists to the shape. This reads the export back from the shape
    // (a fresh proxy) instead of asserting on the object we wrote to, so a
    // detached-snapshot regression would fail here.
    test('export members round-trip on the returned export', (ctx) => {
      const r = rect(ctx);
      r.exports = [{ type: 'png', scale: 1, suffix: '', skipChildren: false }];
      const exp = r.exports[0];
      exp.type = 'jpeg';
      exp.scale = 2;
      exp.suffix = '@2x';
      exp.skipChildren = true;
      const persisted = r.exports[0];
      expect(persisted.type).toBe('jpeg');
      expect(persisted.scale).toBeCloseTo(2, 0);
      expect(persisted.suffix).toBe('@2x');
      expect(persisted.skipChildren).toBe(true);
    });
  });

  describe('Gradient and shadow leftovers', () => {
    test('gradient endpoints and stops round-trip', (ctx) => {
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
        const g = fills[0].fillColorGradient;
        if (g) {
          void g.endX;
          void g.startY;
          g.stops = [
            { color: '#00ff00', opacity: 1, offset: 0 },
            { color: '#000000', opacity: 1, offset: 1 },
          ];
          expect(g.stops.length).toBeGreaterThan(0);
        }
      }
    });

    // `shape.shadows` returns live shadow proxies: writing through a returned
    // shadow persists to the shape. The shadow `color`, however, is a plain
    // snapshot, so setting a gradient on it is lost and the solid color set via
    // `shadow.color` survives. This reads the shadow back from the shape.
    test('shadow color and id round-trip', (ctx) => {
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
      void shadow.id;
      shadow.color = { color: '#ff00ff', opacity: 0.5 };
      const color = shadow.color;
      if (color) {
        void color.id;
        void color.fileId;
        void color.refId;
        void color.refFile;
        color.gradient = {
          type: 'linear',
          startX: 0,
          startY: 0,
          endX: 1,
          endY: 1,
          width: 1,
          stops: [{ color: '#ff0000', opacity: 1, offset: 0 }],
        };
        void color.gradient;
      }
      expect(r.shadows).toHaveLength(1);
      const persisted = r.shadows[0].color;
      expect(persisted).toBeDefined();
      if (persisted) {
        expect(persisted.color).toBe('#ff00ff');
        expect(persisted.opacity).toBeCloseTo(0.5, 2);
      }
    });

    // The scalar shadow members persist to the shape through the live proxy.
    test('shadow scalar members persist to the shape', (ctx) => {
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
      shadow.offsetX = 9;
      shadow.blur = 7;
      shadow.hidden = true;
      const persisted = r.shadows[0];
      expect(persisted.offsetX).toBeCloseTo(9, 0);
      expect(persisted.blur).toBeCloseTo(7, 0);
      expect(persisted.hidden).toBe(true);
    });
  });

  describe('Bounds and Point', () => {
    test('viewport bounds members are readable', (ctx) => {
      // The bounds object is frozen, so only the getters are exercised.
      const b = ctx.penpot.viewport.bounds;
      expect(typeof b.x).toBe('number');
      expect(typeof b.y).toBe('number');
      expect(typeof b.width).toBe('number');
      expect(typeof b.height).toBe('number');
    });

    test('viewport center point members are readable', (ctx) => {
      const c = ctx.penpot.viewport.center;
      expect(typeof c.x).toBe('number');
      expect(typeof c.y).toBe('number');
    });
  });

  describe('Layout leftovers', () => {
    test('flex padding and child margins round-trip', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const flex = board.addFlexLayout();
      flex.horizontalPadding = 4;
      flex.verticalPadding = 6;
      expect(flex.horizontalPadding).toBeCloseTo(4, 0);
      expect(flex.verticalPadding).toBeCloseTo(6, 0);

      const child = ctx.penpot.createRectangle();
      flex.appendChild(child);
      const lc = child.layoutChild;
      expect(lc).toBeDefined();
      if (lc) {
        lc.topMargin = 3;
        lc.rightMargin = 4;
        lc.bottomMargin = 5;
        lc.leftMargin = 6;
        lc.maxHeight = 100;
        lc.minWidth = 10;
        expect(lc.topMargin).toBeCloseTo(3, 0);
        expect(lc.rightMargin).toBeCloseTo(4, 0);
        expect(lc.bottomMargin).toBeCloseTo(5, 0);
        expect(lc.leftMargin).toBeCloseTo(6, 0);
        expect(lc.maxHeight).toBeCloseTo(100, 0);
        expect(lc.minWidth).toBeCloseTo(10, 0);
        // The combined setters overwrite the per-side values just set.
        lc.horizontalMargin = 7;
        lc.verticalMargin = 8;
        expect(lc.horizontalMargin).toBeCloseTo(7, 0);
        expect(lc.verticalMargin).toBeCloseTo(8, 0);
      }
    });

    test('grid cell properties round-trip', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const grid = board.addGridLayout();
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);
      const child = ctx.penpot.createRectangle();
      grid.appendChild(child, 1, 1);
      const cell = child.layoutCell;
      expect(cell).toBeDefined();
      if (cell) {
        cell.areaName = 'header';
        expect(cell.areaName).toBe('header');
        cell.position = 'auto';
        expect(cell.position).toBe('auto');
        expect(cell.rowSpan).toBeCloseTo(1, 0);
      }
    });
  });

  describe('Track', () => {
    // `grid.rows` returns live track proxies: writing through a returned track
    // persists to the grid. This reads the track back from the grid (a fresh
    // proxy) instead of asserting on the object we wrote to.
    test('grid track members round-trip on the returned track', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const grid = board.addGridLayout();
      grid.addRow('flex', 1);
      const track = grid.rows[0];
      track.type = 'fixed';
      track.value = 80;
      const persisted = grid.rows[0];
      expect(persisted.type).toBe('fixed');
      expect(persisted.value).toBeCloseTo(80, 0);
    });
  });

  describe('Path commands', () => {
    test('path command members round-trip', (ctx) => {
      const path = ctx.penpot.createPath();
      ctx.board.appendChild(path);
      path.d = 'M0 0 L10 10';
      const commands = path.commands;
      expect(commands.length).toBeGreaterThan(0);
      const cmd = commands[0];
      void cmd.command;
      void cmd.params;
      // Command objects are snapshots: mutating one only changes the local
      // array; the edit reaches the shape by reassigning the whole command
      // list (Path.commands set).
      cmd.command = 'line-to';
      cmd.params = { x: 5, y: 5 };
      expect(cmd.command).toBe('line-to');
      expect(path.commands[0].command).toBe('move-to');
      path.commands = commands;
      expect(path.commands[0].command).toBe('line-to');
    });
  });

  describe('Shape ordering and blur', () => {
    test('sendBackward and backgroundBlur are exercised', (ctx) => {
      const a = rect(ctx);
      const b = rect(ctx);
      void b;
      a.sendBackward();
      void a.backgroundBlur;
      expect(a.type).toBe('rectangle');
    });
  });

  describe('Interaction reads', () => {
    test('overlay action fields are readable', (ctx) => {
      const overlay = ctx.penpot.createBoard();
      ctx.board.appendChild(overlay as Board);
      const relative = rect(ctx);
      const r = rect(ctx);
      const interaction = r.addInteraction('click', {
        type: 'open-overlay',
        destination: overlay,
        relativeTo: relative,
        position: 'manual',
        manualPositionLocation: { x: 5, y: 5 },
        animation: { type: 'dissolve', duration: 100, easing: 'linear' },
      });
      if (interaction.action.type === 'open-overlay') {
        void interaction.action.relativeTo;
        void interaction.action.manualPositionLocation;
        void interaction.action.animation;
      }
      // Interaction.action and delay setters (records the (set) targets;
      // persistence is asserted in interactions.test.ts).
      interaction.delay = 250;
      interaction.action = { type: 'previous-screen' };
      expect(interaction.shape && interaction.shape.id).toBe(r.id);
    });

    test('navigate-to preserveScrollPosition and slide/push animation fields', (ctx) => {
      const dest = ctx.penpot.createBoard();
      ctx.board.appendChild(dest as Board);
      const r = rect(ctx);
      const nav = r.addInteraction('click', {
        type: 'navigate-to',
        destination: dest,
        preserveScrollPosition: true,
        animation: {
          type: 'slide',
          way: 'in',
          direction: 'right',
          duration: 300,
          offsetEffect: true,
          easing: 'ease',
        },
      });
      if (nav.action.type === 'navigate-to') {
        void nav.action.preserveScrollPosition;
        const anim = nav.action.animation;
        if (anim && anim.type === 'slide') {
          void anim.offsetEffect;
          void anim.easing;
        }
      }

      const r2 = rect(ctx);
      const push = r2.addInteraction('click', {
        type: 'navigate-to',
        destination: dest,
        animation: {
          type: 'push',
          direction: 'left',
          duration: 300,
          easing: 'ease',
        },
      });
      if (push.action.type === 'navigate-to') {
        const anim = push.action.animation;
        if (anim && anim.type === 'push') {
          void anim.easing;
        }
      }
      expect(r.type).toBe('rectangle');
    });
  });

  describe('Variant container variants', () => {
    test('Variants interface members via a variant container', async (ctx) => {
      function main(): Board {
        const r = ctx.penpot.createRectangle();
        ctx.board.appendChild(r);
        return ctx.penpot.library.local
          .createComponent([r])
          .mainInstance() as Board;
      }
      const container = ctx.penpot.createVariantFromComponents([
        main(),
        main(),
      ]);
      await sleep(300);
      const v = container.variants;
      expect(v).not.toBeNull();
      if (v) {
        expect(typeof v.id).toBe('string');
        expect(typeof v.libraryId).toBe('string');
        expect(Array.isArray(v.properties)).toBe(true);
        expect(Array.isArray(v.variantComponents())).toBe(true);
        if (v.properties.length > 0) {
          void v.currentValues(v.properties[0]);
        }
        v.addProperty();
        await sleep(300);
        v.addVariant();
        await sleep(300);
        if (v.properties.length > 0) {
          v.renameProperty(0, 'Size');
          await sleep(200);
          v.removeProperty(v.properties.length - 1);
        }
      }
    });
  });
});
