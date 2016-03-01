;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.syntax
  (:refer-clojure :exclude [defonce]))

(defmacro define-once
  [name' & body]
  (let [sym (symbol (str (namespace name') "-" (name name')))]
    `(cljs.core/defonce ~sym
       (do ~@body nil))))

(defmacro defer
  [& body]
  `(let [func# (fn [] ~@body)]
     (js/setTimeout func# 0)))
