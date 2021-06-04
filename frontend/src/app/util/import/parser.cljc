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
   [app.common.uuid :as uuid]
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

(defn get-data [node]
  (->> node :content (d/seek #(= :penpot:shape (:tag %)))))

(defn get-type
  [node]
  (if (close? node)
    (second node)
    (let [data (get-data node)]
      (-> (get-in data [:attrs :penpot:type])
          (keyword)))))

(defn shape?
  [node]
  (or (close? node)
      (some? (get-data node))))

(defn get-meta
  ([m att]
   (get-meta m att identity))
  ([m att val-fn]
   (let [ns-att (->> att d/name (str "penpot:") keyword)
         val (or (get-in m [:attrs ns-att])
                 (get-in (get-data m) [:attrs ns-att]))]
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

(defn get-svg-data
  [type node]

  (if (search-data-node? type)
    (let [data-tags #{:ellipse :rect :path :text :foreignObject :image}]
      (->> node
           (node-seq)
           (filter #(contains? data-tags (:tag %)))
           (map #(:attrs %))
           (reduce add-attrs {})))
    (:attrs node)))

(def has-position? #{:frame :rect :image :text})

(defn parse-position
  [props svg-data]
  (let [values (->> (select-keys svg-data [:x :y :width :height])
                    (d/mapm (fn [_ val] (d/parse-double val))))]
    (d/merge props values)))

(defn parse-circle
  [props svg-data]
  (let [values (->> (select-keys svg-data [:cx :cy :rx :ry])
                    (d/mapm (fn [_ val] (d/parse-double val))))]

    {:x (- (:cx values) (:rx values))
     :y (- (:cy values) (:ry values))
     :width (* (:rx values) 2)
     :height (* (:ry values) 2)}))

(defn parse-path
  [props svg-data]
  (let [content (upp/parse-path (:d svg-data))
        selrect (gsh/content->selrect content)
        points (gsh/rect->points selrect)]

    (-> props
        (assoc :content content)
        (assoc :selrect selrect)
        (assoc :points points))))

(def url-regex #"url\(#([^\)]*)\)")

(defn seek-node
  [id coll]
  (->> coll (d/seek #(= id (-> % :attrs :id)))))

(defn parse-stops
  [gradient-node]
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
  [props type node svg-data]
  (cond-> props
    (has-position? type)
    (-> (parse-position svg-data)
        (gsh/setup-selrect))

    (= type :circle)
    (-> (parse-circle svg-data)
        (gsh/setup-selrect))

    (= type :path)
    (parse-path svg-data)))

(defn add-fill
  [props type node svg-data]

  (let [fill (:fill svg-data)]
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
             :fill-opacity (-> svg-data (:fill-opacity "1") d/parse-double)))))

(defn add-stroke
  [props type node svg-data]

  (let [stroke-style (get-meta node :stroke-style keyword)
        stroke-alignment (get-meta node :stroke-alignment keyword)
        stroke (:stroke svg-data)]

    (cond-> props
      :always
      (assoc :stroke-alignment stroke-alignment
             :stroke-style     stroke-style
             :stroke-color     (-> svg-data (:stroke "#000000"))
             :stroke-opacity   (-> svg-data (:stroke-opacity "1") d/parse-double)
             :stroke-width     (-> svg-data (:stroke-width "0") d/parse-double))

      (str/starts-with? stroke "url")
      (assoc :stroke-color-gradient (parse-gradient node stroke)
             :stroke-color nil
             :stroke-opacity nil)

      (= stroke-alignment :inner)
      (update :stroke-width / 2))))

(defn add-image-data
  [props node]
  (-> props
      (assoc-in [:metadata :id]     (get-meta node :media-id))
      (assoc-in [:metadata :width]  (get-meta node :media-width))
      (assoc-in [:metadata :height] (get-meta node :media-height))
      (assoc-in [:metadata :mtype]  (get-meta node :media-mtype))))

(defn add-text-data
  [props node]
  (-> props
      (assoc :grow-type (get-meta node :grow-type keyword))
      (assoc :content   (get-meta node :content json/decode))))

(defn str->bool
  [val]
  (= val "true"))

(defn add-group-data
  [props node]
  (let [mask? (get-meta node :masked-group str->bool)]
    (cond-> props
      mask?
      (assoc :masked-group? true))))

(defn parse-shadow [node]
  {:id       (uuid/next)
   :style    (get-meta node :shadow-type keyword)
   :hidden   (get-meta node :hidden str->bool)
   :color    {:color (get-meta node :color)
              :opacity (get-meta node :opacity d/parse-double)}
   :offset-x (get-meta node :offset-x d/parse-double)
   :offset-y (get-meta node :offset-y d/parse-double)
   :blur     (get-meta node :blur d/parse-double)
   :spread   (get-meta node :spread d/parse-double)})

(defn parse-blur [node]
  {:id       (uuid/next)
   :type     (get-meta node :blur-type keyword)
   :hidden   (get-meta node :hidden str->bool)
   :value    (get-meta node :value d/parse-double)})

(defn parse-export [node]
  {:type   (get-meta node :type keyword)
   :suffix (get-meta node :suffix)
   :scale  (get-meta node :scale d/parse-double)})

(defn extract-from-data [node tag parse-fn]
  (let [shape-data (get-data node)]
    (->> shape-data
         (node-seq)
         (filter #(= (:tag %) tag))
         (mapv parse-fn))))

(defn add-shadows
  [props node]
  (let [shadows (extract-from-data node :penpot:shadow parse-shadow)]
    (cond-> props
      (not (empty? shadows))
      (assoc :shadow shadows))))

(defn add-blur
  [props node]
  (let [blur (->> (extract-from-data node :penpot:blur parse-blur) (first))]
    (cond-> props
      (some? blur)
      (assoc :blur blur))))

(defn add-exports
  [props node]
  (let [exports (extract-from-data node :penpot:export parse-export)]
    (cond-> props
      (not (empty? exports))
      (assoc :exports exports))))

(defn get-image-name
  [node]
  (get-in node [:attrs :penpot:name]))

(defn get-image-data
  [node]
  (let [svg-data (get-svg-data :image node)]
    (:xlink:href svg-data)))

(defn parse-data
  [type node]

  (when-not (close? node)
    (let [name              (get-meta node :name)
          blocked           (get-meta node :blocked str->bool)
          hidden            (get-meta node :hidden str->bool)
          transform         (get-meta node :transform gmt/str->matrix)
          transform-inverse (get-meta node :transform-inverse gmt/str->matrix)
          svg-data          (get-svg-data type node)]

      (-> {}
          (add-position type node svg-data)
          (add-fill type node svg-data)
          (add-stroke type node svg-data)
          (add-shadows node)
          (add-blur node)
          (add-exports node)
          (assoc :name name)
          (assoc :blocked blocked)
          (assoc :hidden hidden)

          (cond-> (= :group type)
            (add-group-data node))

          (cond-> (= :image type)
            (add-image-data node))

          (cond-> (= :text type)
            (add-text-data node))

          (cond-> (some? transform)
            (assoc :transform transform))

          (cond-> (some? transform-inverse)
            (assoc :transform-inverse transform-inverse))))))
