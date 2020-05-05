;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.geom.range-tree
  (:require
   [cljs.spec.alpha :as s]))


(defn make-tree [objects])

(defn add-shape [shape])
(defn remove-shape [shape])
(defn update-shape [old-shape new-shape])

(defn query [point match-dist]) ;; Return {:x => [(point, distance, shape-id)]}



;;
