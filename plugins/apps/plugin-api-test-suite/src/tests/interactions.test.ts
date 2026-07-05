import { expect } from '../framework/expect';
import { describe, test } from '../framework/registry';
import type { Board, Rectangle } from '@penpot/plugin-types';
import type { TestContext } from '../framework/types';

// Interactions, overlays and animations.
// Interactions are added to a shape; navigate/overlay actions target boards, so
// destination boards are self-provisioned on the scratch board.

function board(ctx: TestContext): Board {
  const b = ctx.penpot.createBoard();
  ctx.board.appendChild(b);
  return b;
}

function rect(ctx: TestContext): Rectangle {
  const r = ctx.penpot.createRectangle();
  ctx.board.appendChild(r);
  return r;
}

describe('Interactions', () => {
  test('navigate-to interaction round-trips', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'navigate-to',
      destination: dest,
    });

    expect(interaction.trigger).toBe('click');
    expect(interaction.action.type).toBe('navigate-to');
    if (interaction.action.type === 'navigate-to') {
      expect(interaction.action.destination.id).toBe(dest.id);
    }
    expect(interaction.shape && interaction.shape.id).toBe(r.id);
    expect(r.interactions.length).toBeGreaterThan(0);
  });

  test('open-url interaction round-trips', (ctx) => {
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'open-url',
      url: 'https://example.com',
    });
    expect(interaction.action.type).toBe('open-url');
    if (interaction.action.type === 'open-url') {
      expect(interaction.action.url).toBe('https://example.com');
    }
  });

  test('open-overlay interaction round-trips', (ctx) => {
    const overlay = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'open-overlay',
      destination: overlay,
      position: 'manual',
      manualPositionLocation: { x: 10, y: 20 },
      closeWhenClickOutside: true,
      addBackgroundOverlay: true,
      animation: { type: 'dissolve', duration: 100, easing: 'linear' },
    });
    expect(interaction.action.type).toBe('open-overlay');
    if (interaction.action.type === 'open-overlay') {
      expect(interaction.action.destination.id).toBe(overlay.id);
      expect(interaction.action.position).toBe('manual');
      expect(interaction.action.closeWhenClickOutside).toBe(true);
      expect(interaction.action.addBackgroundOverlay).toBe(true);
    }
  });

  test('open-overlay supports a non-manual position', (ctx) => {
    const overlay = board(ctx);
    const r = rect(ctx);
    // Per the types, manualPositionLocation is only needed for 'manual'.
    const interaction = r.addInteraction('click', {
      type: 'open-overlay',
      destination: overlay,
      position: 'center',
      animation: { type: 'dissolve', duration: 100, easing: 'linear' },
    });
    expect(interaction.action.type).toBe('open-overlay');
  });

  // position is optional; when omitted the overlay defaults to 'center'.
  test('open-overlay without a position defaults to center', (ctx) => {
    const overlay = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'open-overlay',
      destination: overlay,
    });
    expect(interaction.action.type).toBe('open-overlay');
    if (interaction.action.type === 'open-overlay') {
      expect(interaction.action.destination.id).toBe(overlay.id);
      expect(interaction.action.position).toBe('center');
    }
  });

  test('toggle-overlay interaction round-trips', (ctx) => {
    const overlay = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'toggle-overlay',
      destination: overlay,
      position: 'manual',
      manualPositionLocation: { x: 0, y: 0 },
      animation: { type: 'dissolve', duration: 100, easing: 'linear' },
    });
    expect(interaction.action.type).toBe('toggle-overlay');
    if (interaction.action.type === 'toggle-overlay') {
      expect(interaction.action.destination.id).toBe(overlay.id);
    }
  });

  test('close-overlay interaction round-trips', (ctx) => {
    const overlay = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'close-overlay',
      destination: overlay,
      animation: { type: 'dissolve', duration: 200, easing: 'linear' },
    });
    expect(interaction.action.type).toBe('close-overlay');
    if (interaction.action.type === 'close-overlay') {
      expect(
        interaction.action.destination && interaction.action.destination.id,
      ).toBe(overlay.id);
      expect(interaction.action.animation).toBeDefined();
    }
  });

  // animation is optional on close-overlay; omitting it closes with no transition.
  test('close-overlay without an animation round-trips', (ctx) => {
    const overlay = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'close-overlay',
      destination: overlay,
    });
    expect(interaction.action.type).toBe('close-overlay');
    if (interaction.action.type === 'close-overlay') {
      expect(
        interaction.action.destination && interaction.action.destination.id,
      ).toBe(overlay.id);
      expect(interaction.action.animation).toBeUndefined();
    }
  });

  test('previous-screen interaction round-trips', (ctx) => {
    const r = rect(ctx);
    const interaction = r.addInteraction('click', { type: 'previous-screen' });
    expect(interaction.action.type).toBe('previous-screen');
  });

  test('after-delay trigger carries a delay', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction(
      'after-delay',
      { type: 'navigate-to', destination: dest },
      1000,
    );
    expect(interaction.trigger).toBe('after-delay');
    expect(interaction.delay).toBeCloseTo(1000, 0);
  });

  // A zero delay is a valid value (fires immediately), not an error.
  test('after-delay accepts a zero delay', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction(
      'after-delay',
      { type: 'navigate-to', destination: dest },
      0,
    );
    expect(interaction.trigger).toBe('after-delay');
    expect(interaction.delay).toBeCloseTo(0, 0);
  });

  test('mouse-leave trigger is recorded', (ctx) => {
    // click / mouse-enter / after-delay are covered above; mouse-leave is the
    // remaining trigger.
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('mouse-leave', {
      type: 'navigate-to',
      destination: dest,
    });
    expect(interaction.trigger).toBe('mouse-leave');
  });

  // Pins persistence of the `delay` and `action` setters on an existing
  // interaction (mutating after `addInteraction`). `misc.test.ts:300` exercises
  // these setters' (set) coverage targets but never asserts that the new values
  // stick; this fills that behavioural gap. (An older note claimed these setters
  // "don't persist" — that is stale: CI confirms they do.)
  test('interaction delay and action setters persist', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction(
      'after-delay',
      { type: 'navigate-to', destination: dest },
      1000,
    );

    interaction.delay = 250;
    interaction.action = { type: 'previous-screen' };

    expect(interaction.delay).toBeCloseTo(250, 0);
    expect(interaction.action.type).toBe('previous-screen');
  });

  // The delay setter accepts zero (fires immediately) as a valid value.
  test('delay setter accepts a zero value', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction(
      'after-delay',
      { type: 'navigate-to', destination: dest },
      1000,
    );

    interaction.delay = 0;
    expect(interaction.delay).toBeCloseTo(0, 0);
  });

  describe('Animations', () => {
    test('dissolve animation round-trips', (ctx) => {
      const dest = board(ctx);
      const r = rect(ctx);
      const interaction = r.addInteraction('click', {
        type: 'navigate-to',
        destination: dest,
        animation: { type: 'dissolve', duration: 300, easing: 'ease' },
      });
      if (
        interaction.action.type === 'navigate-to' &&
        interaction.action.animation
      ) {
        expect(interaction.action.animation.type).toBe('dissolve');
        if (interaction.action.animation.type === 'dissolve') {
          expect(interaction.action.animation.duration).toBeCloseTo(300, 0);
          expect(interaction.action.animation.easing).toBe('ease');
        }
      }
    });

    test('dissolve animation accepts every easing curve', (ctx) => {
      // Only `linear` and `ease` are exercised elsewhere; cover the remaining
      // easing curves so a single broken curve is caught.
      for (const easing of ['ease-in', 'ease-out', 'ease-in-out'] as const) {
        const dest = board(ctx);
        const r = rect(ctx);
        const interaction = r.addInteraction('click', {
          type: 'navigate-to',
          destination: dest,
          animation: { type: 'dissolve', duration: 200, easing },
        });
        if (
          interaction.action.type === 'navigate-to' &&
          interaction.action.animation &&
          interaction.action.animation.type === 'dissolve'
        ) {
          expect(interaction.action.animation.easing).toBe(easing);
        }
      }
    });

    test('slide animation round-trips', (ctx) => {
      const dest = board(ctx);
      const r = rect(ctx);
      const interaction = r.addInteraction('click', {
        type: 'navigate-to',
        destination: dest,
        animation: {
          type: 'slide',
          way: 'in',
          direction: 'right',
          duration: 300,
          easing: 'linear',
        },
      });
      if (
        interaction.action.type === 'navigate-to' &&
        interaction.action.animation
      ) {
        expect(interaction.action.animation.type).toBe('slide');
        if (interaction.action.animation.type === 'slide') {
          expect(interaction.action.animation.way).toBe('in');
          expect(interaction.action.animation.direction).toBe('right');
          expect(interaction.action.animation.duration).toBeCloseTo(300, 0);
        }
      }
    });

    test('push animation round-trips', (ctx) => {
      const dest = board(ctx);
      const r = rect(ctx);
      const interaction = r.addInteraction('click', {
        type: 'navigate-to',
        destination: dest,
        animation: {
          type: 'push',
          direction: 'left',
          duration: 300,
          easing: 'linear',
        },
      });
      if (
        interaction.action.type === 'navigate-to' &&
        interaction.action.animation
      ) {
        expect(interaction.action.animation.type).toBe('push');
        if (interaction.action.animation.type === 'push') {
          expect(interaction.action.animation.direction).toBe('left');
          expect(interaction.action.animation.duration).toBeCloseTo(300, 0);
        }
      }
    });
  });

  test('an interaction can be removed', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'navigate-to',
      destination: dest,
    });

    const before = r.interactions.length;
    interaction.remove();
    expect(r.interactions.length).toBe(before - 1);
  });

  test('interaction trigger can be changed', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'navigate-to',
      destination: dest,
    });

    interaction.trigger = 'mouse-enter';
    expect(interaction.trigger).toBe('mouse-enter');
  });

  // ---------------------------------------------------------------------------
  // Edge cases. "fail" tests assert invalid interaction input is
  // rejected; the "success" test checks several triggers coexisting.
  // ---------------------------------------------------------------------------
  // addInteraction validates the interaction's structure (schema) but not the
  // liveness of a navigate destination nor the format of an open-url string,
  // so both of these are accepted rather than rejected. These pin the current
  // (lenient) behaviour.
  test('navigate-to a removed board is accepted (dangling destination)', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    dest.remove();
    expect(() =>
      r.addInteraction('click', { type: 'navigate-to', destination: dest }),
    ).not.toThrow();
  });

  test('open-url accepts an arbitrary url string', (ctx) => {
    const r = rect(ctx);
    const interaction = r.addInteraction('click', {
      type: 'open-url',
      url: 'not a valid url',
    });
    expect(interaction.action.type).toBe('open-url');
    if (interaction.action.type === 'open-url') {
      expect(interaction.action.url).toBe('not a valid url');
    }
  });

  test('several triggers on one shape coexist', (ctx) => {
    const dest = board(ctx);
    const r = rect(ctx);
    r.addInteraction('click', { type: 'navigate-to', destination: dest });
    r.addInteraction('mouse-enter', {
      type: 'navigate-to',
      destination: dest,
    });
    expect(r.interactions).toHaveLength(2);
    expect(r.interactions.map((i) => i.trigger).sort()).toEqual([
      'click',
      'mouse-enter',
    ]);
  });
});
