;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.interop
  "Interop helpers.")

;; TODO: this can be optimized using es6-iterator-seq
(defn iterable->seq
  "Convert an es6 iterable into cljs Seq."
  [v]
  (seq (js/Array.from v)))

(defn obj-assign!
  ([a b]
   (js/Object.assign a b))
  ([a b & more]
   (reduce obj-assign! (obj-assign! a b) more)))

(defn obj-assoc!
  [obj attr value]
  (unchecked-set obj attr value)
  obj)
