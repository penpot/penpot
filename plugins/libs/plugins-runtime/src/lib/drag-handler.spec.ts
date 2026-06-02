import { expect, describe, vi } from 'vitest';
import { dragHandler } from './drag-handler.js';

describe('dragHandler', () => {
  let element: HTMLElement;

  beforeEach(() => {
    element = document.createElement('div');
    document.body.appendChild(element);
  });

  afterEach(() => {
    document.body.removeChild(element);
    vi.clearAllMocks();
  });

  it('should attach mousedown event listener to the element', () => {
    const addEventListenerMock = vi.spyOn(element, 'addEventListener');

    dragHandler(element);

    expect(addEventListenerMock).toHaveBeenCalledWith(
      'mousedown',
      expect.any(Function),
    );
  });

  it('should update element transform on mousemove', () => {
    const mouseDownEvent = new MouseEvent('mousedown', {
      clientX: 100,
      clientY: 100,
    });

    dragHandler(element);

    element.dispatchEvent(mouseDownEvent);

    const mouseMoveEvent = new MouseEvent('mousemove', {
      clientX: 150,
      clientY: 150,
    });
    document.dispatchEvent(mouseMoveEvent);

    expect(element.style.transform).toBe('translate(50px, 50px)');

    const mouseMoveEvent2 = new MouseEvent('mousemove', {
      clientX: 200,
      clientY: 200,
    });
    document.dispatchEvent(mouseMoveEvent2);

    expect(element.style.transform).toBe('translate(100px, 100px)');
  });

  it('should remove event listeners on mouseup', () => {
    const removeEventListenerMock = vi.spyOn(document, 'removeEventListener');

    const mouseDownEvent = new MouseEvent('mousedown', {
      clientX: 100,
      clientY: 100,
    });

    dragHandler(element);

    element.dispatchEvent(mouseDownEvent);

    const mouseUpEvent = new MouseEvent('mouseup');
    document.dispatchEvent(mouseUpEvent);

    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'mousemove',
      expect.any(Function),
    );
    expect(removeEventListenerMock).toHaveBeenCalledWith(
      'mouseup',
      expect.any(Function),
    );
  });
});
