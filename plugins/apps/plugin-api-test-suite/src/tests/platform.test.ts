import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';

// Platform: user/session, context info, history, utils and markup.

describe('Platform', () => {
  describe('User', () => {
    test('currentUser exposes profile fields', (ctx) => {
      const user = ctx.penpot.currentUser;
      expect(typeof user.id).toBe('string');
      expect(typeof user.name).toBe('string');
      expect(typeof user.sessionId).toBe('string');
      // avatarUrl and color may be undefined depending on the profile.
      void user.avatarUrl;
      void user.color;
    });

    test('activeUsers is an array', (ctx) => {
      const users = ctx.penpot.activeUsers;
      expect(Array.isArray(users)).toBe(true);
      if (users.length > 0) {
        expect(typeof users[0].id).toBe('string');
        void users[0].position;
        void users[0].zoom;
      }
    });
  });

  describe('Context info', () => {
    test('version is a string', (ctx) => {
      expect(typeof ctx.penpot.version).toBe('string');
    });

    test('theme is light or dark', (ctx) => {
      expect(['light', 'dark']).toContain(ctx.penpot.theme);
    });

    test('flags are readable', (ctx) => {
      expect(typeof ctx.penpot.flags.naturalChildOrdering).toBe('boolean');
      expect(typeof ctx.penpot.flags.throwValidationErrors).toBe('boolean');
    });
  });

  describe('History', () => {
    test('undo block begin and finish wrap operations', (ctx) => {
      const block = ctx.penpot.history.undoBlockBegin();
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      rect.name = 'in-undo-block';
      ctx.penpot.history.undoBlockFinish(block);
      expect(rect.name).toBe('in-undo-block');
    });

    // Edge cases.
    test('finishing an unknown undo block is a no-op (not rejected)', (ctx) => {
      // undoBlockFinish does not validate the block id; an unknown id is ignored
      // rather than rejected.
      expect(() =>
        ctx.penpot.history.undoBlockFinish(Symbol('not-a-real-block')),
      ).not.toThrow();
    });

    test('nested undo blocks begin and finish in order', (ctx) => {
      const outer = ctx.penpot.history.undoBlockBegin();
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const inner = ctx.penpot.history.undoBlockBegin();
      rect.name = 'nested';
      ctx.penpot.history.undoBlockFinish(inner);
      ctx.penpot.history.undoBlockFinish(outer);
      expect(rect.name).toBe('nested');
    });
  });

  describe('Utils', () => {
    test('geometry center returns a point', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      rect.x = 0;
      rect.y = 0;
      rect.resize(100, 100);
      const center = ctx.penpot.utils.geometry.center([rect]);
      expect(center).not.toBeNull();
      if (center) {
        expect(center.x).toBeCloseTo(50, 0);
        expect(center.y).toBeCloseTo(50, 0);
      }
    });

    // Edge cases.
    test('center of an empty array returns null', (ctx) => {
      expect(ctx.penpot.utils.geometry.center([])).toBeNull();
    });

    test('center of two shapes sits at their midpoint', (ctx) => {
      const a = ctx.penpot.createRectangle();
      const b = ctx.penpot.createRectangle();
      ctx.board.appendChild(a);
      ctx.board.appendChild(b);
      a.x = 0;
      a.y = 0;
      a.resize(100, 100);
      b.x = 200;
      b.y = 100;
      b.resize(100, 100);
      const center = ctx.penpot.utils.geometry.center([a, b]);
      expect(center).not.toBeNull();
      if (center) {
        // a spans 0..100, b spans 200..300 → combined bounds 0..300 → centre 150.
        expect(center.x).toBeCloseTo(150, 0);
        expect(center.y).toBeCloseTo(100, 0);
      }
    });

    test('types predicates identify shapes', (ctx) => {
      const types = ctx.penpot.utils.types;
      const rect = ctx.penpot.createRectangle();
      const ellipse = ctx.penpot.createEllipse();
      const text = ctx.penpot.createText('hi');
      const path = ctx.penpot.createPath();
      ctx.board.appendChild(rect);
      ctx.board.appendChild(ellipse);
      ctx.board.appendChild(path);
      if (text) ctx.board.appendChild(text);

      expect(types.isRectangle(rect)).toBe(true);
      expect(types.isEllipse(ellipse)).toBe(true);
      expect(types.isPath(path)).toBe(true);
      expect(types.isBoard(ctx.board)).toBe(true);
      if (text) {
        expect(types.isText(text)).toBe(true);
      }
      // Non-matching predicates should be falsy.
      expect(types.isGroup(rect)).toBeFalsy();
      expect(types.isBool(rect)).toBeFalsy();
      expect(types.isMask(rect)).toBeFalsy();
      expect(types.isSVG(rect)).toBeFalsy();
      expect(types.isVariantContainer(rect)).toBeFalsy();
    });
  });

  describe('Markup', () => {
    test('generateMarkup returns html', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const markup = ctx.penpot.generateMarkup([rect]);
      expect(typeof markup).toBe('string');
    });

    test('generateMarkup can target svg', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const svg = ctx.penpot.generateMarkup([rect], { type: 'svg' });
      expect(typeof svg).toBe('string');
    });

    test('generateStyle returns css', (ctx) => {
      const rect = ctx.penpot.createRectangle();
      ctx.board.appendChild(rect);
      const styles = ctx.penpot.generateStyle([rect]);
      expect(typeof styles).toBe('string');
    });
  });
});
