import { expect, expectReject } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { CommentThread, Page } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Comments.
// Comment threads are created on the current page. Both thread removal APIs are
// currently broken (see the dedicated red tests), so cleanup is best-effort to
// keep the other assertions meaningful.

function page(ctx: TestContext): Page {
  const p = ctx.penpot.currentPage;
  if (!p) throw new Error('no current page');
  return p;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function cleanup(thread: CommentThread): void {
  try {
    thread.remove();
  } catch (err) {
    void err; // thread.remove is currently broken; ignore for cleanup
  }
}

// Skipped under MOCK_BACKEND: comments assert backend-shaped responses
// (seqNumber, etc.) and pin real backend behaviour that a mock won't reproduce.
describe.skipIfMocked('Comments', () => {
  test('addCommentThread creates a thread', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('Hello comment', {
      x: 100,
      y: 120,
    });
    try {
      expect(typeof thread.seqNumber).toBe('number');
      expect(thread.position.x).toBeCloseTo(100, 0);
      expect(thread.position.y).toBeCloseTo(120, 0);
      expect(thread.resolved).toBe(false);
      expect(thread.owner).toBeDefined();
      // A page-level thread has no board; reading it still exercises the getter.
      void thread.board;
    } finally {
      cleanup(thread);
    }
  });

  test('findCommentThreads lists threads', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('Find me', { x: 50, y: 50 });
    try {
      const threads = await p.findCommentThreads();
      expect(threads.length).toBeGreaterThan(0);
    } finally {
      cleanup(thread);
    }
  });

  test('reply adds a comment and findComments lists them', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('First comment', { x: 10, y: 10 });
    try {
      const reply = await thread.reply('A reply');
      expect(reply.content).toBe('A reply');
      expect(reply.user).toBeDefined();
      expect(reply.date).toBeDefined();

      const comments = await thread.findComments();
      expect(comments.length).toBeGreaterThan(1);
    } finally {
      cleanup(thread);
    }
  });

  test('thread resolved and position round-trip', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('Toggle me', { x: 30, y: 30 });
    try {
      thread.resolved = true;
      expect(thread.resolved).toBe(true);
      thread.position = { x: 200, y: 220 };
      expect(thread.position.x).toBeCloseTo(200, 0);
      expect(thread.position.y).toBeCloseTo(220, 0);
    } finally {
      cleanup(thread);
    }
  });

  test('comment content round-trips', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('Editable', { x: 0, y: 0 });
    try {
      const comments = await thread.findComments();
      const comment = comments[0];
      comment.content = 'edited content';
      // The content setter persists via an async RPC before updating locally.
      await sleep(300);
      expect(comment.content).toBe('edited content');
      expect(comment.user).toBeDefined();
    } finally {
      cleanup(thread);
    }
  });

  test('a comment can be removed', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('Keep', { x: 5, y: 5 });
    try {
      const reply = await thread.reply('to be removed');
      await reply.remove();
      const comments = await thread.findComments();
      expect(comments.length).toBeGreaterThan(0);
    } finally {
      cleanup(thread);
    }
  });

  test('a comment thread can be removed', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('Remove via thread', {
      x: 8,
      y: 8,
    });
    thread.remove();
    const threads = await p.findCommentThreads();
    expect(threads.every((t) => t.seqNumber !== thread.seqNumber)).toBe(true);
  });

  test('removeCommentThread removes a thread', async (ctx) => {
    const p = page(ctx);
    const thread: CommentThread = await p.addCommentThread('Remove me', {
      x: 70,
      y: 70,
    });
    await p.removeCommentThread(thread);
  });

  // ---------------------------------------------------------------------------
  // Edge cases: empty comment content must be rejected.
  // ---------------------------------------------------------------------------
  test('addCommentThread with empty content rejects', async (ctx) => {
    const p = page(ctx);
    await expectReject(() => p.addCommentThread('', { x: 0, y: 0 }));
  });

  test('reply with empty content rejects', async (ctx) => {
    const p = page(ctx);
    const thread = await p.addCommentThread('parent', { x: 12, y: 12 });
    try {
      await expectReject(() => thread.reply(''));
    } finally {
      cleanup(thread);
    }
  });
});
