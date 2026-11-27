;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.font
  (:require
   [app.common.schema :as sm]))

(def ^:private font-family-re
  ;; \p{L} (Unicode letter) works in Java regex natively, but in JavaScript it
  ;; requires the "u" flag which ClojureScript regex literals don't support.
  ;; Apostrophes are common in font names ("Suisse Int'l"); the frontend
  ;; escapes them where it writes the name into a quoted CSS string.
  #?(:clj  #"[\p{L}\d _.'\u2019-]+"
     :cljs (js/RegExp. "[\\p{L}\\d _.'\\u2019-]+" "u")))

(def schema:font-family
  [:and
   [::sm/text {:max 250}]
   [:fn {:error/code "errors.font-family-invalid-chars"}
    (fn [s] (boolean (re-matches font-family-re s)))]])
