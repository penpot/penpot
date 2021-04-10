;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.text
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.util.transit :as t]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

(def default-text-attrs
  {:typography-ref-file nil
   :typography-ref-id nil
   :font-id "sourcesanspro"
   :font-family "sourcesanspro"
   :font-variant-id "regular"
   :font-size "14"
   :font-weight "400"
   :font-style "normal"
   :line-height "1.2"
   :letter-spacing "0"
   :text-transform "none"
   :text-align "left"
   :text-decoration "none"
   :fill-color nil
   :fill-opacity 1})

(def typography-fields
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style
   :line-height
   :letter-spacing
   :text-transform])

(def default-typography
  (merge
   {:name "Source Sans Pro Regular"}
   (select-keys default-text-attrs typography-fields)))

(defn transform-nodes
  ([transform root]
   (transform-nodes identity transform root))
  ([pred transform root]
   (walk/postwalk
    (fn [item]
      (if (and (map? item) (pred item))
        (transform item)
        item))
    root)))

(defn node-seq
  ([root] (node-seq identity root))
  ([match? root]
   (->> (tree-seq map? :children root)
        (filter match?)
        (seq))))

(defn ^boolean is-text-node?
  [node]
  (string? (:text node)))

(defn ^boolean is-paragraph-node?
  [node]
  (= "paragraph" (:type node)))

(defn ^boolean is-root-node?
  [node]
  (= "root" (:type node)))
