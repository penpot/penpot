import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

// Events.
// Listeners are registered with `on`, triggered by mutating state, and removed
// with `off`. Callbacks are debounced (~10ms), so the tests wait before asserting.

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe('Events', () => {
  test('selectionchange fires with the selected ids', async (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);

    let received: string[] | null = null;
    const listenerId = ctx.penpot.on('selectionchange', (ids) => {
      received = ids;
    });

    ctx.penpot.selection = [rect];
    await sleep(150);
    ctx.penpot.off(listenerId);

    expect(received).not.toBeNull();
    if (received) {
      expect((received as string[]).includes(rect.id)).toBe(true);
    }
  });

  test('shapechange fires when the observed shape changes', async (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);

    let fired = false;
    const listenerId = ctx.penpot.on(
      'shapechange',
      () => {
        fired = true;
      },
      { shapeId: rect.id },
    );

    rect.name = 'changed-name';
    await sleep(150);
    ctx.penpot.off(listenerId);

    expect(fired).toBe(true);
  });

  test('off stops further notifications', async (ctx) => {
    const rect = ctx.penpot.createRectangle();
    ctx.board.appendChild(rect);

    let count = 0;
    const listenerId = ctx.penpot.on('selectionchange', () => {
      count += 1;
    });
    ctx.penpot.off(listenerId);

    ctx.penpot.selection = [rect];
    await sleep(150);

    expect(count).toBe(0);
  });
});
