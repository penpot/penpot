;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.interop
  "Interop helpers.")

(defn iterable->seq
  "Convert an es6 iterable into cljs Seq."
  [v]
  (seq (js/Array.from v)))

(defn obj-assign!
  [obj1 obj2]
  (js/Object.assign obj1 obj2))
