/**
 * Early registry for host-facing frontend audit event callbacks.
 *
 * Loaded before config.js so SaaS / local config can call
 * `penpotAddEventCallback(name, fn)` at parse time. The ClojureScript
 * app reads `penpotEventCallbacks` when prepared audit events fire and
 * leaves a host-provided `penpotAddEventCallback` alone when present.
 */
(function (g) {
  "use strict";

  if (!(g.penpotEventCallbacks instanceof Map)) {
    g.penpotEventCallbacks = new Map();
  }

  if (typeof g.penpotAddEventCallback !== "function") {
    g.penpotAddEventCallback = function (name, callback) {
      var m = g.penpotEventCallbacks;
      var entry = m.get(name);

      if (entry == null) {
        m.set(name, [callback]);
      } else if (Array.isArray(entry)) {
        entry.push(callback);
      } else if (typeof entry === "function") {
        m.set(name, [entry, callback]);
      } else {
        m.set(name, [callback]);
      }
    };
  }
})(globalThis);
