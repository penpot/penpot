import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

// Pages, selection and flows.
// Most assertions use the active page (`currentPage`) and the scratch board so
// the user's file is left clean. Tests that create a page restore the active
// page and remove the created one afterwards.

describe('Pages', () => {
  test('currentPage exposes id and name', (ctx) => {
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      expect(typeof page.id).toBe('string');
      expect(typeof page.name).toBe('string');
    }
  });

  test('createPage and openPage activate a new page', async (ctx) => {
    const original = ctx.penpot.currentPage;
    const page = ctx.penpot.createPage();
    page.name = 'plugin-test-page';
    expect(page.name).toBe('plugin-test-page');

    await ctx.penpot.openPage(page);
    const active = ctx.penpot.currentPage;
    expect(active && active.id).toBe(page.id);

    // Restore the originally active page and remove the created page so the
    // file is left clean.
    if (original) await ctx.penpot.openPage(original);
    page.remove();
    const pages = ctx.penpot.currentFile?.pages ?? [];
    expect(pages.some((p) => p.id === page.id)).toBe(false);
  });

  test('removing the active page activates another page', async (ctx) => {
    const original = ctx.penpot.currentPage;
    const page = ctx.penpot.createPage();
    await ctx.penpot.openPage(page);
    page.remove();
    // The switch to another page happens asynchronously; wait for it.
    const start = Date.now();
    let active = ctx.penpot.currentPage;
    while (active && active.id === page.id && Date.now() - start < 2000) {
      await new Promise((resolve) => setTimeout(resolve, 50));
      active = ctx.penpot.currentPage;
    }
    expect(active).not.toBeNull();
    expect(active && active.id).not.toBe(page.id);
    // The workspace activates the first remaining page; go back home.
    if (original) await ctx.penpot.openPage(original);
  });

  test('getShapeById finds a shape on the page', (ctx) => {
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      const found = page.getShapeById(ctx.board.id);
      expect(found).not.toBeNull();
      expect(found && found.id).toBe(ctx.board.id);
    }
  });

  test('findShapes returns shapes on the page', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    const page = ctx.penpot.currentPage;
    if (page) {
      expect(page.findShapes().length).toBeGreaterThan(0);
    }
  });

  test('page root is a shape', (ctx) => {
    const page = ctx.penpot.currentPage;
    if (page) {
      expect(page.root).toBeDefined();
      expect(typeof page.root.type).toBe('string');
    }
  });

  // Edge cases.
  test('getShapeById of an unknown id returns null', (ctx) => {
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      const found = page.getShapeById('00000000-0000-0000-0000-0000000000ff');
      expect(found).toBeNull();
    }
  });

  test('getShapeById finds a just-created shape', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    const page = ctx.penpot.currentPage;
    if (page) {
      const found = page.getShapeById(rect.id);
      expect(found).not.toBeNull();
      expect(found && found.id).toBe(rect.id);
    }
  });
});

describe('Selection', () => {
  test('selection can be set and read', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);

    ctx.penpot.selection = [rect];
    expect(ctx.penpot.selection).toHaveLength(1);
    expect(ctx.penpot.selection[0].id).toBe(rect.id);
  });

  // Edge cases.
  test('assigning an empty selection clears it', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    ctx.penpot.selection = [rect];
    ctx.penpot.selection = [];
    expect(ctx.penpot.selection).toHaveLength(0);
  });

  test('selecting the same shape twice keeps a single entry', (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);
    ctx.penpot.selection = [rect, rect];
    expect(ctx.penpot.selection).toHaveLength(1);
  });
});

describe('Flows', () => {
  test('createFlow defines a flow on a board', (ctx) => {
    const targetBoard = ctx.penpot.createBoard();
    ctx.board.appendChild(targetBoard);
    const page = ctx.penpot.currentPage;
    expect(page).not.toBeNull();
    if (page) {
      const flow = page.createFlow('plugin-flow', targetBoard);
      expect(flow.name).toBe('plugin-flow');
      expect(flow.startingBoard.id).toBe(targetBoard.id);
      expect(flow.page.id).toBe(page.id);
      expect(page.flows.length).toBeGreaterThan(0);
    }
  });

  test('flow name and starting board round-trip', (ctx) => {
    const first = ctx.penpot.createBoard();
    const second = ctx.penpot.createBoard();
    ctx.board.appendChild(first);
    ctx.board.appendChild(second);
    const page = ctx.penpot.currentPage;
    if (page) {
      const flow = page.createFlow('flow-a', first);
      flow.name = 'flow-b';
      flow.startingBoard = second;
      expect(flow.name).toBe('flow-b');
      expect(flow.startingBoard.id).toBe(second.id);
    }
  });

  test('flow can be removed', (ctx) => {
    const targetBoard = ctx.penpot.createBoard();
    ctx.board.appendChild(targetBoard);
    const page = ctx.penpot.currentPage;
    if (page) {
      const flow = page.createFlow('to-remove', targetBoard);
      const before = page.flows.length;
      flow.remove();
      expect(page.flows.length).toBe(before - 1);
    }
  });

  test('page.removeFlow removes a flow', (ctx) => {
    const targetBoard = ctx.penpot.createBoard();
    ctx.board.appendChild(targetBoard);
    const page = ctx.penpot.currentPage;
    if (page) {
      const flow = page.createFlow('to-remove-2', targetBoard);
      const before = page.flows.length;
      page.removeFlow(flow);
      expect(page.flows.length).toBe(before - 1);
    }
  });
});
