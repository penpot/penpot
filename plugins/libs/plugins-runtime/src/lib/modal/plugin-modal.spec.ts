import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './plugin-modal.js';

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

describe('PluginModalElement', () => {
  let setPointerCaptureSpy: ReturnType<typeof vi.fn>;
  let releasePointerCaptureSpy: ReturnType<typeof vi.fn>;
  let hasPointerCaptureSpy: ReturnType<typeof vi.fn>;
  let originalSetPointerCapture: typeof HTMLElement.prototype.setPointerCapture;
  let originalReleasePointerCapture: typeof HTMLElement.prototype.releasePointerCapture;
  let originalHasPointerCapture: typeof HTMLElement.prototype.hasPointerCapture;

  beforeEach(() => {
    originalSetPointerCapture = HTMLElement.prototype.setPointerCapture;
    originalReleasePointerCapture = HTMLElement.prototype.releasePointerCapture;
    originalHasPointerCapture = HTMLElement.prototype.hasPointerCapture;

    setPointerCaptureSpy = vi.fn();
    releasePointerCaptureSpy = vi.fn();
    hasPointerCaptureSpy = vi.fn().mockReturnValue(true);

    Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
      configurable: true,
      value: setPointerCaptureSpy,
    });
    Object.defineProperty(HTMLElement.prototype, 'releasePointerCapture', {
      configurable: true,
      value: releasePointerCaptureSpy,
    });
    Object.defineProperty(HTMLElement.prototype, 'hasPointerCapture', {
      configurable: true,
      value: hasPointerCaptureSpy,
    });
  });

  afterEach(() => {
    document.body.innerHTML = '';
    Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
      configurable: true,
      value: originalSetPointerCapture,
    });
    Object.defineProperty(HTMLElement.prototype, 'releasePointerCapture', {
      configurable: true,
      value: originalReleasePointerCapture,
    });
    Object.defineProperty(HTMLElement.prototype, 'hasPointerCapture', {
      configurable: true,
      value: originalHasPointerCapture,
    });
    vi.restoreAllMocks();
  });

  it('should not start dragging on close button pointerdown', () => {
    const modal = document.createElement('plugin-modal');
    modal.setAttribute('title', 'Test modal');
    modal.setAttribute('iframe-src', 'about:blank');
    document.body.appendChild(modal);

    const shadow = modal.shadowRoot;
    expect(shadow).toBeTruthy();

    const wrapper = shadow?.querySelector<HTMLElement>('.wrapper');
    const closeButton = shadow?.querySelector<HTMLElement>('button');

    expect(wrapper).toBeTruthy();
    expect(closeButton).toBeTruthy();

    closeButton?.dispatchEvent(
      createPointerEvent('pointerdown', {
        pointerId: 11,
        button: 0,
      }),
    );

    expect(wrapper?.classList.contains('is-dragging')).toBe(false);
    expect(setPointerCaptureSpy).not.toHaveBeenCalled();

    modal.remove();
  });

  it('should dispatch close event when close button is clicked', () => {
    const modal = document.createElement('plugin-modal');
    modal.setAttribute('title', 'Test modal');
    modal.setAttribute('iframe-src', 'about:blank');

    const onClose = vi.fn();
    modal.addEventListener('close', onClose);
    document.body.appendChild(modal);

    const closeButton = modal.shadowRoot?.querySelector<HTMLElement>('button');
    expect(closeButton).toBeTruthy();

    closeButton?.dispatchEvent(
      new MouseEvent('click', {
        bubbles: true,
        cancelable: true,
      }),
    );

    expect(onClose).toHaveBeenCalledTimes(1);

    modal.remove();
  });
});
