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
   [app.util.color :as uc]
   [app.util.json :as json]
   [app.util.path.parser :as upp]
   [cuerdas.core :as str]))

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

(defn get-meta
  ([m att]
   (get-meta m att identity))
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
       (merge m (parse-style v))
       (assoc m k v)))
   m
   attrs))

(def search-data-node? #{:rect :image :path :text :circle})

(defn get-shape-data
  [type node]

  (if (search-data-node? type)
    (let [data-tags #{:ellipse :rect :path :text :foreignObject}]
      (->> node
           (node-seq)
           (filter #(contains? data-tags (:tag %)))
           (map #(:attrs %))
           (reduce add-attrs {})))
    (:attrs node)))

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

(def url-regex #"url\(#([^\)]*)\)")

(defn seek-node [id coll]
  (->> coll (d/seek #(= id (-> % :attrs :id)))))

(defn parse-stops [gradient-node]
  (->> gradient-node
       (node-seq)
       (filter #(= :stop (:tag %)))
       (mapv (fn [{{:keys [offset stop-color stop-opacity]} :attrs}]
               {:color stop-color
                :opacity (d/parse-double stop-opacity)
                :offset (d/parse-double offset)}))))

(defn parse-gradient
  [node ref-url]
  (let [[_ url] (re-matches url-regex ref-url)
        gradient-node (->> node (node-seq) (seek-node url))
        stops (parse-stops gradient-node)]

    (cond-> {:stops stops}
      (= :linearGradient (:tag gradient-node))
      (assoc :type :linear
             :start-x (-> gradient-node :attrs :x1 d/parse-double)
             :start-y (-> gradient-node :attrs :y1 d/parse-double)
             :end-x   (-> gradient-node :attrs :x2 d/parse-double)
             :end-y   (-> gradient-node :attrs :y2 d/parse-double)
             :width   1)

      (= :radialGradient (:tag gradient-node))
      (assoc :type :radial
             :start-x (get-meta gradient-node :start-x d/parse-double)
             :start-y (get-meta gradient-node :start-y d/parse-double)
             :end-x   (get-meta gradient-node :end-x   d/parse-double)
             :end-y   (get-meta gradient-node :end-y   d/parse-double)
             :width   (get-meta gradient-node :width   d/parse-double)))))

(defn add-position
  [props type node data]
  (cond-> props
    (has-position? type)
    (-> (parse-position data)
        (gsh/setup-selrect))

    (= type :circle)
    (-> (parse-circle data)
        (gsh/setup-selrect))

    (= type :path)
    (parse-path data)))

(defn add-fill
  [props type node data]

  (let [fill (:fill data)]
    (cond-> props
      (= fill "none")
      (assoc :fill-color nil
             :fill-opacity nil)

      (str/starts-with? fill "url")
      (assoc :fill-color-gradient (parse-gradient node fill)
             :fill-color nil
             :fill-opacity nil)

      (uc/hex? fill)
      (assoc :fill-color fill
             :fill-opacity (-> data (:fill-opacity "1") d/parse-double)))))

(defn add-stroke
  [props type node data]

  (let [stroke-style (get-meta node :stroke-style keyword)
        stroke-alignment (get-meta node :stroke-alignment keyword)
        stroke (:stroke data)]

    (cond-> props
      :always
      (assoc :stroke-alignment stroke-alignment
             :stroke-style stroke-style
             :stroke-color (-> data (:stroke "#000000"))
             :stroke-opacity (-> data (:stroke-opacity "1") d/parse-double)
             :stroke-width (-> data (:stroke-width "0") d/parse-double))

      (str/starts-with? stroke "url")
      (assoc :stroke-color-gradient (parse-gradient node stroke)
             :stroke-color nil
             :stroke-opacity nil)

      (= stroke-alignment :inner)
      (update :stroke-width / 2))))

(defn add-text-data
  [props node]
  (-> props
      (assoc :grow-type (get-meta node :grow-type keyword))
      (assoc :content (get-meta node :content json/decode))))

(defn str->bool
  [val]
  (= val "true"))

(defn parse-data
  [type node]

  (when-not (close? node)
    (let [name              (get-meta node :name)
          blocked           (get-meta node :blocked str->bool)
          hidden            (get-meta node :hidden str->bool)
          transform         (get-meta node :transform gmt/str->matrix)
          transform-inverse (get-meta node :transform-inverse gmt/str->matrix)
          data              (get-shape-data type node)]

      (-> {}
          (add-position type node data)
          (add-fill type node data)
          (add-stroke type node data)
          (assoc :name name)
          (assoc :blocked blocked)
          (assoc :hidden hidden)

          (cond-> (= :text type)
            (add-text-data node))

          (cond-> (some? transform)
            (assoc :transform transform))

          (cond-> (some? transform-inverse)
            (assoc :transform-inverse transform-inverse))))))
