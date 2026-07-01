import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Board } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Layout (flex & grid).
// Layouts are created on boards via addFlexLayout/addGridLayout. Child and cell
// properties are reached through a shape that lives inside the laid-out board.
// Each group keeps its happy-path round-trips together with the related edge
// cases: "throws" tests assert invalid input is rejected (a red test surfaces a
// missing-validation bug) and the remaining ones pin non-trivial valid behaviour.
// Note: track insertion indices are 0-based (addRowAtIndex/removeRow/setRow);
// appendChild cell coordinates and layoutCell.row/column are 1-based.

function board(ctx: TestContext): Board {
  const b = ctx.penpot.createBoard();
  ctx.board.appendChild(b);
  return b;
}

describe('Layout', () => {
  describe('Flex', () => {
    test('addFlexLayout adds a flex layout to the board', (ctx) => {
      const b = board(ctx);
      const flex = b.addFlexLayout();
      expect(flex).toBeDefined();
      expect(b.flex).toBeDefined();
    });

    test('direction and wrap round-trip', (ctx) => {
      const flex = board(ctx).addFlexLayout();
      flex.dir = 'column';
      flex.wrap = 'wrap';
      expect(flex.dir).toBe('column');
      expect(flex.wrap).toBe('wrap');
    });

    test('alignment round-trips', (ctx) => {
      const flex = board(ctx).addFlexLayout();
      flex.alignItems = 'center';
      flex.alignContent = 'space-between';
      flex.justifyItems = 'center';
      flex.justifyContent = 'space-around';
      expect(flex.alignItems).toBe('center');
      expect(flex.alignContent).toBe('space-between');
      expect(flex.justifyItems).toBe('center');
      expect(flex.justifyContent).toBe('space-around');
    });

    test('gaps and padding round-trip', (ctx) => {
      const flex = board(ctx).addFlexLayout();
      flex.rowGap = 5;
      flex.columnGap = 10;
      flex.verticalPadding = 4;
      flex.horizontalPadding = 8;
      flex.topPadding = 1;
      flex.rightPadding = 2;
      flex.bottomPadding = 3;
      flex.leftPadding = 4;
      expect(flex.rowGap).toBeCloseTo(5, 0);
      expect(flex.columnGap).toBeCloseTo(10, 0);
      expect(flex.topPadding).toBeCloseTo(1, 0);
      expect(flex.rightPadding).toBeCloseTo(2, 0);
      expect(flex.bottomPadding).toBeCloseTo(3, 0);
      expect(flex.leftPadding).toBeCloseTo(4, 0);
    });

    test('sizing round-trips', (ctx) => {
      const flex = board(ctx).addFlexLayout();
      flex.horizontalSizing = 'fix';
      flex.verticalSizing = 'auto';
      expect(flex.horizontalSizing).toBe('fix');
      expect(flex.verticalSizing).toBe('auto');
    });

    test('appendChild adds a child to the flex layout', (ctx) => {
      const b = board(ctx);
      const flex = b.addFlexLayout();
      const rect = ctx.penpot.createRectangle();
      flex.appendChild(rect);
      expect(b.children.length).toBeGreaterThan(0);
    });

    test('remove deletes the flex layout', (ctx) => {
      const b = board(ctx);
      const flex = b.addFlexLayout();
      flex.remove();
      expect(b.flex).toBeFalsy();
    });
  });

  describe('Grid', () => {
    test('addGridLayout adds a grid layout to the board', (ctx) => {
      const b = board(ctx);
      const grid = b.addGridLayout();
      expect(grid).toBeDefined();
      expect(b.grid).toBeDefined();
    });

    test('direction round-trips', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.dir = 'row';
      expect(grid.dir).toBe('row');
    });

    test('rows and columns can be added and read as tracks', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      grid.addColumn('percent', 50);

      expect(grid.rows.length).toBeGreaterThan(0);
      expect(grid.columns.length).toBeGreaterThan(0);
      expect(grid.rows[0].type).toBe('flex');
      expect(grid.columns[0].type).toBe('percent');
      expect(grid.columns[0].value).toBeCloseTo(50, 0);
    });

    test('addRowAtIndex inserts a row at an index', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      grid.addRowAtIndex(0, 'fixed', 100);
      expect(grid.rows[0].type).toBe('fixed');
    });

    test('addColumnAtIndex inserts a column at an index', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addColumn('flex', 1);
      grid.addColumnAtIndex(0, 'fixed', 100);
      expect(grid.columns[0].type).toBe('fixed');
    });

    test('setRow and setColumn update tracks', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);
      grid.setRow(0, 'fixed', 80);
      grid.setColumn(0, 'percent', 25);
      expect(grid.rows[0].type).toBe('fixed');
      expect(grid.rows[0].value).toBeCloseTo(80, 0);
      expect(grid.columns[0].type).toBe('percent');
    });

    test('removeRow and removeColumn drop tracks', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);
      grid.addColumn('flex', 1);
      const rowsBefore = grid.rows.length;
      const colsBefore = grid.columns.length;
      grid.removeRow(0);
      grid.removeColumn(0);
      expect(grid.rows.length).toBe(rowsBefore - 1);
      expect(grid.columns.length).toBe(colsBefore - 1);
    });

    test('appendChild places a child into a cell', (ctx) => {
      const b = board(ctx);
      const grid = b.addGridLayout();
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);
      const rect = ctx.penpot.createRectangle();
      grid.appendChild(rect, 1, 1);
      expect(b.children.length).toBeGreaterThan(0);
    });

    test('alignment and gaps round-trip', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.alignItems = 'center';
      grid.justifyItems = 'start';
      grid.rowGap = 7;
      grid.columnGap = 9;
      expect(grid.alignItems).toBe('center');
      expect(grid.justifyItems).toBe('start');
      expect(grid.rowGap).toBeCloseTo(7, 0);
      expect(grid.columnGap).toBeCloseTo(9, 0);
    });

    // Index boundaries — invalid indices must be rejected.
    test('addRowAtIndex with a negative index throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      expect(() => grid.addRowAtIndex(-1, 'fixed', 100)).toThrow();
    });

    test('addRowAtIndex past the end throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      expect(() => grid.addRowAtIndex(5, 'fixed', 100)).toThrow();
    });

    test('addColumnAtIndex with a negative index throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addColumn('flex', 1);
      expect(() => grid.addColumnAtIndex(-1, 'fixed', 100)).toThrow();
    });

    test('removeRow on an empty grid throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      expect(() => grid.removeRow(0)).toThrow();
    });

    test('removeRow with an out-of-range index throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      expect(() => grid.removeRow(5)).toThrow();
    });

    test('removeColumn with an out-of-range index throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addColumn('flex', 1);
      expect(() => grid.removeColumn(5)).toThrow();
    });

    test('setRow with an out-of-range index throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      expect(() => grid.setRow(5, 'fixed', 80)).toThrow();
    });

    test('setColumn with an out-of-range index throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addColumn('flex', 1);
      expect(() => grid.setColumn(5, 'fixed', 80)).toThrow();
    });

    // Track type — invalid track types must be rejected.
    test('addRow with an invalid track type throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      expect(() => grid.addRow('not-a-type' as unknown as 'flex', 1)).toThrow();
    });

    test('addColumn with an invalid track type throws', (ctx) => {
      const grid = board(ctx).addGridLayout();
      expect(() =>
        grid.addColumn('not-a-type' as unknown as 'flex', 1),
      ).toThrow();
    });

    // Success edges — non-trivial valid behaviour.
    test('addRowAtIndex inserts at the position and shifts the rest', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('fixed', 10);
      grid.addRow('percent', 20);
      grid.addRowAtIndex(1, 'flex', 1);
      expect(grid.rows.length).toBe(3);
      expect(grid.rows[0].type).toBe('fixed');
      expect(grid.rows[1].type).toBe('flex');
      expect(grid.rows[2].type).toBe('percent');
    });

    test('setRow updates a track in place without changing the count', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      grid.addRow('flex', 1);
      grid.addRow('flex', 1);
      grid.setRow(1, 'fixed', 80);
      expect(grid.rows.length).toBe(3);
      expect(grid.rows[0].type).toBe('flex');
      expect(grid.rows[1].type).toBe('fixed');
      expect(grid.rows[1].value).toBeCloseTo(80, 0);
      expect(grid.rows[2].type).toBe('flex');
    });

    test('mixed track types coexist and read back', (ctx) => {
      const grid = board(ctx).addGridLayout();
      grid.addRow('flex', 1);
      grid.addRow('fixed', 50);
      grid.addRow('percent', 25);
      grid.addRow('auto');
      expect(grid.rows.map((r) => r.type)).toEqual([
        'flex',
        'fixed',
        'percent',
        'auto',
      ]);
    });

    test('appendChild places children into the cells requested', (ctx) => {
      const b = board(ctx);
      const grid = b.addGridLayout();
      grid.addRow('flex', 1);
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);
      grid.addColumn('flex', 1);

      const a = ctx.penpot.createRectangle();
      const c = ctx.penpot.createRectangle();
      grid.appendChild(a, 1, 1);
      grid.appendChild(c, 2, 2);

      const cellA = a.layoutCell;
      const cellC = c.layoutCell;
      expect(cellA).toBeDefined();
      expect(cellC).toBeDefined();
      if (cellA && cellC) {
        expect(cellA.row).toBeCloseTo(1, 0);
        expect(cellA.column).toBeCloseTo(1, 0);
        expect(cellC.row).toBeCloseTo(2, 0);
        expect(cellC.column).toBeCloseTo(2, 0);
      }
    });

    test('a grid board can nest a flex board as a child', (ctx) => {
      const outer = board(ctx);
      const grid = outer.addGridLayout();
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);

      const inner = ctx.penpot.createBoard();
      const flex = inner.addFlexLayout();
      flex.dir = 'column';
      grid.appendChild(inner, 1, 1);

      expect(outer.children.length).toBeGreaterThan(0);
      expect(inner.flex).toBeDefined();
      expect(inner.flex && inner.flex.dir).toBe('column');
    });
  });

  describe('Child', () => {
    test('layout child properties round-trip', (ctx) => {
      const b = board(ctx);
      const flex = b.addFlexLayout();
      const rect = ctx.penpot.createRectangle();
      flex.appendChild(rect);

      const child = rect.layoutChild;
      expect(child).toBeDefined();
      if (child) {
        child.absolute = true;
        child.zIndex = 3;
        child.horizontalSizing = 'fill';
        child.verticalSizing = 'fix';
        child.alignSelf = 'center';
        child.horizontalMargin = 2;
        child.verticalMargin = 4;
        child.topMargin = 1;
        child.rightMargin = 2;
        child.bottomMargin = 3;
        child.leftMargin = 4;
        child.maxWidth = 200;
        child.maxHeight = 150;
        child.minWidth = 10;
        child.minHeight = 20;

        expect(child.absolute).toBe(true);
        expect(child.zIndex).toBeCloseTo(3, 0);
        expect(child.horizontalSizing).toBe('fill');
        expect(child.verticalSizing).toBe('fix');
        expect(child.alignSelf).toBe('center');
        expect(child.topMargin).toBeCloseTo(1, 0);
        expect(child.maxWidth).toBeCloseTo(200, 0);
        expect(child.minHeight).toBeCloseTo(20, 0);
      }
    });
  });

  describe('Cell', () => {
    test('layout cell properties round-trip', (ctx) => {
      const b = board(ctx);
      const grid = b.addGridLayout();
      grid.addRow('flex', 1);
      grid.addRow('flex', 1);
      grid.addColumn('flex', 1);
      grid.addColumn('flex', 1);
      const rect = ctx.penpot.createRectangle();
      grid.appendChild(rect, 1, 1);

      const cell = rect.layoutCell;
      expect(cell).toBeDefined();
      if (cell) {
        cell.row = 1;
        cell.column = 1;
        cell.rowSpan = 1;
        cell.columnSpan = 2;
        expect(cell.row).toBeCloseTo(1, 0);
        expect(cell.column).toBeCloseTo(1, 0);
        expect(cell.columnSpan).toBeCloseTo(2, 0);
      }
    });
  });

  describe('Switching type', () => {
    // addFlexLayout/addGridLayout do not reject a board that already has a
    // layout; they create the requested layout (switching the board's type).
    // These pin that behaviour.
    test('adding a grid layout to a board that already has a flex layout switches to grid', (ctx) => {
      const b = board(ctx);
      b.addFlexLayout();
      expect(() => b.addGridLayout()).not.toThrow();
      expect(b.grid).toBeDefined();
    });

    test('adding a flex layout to a board that already has a grid layout switches to flex', (ctx) => {
      const b = board(ctx);
      b.addGridLayout();
      expect(() => b.addFlexLayout()).not.toThrow();
      expect(b.flex).toBeDefined();
    });
  });
});
