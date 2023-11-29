;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pprint
  (:refer-clojure :exclude [prn])
  (:require
   [me.flowthing.pp :as pp]))

(defn pprint
  [expr & {:keys [width level length]
           :or {width 120 level 8 length 25}}]
  (binding [*print-level* level
            *print-length* length]
    (pp/pprint expr {:max-width width})))

(defn pprint-str
  [expr & {:as opts}]
  (with-out-str
    (pprint expr opts)))
