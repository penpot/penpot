import { expect, expectReject } from '../framework/expect';
import { describe, test } from '../framework/registry';

// waitForSave: resolves once the pending file changes are saved, or when
// there is nothing to save. It waits on the shared persistence queue, so the
// edits of earlier tests may be in flight too; each test only asserts what
// holds whatever else is queued.

describe('WaitForSave', () => {
  test('resolves after an edit is saved', async (ctx) => {
    const r = ctx.penpot.createRectangle();
    ctx.board.appendChild(r);
    r.name = 'wait-save-probe';

    const value = await ctx.penpot.waitForSave();
    expect(value).toBeUndefined();
    expect(r.name).toBe('wait-save-probe');
  });

  test('resolves when there is nothing to save', async (ctx) => {
    await ctx.penpot.waitForSave();
    await ctx.penpot.waitForSave(5000);
  });

  test('rejects an invalid timeout with an Error', async (ctx) => {
    for (const value of [
      -1,
      0,
      Number.NaN,
      Number.POSITIVE_INFINITY,
      2 ** 31,
    ]) {
      const p = ctx.penpot.waitForSave(value);
      expect(p instanceof Promise).toBe(true);
      await expectReject(p, /not valid/);
      const error = await p.catch((e: unknown) => e);
      expect(error instanceof Error).toBe(true);
    }
  });
});
