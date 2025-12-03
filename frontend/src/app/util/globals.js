/**
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

/*
 * Provide instances of some javascript global objects, by looking for
 * them in the browser, the current worker or creating a dummy for test
 * environment.
 */

"use strict";

goog.provide("app.util.globals");

goog.scope(function () {
  var self = app.util.globals;

  self.global = goog.global;

  function createMockedEventEmitter(k) {
    /* Allow mocked objects to be event emitters, so other modules
     * may subscribe to them.
     */
    return {
      addListener(...args) {},
      removeListener(...args) {},
      addEventListener(...args) {},
      removeEventListener(...args) {},
      dispatchEvent(...args) { return true; },
    };
  }

  self.event = function(name, detail) {
    const options = {};
    if (detail !== undefined) {
      options.detail = detail;
    }
    return new CustomEvent(name, options);
  };

  self.dispatch_BANG_ = function(...args) {
    self.document.dispatchEvent(...args);
  };

  self.listen = function(...args) {
    self.document.addEventListener(...args);
  };

  self.unlisten = function(...args) {
    self.document.removeEventListener(...args);
  }

  self.window = (function () {
    if (typeof goog.global.window !== "undefined") {
      return goog.global.window;
    } else {
      const mockWindow = createMockedEventEmitter();
      mockWindow.matchMedia = function (query) {
        const mediaObj = createMockedEventEmitter();
        mediaObj.matches = false;
        mediaObj.media = query;
        mediaObj.onchange = null;
        return mediaObj;
      };
      return mockWindow;
    }
  })();

  self.document = (function() {
    if (typeof goog.global.document !== "undefined") {
      return goog.global.document;
    } else {
      return createMockedEventEmitter();
    }
  })();

  self.location = (function() {
    if (typeof goog.global.location !== "undefined") {
      return goog.global.location;
    } else {
      return createMockedEventEmitter();
    }
  })();

  self.navigator = (function() {
    if (typeof goog.global.navigator !== "undefined") {
      return goog.global.navigator;
    } else {
      return createMockedEventEmitter();
    }
  })();

  self.FormData = (function() {
    if (typeof goog.global.FormData !== "undefined") {
      return goog.global.FormData;
    } else {
      return function() {};
    }
  })();
});

