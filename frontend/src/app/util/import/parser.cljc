;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.import.parser
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes :as gsh]
   [cuerdas.core :as str]
   [app.util.path.parser :as upp]))

(defn valid?
  [root]
  (contains? (:attrs root) :xmlns:penpot))

(defn branch?
  [node]
  (and (contains? node :content)
       (some? (:content node))))

(defn close?
  [node]
  (and (vector? node)
       (= ::close (first node))))

(defn get-type
  [node]
  (if (close? node)
    (second node)
    (-> (get-in node [:attrs :penpot:type])
        (keyword))))

(defn shape?
  [node]
  (or (close? node)
      (contains? (:attrs node) :penpot:type)))

(defn get-attr
  ([m att]
   (get-attr m att identity))
  ([m att val-fn]
   (let [ns-att (->> att d/name (str "penpot:") keyword)
         val (get-in m [:attrs ns-att])]
     (when val (val-fn val)))))

(defn get-children
  [node]
  (cond-> (:content node)
    ;; We add a "fake" node to know when we are leaving the shape children
    (shape? node)
    (conj [::close (get-type node)])))

(defn node-seq
  [content]
  (->> content (tree-seq branch? get-children)))

(defn get-transform
  [type node])

(defn parse-style
  "Transform style list into a map"
  [style-str]
  (if (string? style-str)
    (->> (str/split style-str ";")
         (map str/trim)
         (map #(str/split % ":"))
         (group-by first)
         (map (fn [[key val]]
                (vector (keyword key) (second (first val)))))
         (into {}))
    style-str))

(defn add-attrs
  [m attrs]
  (reduce-kv
   (fn [m k v]
     (if (#{:style :data-style} k)
       (assoc m :style (parse-style v))
       (assoc m k v)))
   m
   attrs))

(defn get-data-node
  [node]

  (let [data-tags #{:ellipse :rect :path}]
    (->> node
         (node-seq)
         (filter #(contains? data-tags (:tag %)))
         (map #(:attrs %))
         (reduce add-attrs {}))))

(def search-data-node? #{:rect :image :path :text :circle})
(def has-position? #{:frame :rect :image :text})

(defn parse-position
  [props data]
  (let [values (->> (select-keys data [:x :y :width :height])
                    (d/mapm (fn [_ val] (d/parse-double val))))]
    (d/merge props values)))

(defn parse-circle
  [props data]
  (let [values (->> (select-keys data [:cx :cy :rx :ry])
                    (d/mapm (fn [_ val] (d/parse-double val))))]

    {:x (- (:cx values) (:rx values))
     :y (- (:cy values) (:ry values))
     :width (* (:rx values) 2)
     :height (* (:ry values) 2)}))

(defn parse-path
  [props data]
  (let [content (upp/parse-path (:d data))
        selrect (gsh/content->selrect content)
        points (gsh/rect->points selrect)]

    (-> props
        (assoc :content content)
        (assoc :selrect selrect)
        (assoc :points points))))

(defn extract-data
  [type node]
  (let [data (if (search-data-node? type)
               (get-data-node node)
               (:attrs node))]
    (cond-> {}
      (has-position? type)
      (-> (parse-position data)
          (gsh/setup-selrect))

      (= type :circle)
      (-> (parse-circle data)
          (gsh/setup-selrect))

      (= type :path)
      (parse-path data))))

(defn str->bool
  [val]
  (= val "true"))

(defn parse-data
  [type node]

  (when-not (close? node)
    (let [name              (get-attr node :name)
          blocked           (get-attr node :blocked str->bool)
          hidden            (get-attr node :hidden str->bool)
          transform         (get-attr node :transform gmt/str->matrix)
          transform-inverse (get-attr node :transform-inverse gmt/str->matrix)]

      (-> (extract-data type node)
          (assoc :name name)
          (assoc :blocked blocked)
          (assoc :hidden hidden)
          (cond-> (some? transform)
            (assoc :transform transform))
          (cond-> (some? transform-inverse)
            (assoc :transform-inverse transform-inverse))))))
