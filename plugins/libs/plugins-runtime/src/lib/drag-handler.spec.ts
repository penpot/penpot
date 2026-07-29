import { expect, describe, vi } from 'vitest';
import { dragHandler } from './drag-handler.js';

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

describe('dragHandler', () => {
  let element: HTMLElement;

  beforeEach(() => {
    element = document.createElement('div');
    Object.defineProperty(element, 'setPointerCapture', {
      configurable: true,
      value: vi.fn(),
    });
    Object.defineProperty(element, 'releasePointerCapture', {
      configurable: true,
      value: vi.fn(),
    });
    Object.defineProperty(element, 'hasPointerCapture', {
      configurable: true,
      value: vi.fn().mockReturnValue(true),
    });
    document.body.appendChild(element);
  });

  afterEach(() => {
    document.body.removeChild(element);
    vi.clearAllMocks();
  });

  it('should attach pointerdown event listener to the element', () => {
    const addEventListenerMock = vi.spyOn(element, 'addEventListener');

    dragHandler(element);

    expect(addEventListenerMock).toHaveBeenCalledWith(
      'pointerdown',
      expect.any(Function),
    );
  });

  it('should update element transform on pointermove', () => {
    const pointerDownEvent = createPointerEvent('pointerdown', {
      clientX: 100,
      clientY: 100,
    });

    dragHandler(element);

    element.dispatchEvent(pointerDownEvent);

    const pointerMoveEvent = createPointerEvent('pointermove', {
      clientX: 150,
      clientY: 150,
    });
    element.dispatchEvent(pointerMoveEvent);

    expect(element.style.transform).toBe('translate(50px, 50px)');

    const pointerMoveEvent2 = createPointerEvent('pointermove', {
      clientX: 200,
      clientY: 200,
    });
    element.dispatchEvent(pointerMoveEvent2);

    expect(element.style.transform).toBe('translate(100px, 100px)');
  });

  it('should run lifecycle callbacks on drag start/end', () => {
    const start = vi.fn();
    const end = vi.fn();
    const pointerDownEvent = createPointerEvent('pointerdown', {
      clientX: 100,
      clientY: 100,
      pointerId: 2,
    });
    const pointerUpEvent = createPointerEvent('pointerup', {
      pointerId: 2,
    });

    dragHandler(element, element, undefined, { start, end });
    element.dispatchEvent(pointerDownEvent);
    element.dispatchEvent(pointerUpEvent);

    expect(start).toHaveBeenCalledTimes(1);
    expect(end).toHaveBeenCalledTimes(1);
    expect(element.releasePointerCapture).toHaveBeenCalledWith(2);
  });

  it('should ignore pointerdown events from button targets', () => {
    const start = vi.fn();
    const button = document.createElement('button');
    const icon = document.createElement('span');
    button.appendChild(icon);
    element.appendChild(button);

    dragHandler(element, element, undefined, { start });

    icon.dispatchEvent(
      createPointerEvent('pointerdown', {
        pointerId: 5,
        button: 0,
      }),
    );

    expect(start).not.toHaveBeenCalled();
    expect(element.setPointerCapture).not.toHaveBeenCalled();
  });

  it('should remove pointer listeners on teardown', () => {
    const removeEventListenerMock = vi.spyOn(element, 'removeEventListener');

    const cleanup = dragHandler(element);
    cleanup();

    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'pointerdown',
      expect.any(Function),
    );
    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'pointermove',
      expect.any(Function),
    );
    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'pointerup',
      expect.any(Function),
    );
    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'pointercancel',
      expect.any(Function),
    );
    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'lostpointercapture',
      expect.any(Function),
    );
  });
});
