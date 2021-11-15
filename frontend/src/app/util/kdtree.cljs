;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.kdtree
  "A cljs layer on top of js impl of kdtree located in `kdtree_impl.js`."
  (:require [app.util.kdtree-impl :as impl]))

(defn create
  "Create an empty or initialized kd-tree instance."
  ([] (impl/create))
  ([points] (impl/create (clj->js points))))

(defn setup!
  "Generate new kd-tree instance with provided generation parameter
  or just return a previously created from internal LRU cache."
  [t w h ws hs]
  (impl/setup t w h ws hs))

(defn nearest
  "Search nearest points to the provided point
  and return the `n` maximum results."
  ([t p]
   (nearest t p 10))
  ([t p n]
   {:pre [(vector? p)]}
   (let [p (into-array p)]
     (map clj->js (impl/nearest t p n)))))
