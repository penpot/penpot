import { parseTranslate } from './parse-translate';

type DragHandlerLifecycle = {
  start?: () => void;
  end?: () => void;
};

export const dragHandler = (
  el: HTMLElement,
  target: HTMLElement = el,
  move?: () => void,
  lifecycle?: DragHandlerLifecycle,
) => {
  let initialTranslate = { x: 0, y: 0 };
  let initialClientPosition = { x: 0, y: 0 };
  let pointerId: number | null = null;
  let dragging = false;

  const endDrag = () => {
    if (!dragging) {
      return;
    }

    dragging = false;
    pointerId = null;
    lifecycle?.end?.();
  };

  const handlePointerMove = (moveEvent: PointerEvent) => {
    if (!dragging || moveEvent.pointerId !== pointerId) {
      return;
    }

    const { clientX: moveX, clientY: moveY } = moveEvent;
    const deltaX = moveX - initialClientPosition.x + initialTranslate.x;
    const deltaY = moveY - initialClientPosition.y + initialTranslate.y;

    target.style.transform = `translate(${deltaX}px, ${deltaY}px)`;

    move?.();
  };

  const handlePointerUp = (upEvent: PointerEvent) => {
    if (upEvent.pointerId !== pointerId) {
      return;
    }

    if (el.hasPointerCapture(upEvent.pointerId)) {
      el.releasePointerCapture(upEvent.pointerId);
    }

    endDrag();
  };

  const handlePointerCancel = (cancelEvent: PointerEvent) => {
    if (cancelEvent.pointerId !== pointerId) {
      return;
    }

    endDrag();
  };

  const handleLostPointerCapture = (lostCaptureEvent: PointerEvent) => {
    if (lostCaptureEvent.pointerId !== pointerId) {
      return;
    }

    endDrag();
  };

  const handlePointerDown = (e: PointerEvent) => {
    if (e.button !== 0) {
      return;
    }

    const fromButton =
      e.target instanceof Element && !!e.target.closest('button');

    if (fromButton) {
      return;
    }

    e.preventDefault();

    initialClientPosition = { x: e.clientX, y: e.clientY };
    initialTranslate = parseTranslate(target);

    dragging = true;
    pointerId = e.pointerId;
    lifecycle?.start?.();

    el.setPointerCapture(e.pointerId);
  };

  el.addEventListener('pointerdown', handlePointerDown);
  el.addEventListener('pointermove', handlePointerMove);
  el.addEventListener('pointerup', handlePointerUp);
  el.addEventListener('pointercancel', handlePointerCancel);
  el.addEventListener('lostpointercapture', handleLostPointerCapture);

  return () => {
    el.removeEventListener('pointerdown', handlePointerDown);
    el.removeEventListener('pointermove', handlePointerMove);
    el.removeEventListener('pointerup', handlePointerUp);
    el.removeEventListener('pointercancel', handlePointerCancel);
    el.removeEventListener('lostpointercapture', handleLostPointerCapture);
    endDrag();
  };
};
