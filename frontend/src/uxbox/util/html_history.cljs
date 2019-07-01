;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.html-history
  "A singleton abstraction for the html5 fragment based history."
  (:require [goog.events :as e])
  (:import bide.impl.TokenTransformer
           goog.history.Html5History
           goog.history.EventType))

(defonce +instance+
  (doto (Html5History. nil (TokenTransformer.))
    (.setUseFragment true)
    (.setEnabled true)))

(defonce path (atom (.getToken +instance+)))

(e/listen +instance+ EventType.NAVIGATE #(reset! path (.-token %)))

(defn set-path!
  [path]
  (.setToken +instance+ path))

(defn replace-path!
  [path]
  (.replaceToken +instance+ path))
