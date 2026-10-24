import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resizeHandler } from './resize-handler.js';

type PointerLikeEvent = MouseEvent & { pointerId: number };

function createPointerEvent(
  type: string,
  init: Partial<PointerEventInit> = {},
): PointerLikeEvent {
  const event = new MouseEvent(type, {
    bubbles: true,
    cancelable: true,
    clientX: init.clientX ?? 0,
    clientY: init.clientY ?? 0,
    button: init.button ?? 0,
  }) as PointerLikeEvent;

  Object.defineProperty(event, 'pointerId', {
    configurable: true,
    value: init.pointerId ?? 1,
  });

  return event;
}

const limits = () => ({
  minWidth: 200,
  minHeight: 200,
  maxWidth: 800,
  maxHeight: 600,
});

describe('resizeHandler', () => {
  let handle: HTMLElement;
  let target: HTMLElement;

  beforeEach(() => {
    target = document.createElement('div');
    target.style.width = '300px';
    target.style.height = '400px';
    handle = document.createElement('div');
    for (const [name, value] of [
      ['setPointerCapture', vi.fn()],
      ['releasePointerCapture', vi.fn()],
      ['hasPointerCapture', vi.fn().mockReturnValue(true)],
    ] as const) {
      Object.defineProperty(handle, name, { configurable: true, value });
    }
    target.appendChild(handle);
    document.body.appendChild(target);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.clearAllMocks();
  });

  const drag = (from: [number, number], to: [number, number]) => {
    handle.dispatchEvent(
      createPointerEvent('pointerdown', { clientX: from[0], clientY: from[1] }),
    );
    handle.dispatchEvent(
      createPointerEvent('pointermove', { clientX: to[0], clientY: to[1] }),
    );
  };

  it('grows and shrinks the target with the pointer', () => {
    resizeHandler(handle, target, limits);

    drag([500, 500], [560, 530]);
    expect([target.style.width, target.style.height]).toEqual([
      '360px',
      '430px',
    ]);

    handle.dispatchEvent(
      createPointerEvent('pointermove', { clientX: 450, clientY: 480 }),
    );
    expect([target.style.width, target.style.height]).toEqual([
      '250px',
      '380px',
    ]);
  });

  it('keeps the target within its limits', () => {
    resizeHandler(handle, target, limits);

    drag([500, 500], [100, 100]);
    expect([target.style.width, target.style.height]).toEqual([
      '200px',
      '200px',
    ]);

    handle.dispatchEvent(
      createPointerEvent('pointermove', { clientX: 2000, clientY: 2000 }),
    );
    expect([target.style.width, target.style.height]).toEqual([
      '800px',
      '600px',
    ]);
  });

  it('captures the pointer, tells when it starts and ends, and stops on pointerup', () => {
    const start = vi.fn();
    const end = vi.fn();
    resizeHandler(handle, target, limits, { start, end });

    drag([500, 500], [520, 520]);
    expect(handle.setPointerCapture).toHaveBeenCalledWith(1);
    expect(start).toHaveBeenCalledTimes(1);

    handle.dispatchEvent(createPointerEvent('pointerup'));
    expect(handle.releasePointerCapture).toHaveBeenCalledWith(1);
    expect(end).toHaveBeenCalledTimes(1);

    handle.dispatchEvent(
      createPointerEvent('pointermove', { clientX: 700, clientY: 700 }),
    );
    expect(target.style.width).toBe('320px');
  });

  it('ignores other buttons and other pointers', () => {
    resizeHandler(handle, target, limits);

    handle.dispatchEvent(
      createPointerEvent('pointerdown', { clientX: 500, button: 2 }),
    );
    handle.dispatchEvent(createPointerEvent('pointermove', { clientX: 600 }));
    expect(target.style.width).toBe('300px');

    drag([500, 500], [500, 500]);
    handle.dispatchEvent(
      createPointerEvent('pointermove', { clientX: 900, pointerId: 2 }),
    );
    expect(target.style.width).toBe('300px');
  });

  it('removes its listeners when cleaned up', () => {
    const cleanup = resizeHandler(handle, target, limits);
    cleanup();

    drag([500, 500], [600, 600]);
    expect(target.style.width).toBe('300px');
  });
});
