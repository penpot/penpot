/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 *
 * Copyright (c) 2020 UXBOX Labs SL
 */

"use strict";

goog.provide("uxbox.util.browser_history");
goog.require("goog.history.Html5History");


goog.scope(function() {
  const self = uxbox.util.browser_history;
  const Html5History = goog.history.Html5History;

  class TokenTransformer {
    retrieveToken(pathPrefix, location) {
      return location.pathname.substr(pathPrefix.length) + location.search;
    }

    createUrl(token, pathPrefix, location) {
      return pathPrefix + token;
    }
  }

  self.create = function() {
    const instance = new Html5History(null, new TokenTransformer());
    instance.setUseFragment(true);
    return instance;
  };

  self.enable_BANG_ = function(instance) {
    instance.setEnabled(true);
  };

  self.disable_BANG_ = function(instance) {
    instance.setEnabled(false);
  };

  self.set_token_BANG_ = function(instance, token) {
    instance.setToken(token);
  }

  self.replace_token_BANG_ = function(instance, token) {
    instance.replaceToken(token);
  }
});
