/**
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) UXBOX Labs SL
 */

/*
 * Provide instances of some javascript global objects, by looking for
 * them in the browser, the current worker or creating a dummy for test
 * environment.
 */

"use strict";

goog.provide("app.util.globals");

goog.scope(function() {
  app.util.globals.global = goog.global;

  function createGlobalEventEmitter(k) {
    /* Allow mocked objects to be event emitters, so other modules
     * may subscribe to them.
     */
    return {
      addListener(...args) {
      },
      removeListener(...args) {
      }
    }
  }

  app.util.globals.window = (function() {
    if (typeof goog.global.window !== "undefined") {
      return goog.global.window;
    } else {
      return createGlobalEventEmitter();
    }
  })();

  app.util.globals.document = (function() {
    if (typeof goog.global.document !== "undefined") {
      return goog.global.document;
    } else {
      return createGlobalEventEmitter();
    }
  })();

  app.util.globals.location = (function() {
    if (typeof goog.global.location !== "undefined") {
      return goog.global.location;
    } else {
      return createGlobalEventEmitter();
    }
  })();

  app.util.globals.navigator = (function() {
    if (typeof goog.global.navigator !== "undefined") {
      return goog.global.navigator;
    } else {
      return createGlobalEventEmitter();
    }
  })();

  app.util.globals.FormData = (function() {
    if (typeof goog.global.FormData !== "undefined") {
      return goog.global.FormData;
    } else {
      return function() {};
    }
  })();
});

