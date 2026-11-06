type ResizeHandlerLifecycle = {
  start?: () => void;
  end?: () => void;
};

export type ResizeLimits = {
  minWidth: number;
  minHeight: number;
  maxWidth: number;
  maxHeight: number;
};

const clamp = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), Math.max(min, max));

/**
 * Resizes `target` while `handle` is dragged: its width and height (as its
 * `width` and `height` styles set them) grow and shrink with the pointer from
 * where the drag started, within `limits`, read when it starts. Returns a
 * function that removes the listeners.
 */
export const resizeHandler = (
  handle: HTMLElement,
  target: HTMLElement,
  limits: () => ResizeLimits,
  lifecycle?: ResizeHandlerLifecycle,
) => {
  let start = { x: 0, y: 0, width: 0, height: 0 };
  let bounds: ResizeLimits = limits();
  let pointerId: number | null = null;
  let resizing = false;

  const endResize = () => {
    if (!resizing) {
      return;
    }

    resizing = false;
    pointerId = null;
    lifecycle?.end?.();
  };

  const handlePointerDown = (e: PointerEvent) => {
    if (e.button !== 0) {
      return;
    }

    e.preventDefault();
    e.stopPropagation();

    const style = getComputedStyle(target);
    start = {
      x: e.clientX,
      y: e.clientY,
      width: parseFloat(style.width) || 0,
      height: parseFloat(style.height) || 0,
    };
    bounds = limits();

    resizing = true;
    pointerId = e.pointerId;
    lifecycle?.start?.();

    handle.setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: PointerEvent) => {
    if (!resizing || e.pointerId !== pointerId) {
      return;
    }

    const width = clamp(
      start.width + e.clientX - start.x,
      bounds.minWidth,
      bounds.maxWidth,
    );
    const height = clamp(
      start.height + e.clientY - start.y,
      bounds.minHeight,
      bounds.maxHeight,
    );

    target.style.width = `${width}px`;
    target.style.height = `${height}px`;
  };

  const handlePointerUp = (e: PointerEvent) => {
    if (e.pointerId !== pointerId) {
      return;
    }

    if (handle.hasPointerCapture(e.pointerId)) {
      handle.releasePointerCapture(e.pointerId);
    }

    endResize();
  };

  const handlePointerCancel = (e: PointerEvent) => {
    if (e.pointerId !== pointerId) {
      return;
    }

    endResize();
  };

  handle.addEventListener('pointerdown', handlePointerDown);
  handle.addEventListener('pointermove', handlePointerMove);
  handle.addEventListener('pointerup', handlePointerUp);
  handle.addEventListener('pointercancel', handlePointerCancel);
  handle.addEventListener('lostpointercapture', handlePointerCancel);

  return () => {
    handle.removeEventListener('pointerdown', handlePointerDown);
    handle.removeEventListener('pointermove', handlePointerMove);
    handle.removeEventListener('pointerup', handlePointerUp);
    handle.removeEventListener('pointercancel', handlePointerCancel);
    handle.removeEventListener('lostpointercapture', handlePointerCancel);
    endResize();
  };
};
