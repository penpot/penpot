;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pprint
  (:refer-clojure :exclude [prn])
  (:require
   [me.flowthing.pp :as pp]))

(def default-level 8)
(def default-length 25)
(def default-width 120)

#?(:clj
   (defn set-defaults
     [& {:keys [level width length]}]
     (when length
       (alter-var-root #'default-length (constantly length)))
     (when width
       (alter-var-root #'default-width (constantly width)))
     (when level
       (alter-var-root #'default-level (constantly level)))
     nil))

(defn pprint
  [expr & {:keys [width level length]
           :or {width default-width
                level default-level
                length default-length}}]
  (binding [*print-level* level
            *print-length* length]
    (pp/pprint expr {:max-width width})))

(defn pprint-str
  [expr & {:as opts}]
  (with-out-str
    (pprint expr opts)))
