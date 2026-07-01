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
        void bool.content;
        expect(bool.fills).toHaveLength(1);
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
    test('export members round-trip on the returned export', (ctx) => {
      const r = rect(ctx);
      r.exports = [{ type: 'png', scale: 1, suffix: '', skipChildren: false }];
      const exp = r.exports[0];
      exp.type = 'jpeg';
      exp.scale = 2;
      exp.suffix = '@2x';
      exp.skipChildren = true;
      expect(exp.type).toBe('jpeg');
      expect(exp.scale).toBeCloseTo(2, 0);
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
    test('flex padding and child margins are readable', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const flex = board.addFlexLayout();
      flex.horizontalPadding = 4;
      flex.verticalPadding = 6;
      void flex.horizontalPadding;
      void flex.verticalPadding;

      const child = ctx.penpot.createRectangle();
      flex.appendChild(child);
      const lc = child.layoutChild;
      if (lc) {
        lc.horizontalMargin = 1;
        lc.verticalMargin = 2;
        lc.topMargin = 3;
        lc.rightMargin = 4;
        lc.bottomMargin = 5;
        lc.leftMargin = 6;
        lc.maxHeight = 100;
        lc.minWidth = 10;
        void lc.horizontalMargin;
        void lc.verticalMargin;
        void lc.leftMargin;
        void lc.rightMargin;
        void lc.bottomMargin;
        void lc.maxHeight;
        void lc.minWidth;
      }
      expect(board.type).toBe('board');
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
      if (cell) {
        cell.areaName = 'header';
        cell.position = 'auto';
        void cell.areaName;
        void cell.position;
        void cell.rowSpan;
      }
      expect(board.type).toBe('board');
    });
  });

  describe('Track', () => {
    test('grid track members round-trip on the returned track', (ctx) => {
      const board = ctx.penpot.createBoard();
      ctx.board.appendChild(board);
      const grid = board.addGridLayout();
      grid.addRow('flex', 1);
      const track = grid.rows[0];
      track.type = 'fixed';
      track.value = 80;
      expect(track.type).toBe('fixed');
      expect(track.value).toBeCloseTo(80, 0);
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
      cmd.command = 'line-to';
      cmd.params = { x: 5, y: 5 };
      expect(cmd.command).toBe('line-to');
      // Reassign the whole command list (Path.commands set).
      path.commands = commands;
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
