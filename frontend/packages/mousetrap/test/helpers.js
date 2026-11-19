import { Mousetrap } from "../index.js";

/**
 * Fake event target capturing listeners. The Mousetrap class only needs
 * `addEventListener`, so tests run in plain Node without a DOM.
 */
export function makeTarget() {
  const listeners = {};
  const target = {
    listeners,
    addEventListener(type, callback) {
      listeners[type] = listeners[type] || [];
      listeners[type].push(callback);
    },
  };
  return target;
}

/**
 * Fake focused element for `stopCallback`. Covers every property the
 * implementation reads: dataset, className, tagName, contentEditable.
 */
export function makeElement(over = {}) {
  return {
    dataset: {},
    className: "",
    tagName: "DIV",
    contentEditable: "false",
    ...over,
  };
}

/**
 * Synthetic keyboard event with sane defaults. Override any field per
 * case (`key`, `code`, `shiftKey`, ...). The `target` defaults to a plain
 * DIV so `stopCallback` lets the event through.
 */
export function makeKeyEvent(over = {}) {
  return {
    type: "keydown",
    key: "a",
    code: "KeyA",
    shiftKey: false,
    ctrlKey: false,
    altKey: false,
    metaKey: false,
    target: makeElement(),
    ...over,
  };
}

/**
 * Dispatches a synthetic event through every listener registered for
 * its type on a fake target.
 */
export function fire(target, event) {
  for (const callback of target.listeners[event.type] || []) {
    callback(event);
  }
}

/**
 * Event spies recording `preventDefault`/`stopPropagation` calls for the
 * `callback → false` convention.
 */
export function makeSpiedEvent(over = {}) {
  const calls = { preventDefault: 0, stopPropagation: 0 };
  const event = makeKeyEvent({
    preventDefault() {
      calls.preventDefault++;
    },
    stopPropagation() {
      calls.stopPropagation++;
    },
    ...over,
  });
  return { event, calls };
}

/**
 * Fresh isolated instance plus its fake target. Timers used by sequences
 * are real by default; tests needing determinism enable `mock.timers`
 * themselves and restore it afterwards.
 */
export function freshInstance() {
  const target = makeTarget();
  const mousetrap = new Mousetrap(target);
  return { mousetrap, target };
}
