;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.modules
  (:refer-clojure :exclude [load resolve]))

(defmacro load
  [thing]
  `(-> (shadow.esm/load-by-name ~thing)
       (.then (fn [f#] (cljs.core/js-obj "default" (f#))))))

(defmacro load-fn
  [thing]
  `(shadow.esm/load-by-name ~thing))
