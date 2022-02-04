;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.import.parser
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.spec.interactions :as cti]
   [app.common.uuid :as uuid]
   [app.util.color :as uc]
   [app.util.json :as json]
   [app.util.path.parser :as upp]
   [cuerdas.core :as str]))

(def url-regex
  #"url\(#([^\)]*)\)")

(def uuid-regex
  #"\w{8}-\w{4}-\w{4}-\w{4}-\w{12}")

(def uuid-regex-prefix
  #"\w{8}-\w{4}-\w{4}-\w{4}-\w{12}-")

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

(defn find-node
  [node tag]
  (when (some? node)
    (->> node :content (d/seek #(= (:tag %) tag)))))

(defn find-node-by-id
  [id coll]
  (->> coll (d/seek #(= id (-> % :attrs :id)))))

(defn find-all-nodes
  [node tag]
  (when (some? node)
    (->> node :content (filterv #(= (:tag %) tag)))))

(defn get-data
  ([node]
   (or (find-node node :penpot:shape)
       (find-node node :penpot:page)))

   ([node tag]
    (-> (get-data node)
        (find-node tag))))

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

(defn get-id
  [node]
  (let [attr-id (get-in node [:attrs :id])
        id (when (string? attr-id) (re-find uuid-regex attr-id))]
    (when (some? id)
      (uuid/uuid id))))

(defn str->bool
  [val]
  (when (some? val) (= val "true")))

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

(defn without-penpot-prefix
  [m]
  (let [no-penpot-prefix?
        (fn [[k _]]
          (not (str/starts-with? (d/name k) "penpot:")))]
    (into {} (filter no-penpot-prefix?) m)))

(defn remove-penpot-prefix
  [m]
  (into {}
        (map (fn [[k v]]
               (if (str/starts-with? (d/name k) "penpot:")
                 [(-> k d/name (str/replace "penpot:" "") keyword) v]
                 [k v])))
        m))

(defn camelize [[k v]]
  [(-> k d/name str/camel keyword) v])

(defn camelize-keys
  [m]
  (assert (map? m) (str m))

  (into {} (map camelize) m))

(defn fix-style-attr
  [m]
  (let [fix-style
        (fn [[k v]]
          (if (= k :style)
            [k (-> v parse-style camelize-keys)]
            [k v]))]

    (d/deep-mapm (comp camelize fix-style) m)))

(defn string->uuid
  "Looks in a map for keys or values that have uuid shape and converts them
  into uuid objects"
  [m]
  (letfn [(convert [value]
            (cond
              (and (string? value) (re-matches uuid-regex value))
              (uuid/uuid value)

              (and (keyword? value) (re-matches uuid-regex (d/name value)))
              (uuid/uuid (d/name value))

              (vector? value)
              (mapv convert value)

              :else
              value))]
    (->> m
         (d/deep-mapm
          (fn [pair] (->> pair (mapv convert)))))))

(def search-data-node? #{:rect :image :path :text :circle})

(defn get-svg-data
  [type node]

  (let [node-attrs (add-attrs {} (:attrs node))]
    (cond
      (search-data-node? type)
      (let [data-tags #{:ellipse :rect :path :text :foreignObject :image}]
        (->> node
             (node-seq)
             (filter #(contains? data-tags (:tag %)))
             (map #(:attrs %))
             (reduce add-attrs node-attrs)))

      (= type :frame)
      (let [svg-node (->> node :content (d/seek #(= "frame-background" (get-in % [:attrs :class]))))]
        (merge (add-attrs {} (:attrs svg-node)) node-attrs))

      (= type :svg-raw)
      (let [svg-content (get-data node :penpot:svg-content)
            tag (-> svg-content :attrs :penpot:tag keyword)

            svg-node (if (= :svg tag)
                       (->> node :content last :content last)
                       (->> node :content last))
            svg-node (d/update-in-when svg-node [:attrs :style] parse-style)]
        (merge (add-attrs {} (:attrs svg-node)) node-attrs))

      (= type :bool)
      (->> node
           (:content)
           (filter #(= :path (:tag %)))
           (map #(:attrs %))
           (reduce add-attrs node-attrs))

      :else
      node-attrs)))

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
    (-> props
        (assoc :x (- (:cx values) (:rx values))
               :y (- (:cy values) (:ry values))
               :width (* (:rx values) 2)
               :height (* (:ry values) 2)))))

(defn parse-path
  [props center svg-data]
  (let [content           (upp/parse-path (:d svg-data))]
    (-> props
        (assoc :content content)
        (assoc :center center))))

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
  (let [[_ url] (re-find url-regex ref-url)
        gradient-node (->> node (node-seq) (find-node-by-id url))
        stops (parse-stops gradient-node)]

    (when (contains? (:attrs gradient-node) :penpot:gradient)
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
               :width   (get-meta gradient-node :width   d/parse-double))))))

(defn add-svg-position [props node]
  (let [svg-content (get-data node :penpot:svg-content)]
    (cond-> props
      (contains? (:attrs svg-content) :penpot:x)
      (assoc :x (-> svg-content :attrs :penpot:x d/parse-double))

      (contains? (:attrs svg-content) :penpot:y)
      (assoc :y (-> svg-content :attrs :penpot:y d/parse-double))

      (contains? (:attrs svg-content) :penpot:width)
      (assoc :width (-> svg-content :attrs :penpot:width d/parse-double))

      (contains? (:attrs svg-content) :penpot:height)
      (assoc :height (-> svg-content :attrs :penpot:height d/parse-double)))))

(defn add-common-data
  [props node]

  (let [name              (get-meta node :name)
        blocked           (get-meta node :blocked str->bool)
        hidden            (get-meta node :hidden str->bool)
        transform         (get-meta node :transform gmt/str->matrix)
        transform-inverse (get-meta node :transform-inverse gmt/str->matrix)
        flip-x            (get-meta node :flip-x str->bool)
        flip-y            (get-meta node :flip-y str->bool)
        proportion        (get-meta node :proportion d/parse-double)
        proportion-lock   (get-meta node :proportion-lock str->bool)
        rotation          (get-meta node :rotation d/parse-double)
        constraints-h     (get-meta node :constraints-h keyword)
        constraints-v     (get-meta node :constraints-v keyword)
        fixed-scroll      (get-meta node :fixed-scroll str->bool)]

    (-> props
        (assoc :name name)
        (assoc :blocked blocked)
        (assoc :hidden hidden)
        (assoc :flip-x flip-x)
        (assoc :flip-y flip-y)
        (assoc :proportion proportion)
        (assoc :proportion-lock proportion-lock)
        (assoc :rotation rotation)

        (cond-> (some? transform)
          (assoc :transform transform))

        (cond-> (some? transform-inverse)
          (assoc :transform-inverse transform-inverse))

        (cond-> (some? constraints-h)
          (assoc :constraints-h constraints-h))

        (cond-> (some? constraints-v)
          (assoc :constraints-v constraints-v))

        (cond-> (some? fixed-scroll)
          (assoc :fixed-scroll fixed-scroll)))))

(defn add-position
  [props type node svg-data]
  (let [center-x (get-meta node :center-x d/parse-double)
        center-y (get-meta node :center-y d/parse-double)
        center (gpt/point center-x center-y)]
    (cond-> props
      (has-position? type)
      (parse-position svg-data)

      (= type :svg-raw)
      (add-svg-position node)

      (= type :circle)
      (parse-circle svg-data)

      (= type :path)
      (parse-path center svg-data))))

(defn add-library-refs
  [props node]

  (let [stroke-color-ref-id   (get-meta node :stroke-color-ref-id uuid/uuid)
        stroke-color-ref-file (get-meta node :stroke-color-ref-file uuid/uuid)
        component-id          (get-meta node :component-id uuid/uuid)
        component-file        (get-meta node :component-file uuid/uuid)
        shape-ref             (get-meta node :shape-ref uuid/uuid)
        component-root?       (get-meta node :component-root str->bool)]

    (cond-> props
      (some? stroke-color-ref-id)
      (assoc :stroke-color-ref-id stroke-color-ref-id
             :stroke-color-ref-file stroke-color-ref-file)

      (some? component-id)
      (assoc :component-id component-id
             :component-file component-file)

      component-root?
      (assoc :component-root? component-root?)

      (some? shape-ref)
      (assoc :shape-ref shape-ref))))

(defn add-fill
  [props node svg-data]

  (let [fill (:fill svg-data)
        hide-fill-on-export (get-meta node :hide-fill-on-export str->bool)
        fill-color-ref-id     (get-meta node :fill-color-ref-id uuid/uuid)
        fill-color-ref-file   (get-meta node :fill-color-ref-file uuid/uuid)
        gradient (when (str/starts-with? fill "url")
                   (parse-gradient node fill))]

    (cond-> props
      :always
      (assoc :fill-color nil
             :fill-opacity nil)

      (some? gradient)
      (assoc :fill-color-gradient gradient
             :fill-color nil
             :fill-opacity nil)

      (uc/hex? fill)
      (assoc :fill-color fill
             :fill-opacity (-> svg-data (:fill-opacity "1") d/parse-double))

      (some? hide-fill-on-export)
      (assoc :hide-fill-on-export hide-fill-on-export)

      (some? fill-color-ref-id)
      (assoc :fill-color-ref-id fill-color-ref-id
             :fill-color-ref-file fill-color-ref-file))))

(defn add-stroke
  [props node svg-data]

  (let [stroke-style (get-meta node :stroke-style keyword)
        stroke-alignment (get-meta node :stroke-alignment keyword)
        stroke (:stroke svg-data)
        gradient (when (str/starts-with? stroke "url")
                   (parse-gradient node stroke))
        stroke-cap-start (get-meta node :stroke-cap-start keyword)
        stroke-cap-end (get-meta node :stroke-cap-end keyword)]

    (cond-> props
      :always
      (assoc :stroke-alignment stroke-alignment
             :stroke-style     stroke-style
             :stroke-color     (-> svg-data :stroke)
             :stroke-opacity   (-> svg-data :stroke-opacity d/parse-double)
             :stroke-width     (-> svg-data :stroke-width d/parse-double))

      (some? gradient)
      (assoc :stroke-color-gradient  gradient
             :stroke-color nil
             :stroke-opacity nil)

      (= stroke-alignment :inner)
      (update :stroke-width / 2)

      (some? stroke-cap-start)
      (assoc :stroke-cap-start stroke-cap-start)

      (some? stroke-cap-end)
      (assoc :stroke-cap-end stroke-cap-end))))

(defn add-rect-data
  [props node svg-data]
  (let [r1 (get-meta node :r1 d/parse-double)
        r2 (get-meta node :r2 d/parse-double)
        r3 (get-meta node :r3 d/parse-double)
        r4 (get-meta node :r4 d/parse-double)

        rx (-> (get svg-data :rx) d/parse-double)
        ry (-> (get svg-data :ry) d/parse-double)]

    (cond-> props
      (some? r1)
      (assoc :r1 r1 :r2 r2 :r3 r3 :r4 r4
             :rx nil :ry nil)

      (and (nil? r1) (some? rx))
      (assoc :rx rx :ry ry))))

(defn add-image-data
  [props type node]
  (let [metadata {:id     (get-meta node :media-id)
                  :width  (get-meta node :media-width)
                  :height (get-meta node :media-height)
                  :mtype  (get-meta node :media-mtype)}]
    (cond-> props
      (= type :image)
      (assoc :metadata metadata)

      (not= type :image)
      (assoc :fill-image metadata))))

(defn add-text-data
  [props node]
  (-> props
      (assoc :grow-type (get-meta node :grow-type keyword))
      (assoc :content   (get-meta node :content (comp string->uuid json/decode)))))

(defn add-group-data
  [props node]
  (let [mask? (get-meta node :masked-group str->bool)]
    (cond-> props
      mask?
      (assoc :masked-group? true))))

(defn add-bool-data
  [props node]
  (-> props
      (assoc :bool-type (get-meta node :bool-type keyword))))

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

(defn parse-grid-node [node]
  (let [attrs (-> node :attrs remove-penpot-prefix)
        color {:color (:color attrs)
               :opacity (-> attrs :opacity d/parse-double)}

        params (-> (dissoc attrs :color :opacity :display :type)
                   (d/update-when :size d/parse-double)
                   (d/update-when :item-length d/parse-double)
                   (d/update-when :gutter d/parse-double)
                   (d/update-when :margin d/parse-double)
                   (assoc :color color))]
    {:type    (-> attrs :type keyword)
     :display (-> attrs :display str->bool)
     :params  params}))

(defn parse-grids [node]
  (let [grids-node (get-data node :penpot:grids)]
    (->> grids-node :content (mapv parse-grid-node))))

(defn parse-flow-node [node]
  (let [attrs (-> node :attrs remove-penpot-prefix)]
    {:id             (uuid/next)
     :name           (-> attrs :name)
     :starting-frame (-> attrs :starting-frame uuid)}))

(defn parse-flows [node]
  (let [flows-node (get-data node :penpot:flows)]
    (->> flows-node :content (mapv parse-flow-node))))

(defn parse-guide-node [node]
  (let [attrs (-> node :attrs remove-penpot-prefix)]
    (println attrs)
    (let [id (uuid/next)]
      [id
       {:id       id
        :frame-id (when (:frame-id attrs) (-> attrs :frame-id uuid))
        :axis     (-> attrs :axis keyword)
        :position (-> attrs :position d/parse-double)}])))

(defn parse-guides [node]
  (let [guides-node (get-data node :penpot:guides)]
    (->> guides-node :content (map parse-guide-node) (into {}))))

(defn extract-from-data
  ([node tag]
   (extract-from-data node tag identity))

  ([node tag parse-fn]
   (let [shape-data (get-data node)]
     (->> shape-data
          (node-seq)
          (filter #(= (:tag %) tag))
          (mapv parse-fn)))))

(defn add-shadows
  [props node]
  (let [shadows (extract-from-data node :penpot:shadow parse-shadow)]
    (cond-> props
      (d/not-empty? shadows)
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
      (d/not-empty? exports)
      (assoc :exports exports))))

(defn add-layer-options
  [props svg-data]
  (let [blend-mode (get svg-data :mix-blend-mode)
        opacity (-> (get svg-data :opacity) d/parse-double)]

    (cond-> props
      (some? blend-mode)
      (assoc :blend-mode (keyword blend-mode))

      (some? opacity)
      (assoc :opacity opacity))))

(defn remove-prefix [s]
  (cond-> s
    (string? s)
    (str/replace uuid-regex-prefix "")))

(defn get-svg-attrs
  [svg-import svg-data svg-attrs]
  (let [process-attr
        (fn [acc prop]
          (cond
            (and (= prop "style")
                 (contains? (:attrs svg-import) :penpot:svg-style))
            (let [style (get-in svg-import [:attrs :penpot:svg-style])]
              (assoc acc :style (parse-style style)))

            (and (= prop "filter")
                 (contains? (:attrs svg-import) :penpot:svg-filter))
            (let [style (get-in svg-import [:attrs :penpot:svg-filter])]
              (assoc acc :filter (parse-style style)))

            :else
            (let [key (keyword prop)]
              (if-let [v (or (get svg-data key)
                             (get-in svg-data [:attrs key]))]
                (assoc acc key (remove-prefix v))
                acc))))]
    (->> (str/split svg-attrs ",")
         (reduce process-attr {}))))

(defn get-svg-defs
  [node]

  (let [svg-import (get-data node :penpot:svg-import)]
    (->> svg-import
         :content
         (filter #(= (:tag %) :penpot:svg-def))
         (map #(vector (-> % :attrs :def-id)
                       (-> % :content first)))
         (into {}))))

(defn add-svg-attrs
  [props node svg-data]

  (let [svg-import (get-data node :penpot:svg-import)]
    (if (some? svg-import)
      (let [svg-attrs (get-in svg-import [:attrs :penpot:svg-attrs])
            svg-defs (get-in svg-import [:attrs :penpot:svg-defs])
            svg-transform (get-in svg-import [:attrs :penpot:svg-transform])
            viewbox-x (get-in svg-import [:attrs :penpot:svg-viewbox-x])
            viewbox-y (get-in svg-import [:attrs :penpot:svg-viewbox-y])
            viewbox-width (get-in svg-import [:attrs :penpot:svg-viewbox-width])
            viewbox-height (get-in svg-import [:attrs :penpot:svg-viewbox-height])]

        (cond-> props
          :true
          (assoc :svg-attrs (get-svg-attrs svg-import svg-data svg-attrs))

          (some? viewbox-x)
          (assoc :svg-viewbox {:x      (d/parse-double viewbox-x)
                               :y      (d/parse-double viewbox-y)
                               :width  (d/parse-double viewbox-width)
                               :height (d/parse-double viewbox-height)})

          (some? svg-transform)
          (assoc :svg-transform (gmt/str->matrix svg-transform))


          (some? svg-defs)
          (assoc :svg-defs (get-svg-defs node))))

      props)))

(defn add-fills
  [props node svg-data]
  (let [fills (-> node
                  (find-node :defs)
                  (find-node :pattern)
                  (find-node :g)
                  (find-all-nodes :rect)
                  (reverse))
        fills (if (= 0 (count fills))
                [(add-fill {} node svg-data)]
                (map #(add-fill {} node (get-svg-data :rect %)) fills))]
      (-> props
          (assoc :fills fills))))


(defn add-svg-content
  [props node]
  (let [svg-content (get-data node :penpot:svg-content)
        attrs (-> (:attrs svg-content) (without-penpot-prefix)
                  (d/update-when :style parse-style))
        tag (-> svg-content :attrs :penpot:tag keyword)

        node-content
        (cond
          (= tag :svg)
          (->> node :content last :content last :content fix-style-attr)

          (some? (:content svg-content))
          (->> (:content svg-content)
               (filter #(= :penpot:svg-child (:tag %)))
               (mapv :content)
               (first)))]
    (-> props
        (assoc
         :content
         {:attrs   attrs
          :tag     tag
          :content node-content}))))

(defn add-frame-data [props node]
  (let [grids (parse-grids node)]
    (cond-> props
      (d/not-empty? grids)
      (assoc :grids grids))))

(defn has-image?
  [node]
  (let [type (get-type node)
        pattern-image
        (-> node
            (find-node :defs)
            (find-node :pattern)
            (find-node :image))]
    (or (= type :image)
        (some? pattern-image))))

(defn get-image-name
  [node]
  (get-meta node :name))

(defn get-image-data
  [node]
  (let [pattern-data
        (-> node
            (find-node :defs)
            (find-node :pattern)
            (find-node :image)
            :attrs)
        image-data (get-svg-data :image node)
        svg-data (or image-data pattern-data)]
    (:xlink:href svg-data)))

(defn parse-data
  [type node]

  (when-not (close? node)
    (let [svg-data (get-svg-data type node)]
      (-> {}
          (add-common-data node)
          (add-position type node svg-data)
          (add-stroke node svg-data)
          (add-layer-options svg-data)
          (add-shadows node)
          (add-blur node)
          (add-exports node)
          (add-svg-attrs node svg-data)
          (add-library-refs node)
          (add-fills node svg-data)

          (cond-> (= :svg-raw type)
            (add-svg-content node))

          (cond-> (= :frame type)
            (add-frame-data node))

          (cond-> (= :group type)
            (add-group-data node))

          (cond-> (= :rect type)
            (add-rect-data node svg-data))

          (cond-> (some? (get-in node [:attrs :penpot:media-id]))
            (->
             (add-rect-data node svg-data)
             (add-image-data type node)))

          (cond-> (= :text type)
            (add-text-data node))

          (cond-> (= :bool type)
            (add-bool-data node))))))

(defn parse-page-data
  [node]
  (let [style      (parse-style (get-in node [:attrs :style]))
        background (:background style)
        grids      (->> (parse-grids node)
                        (group-by :type)
                        (d/mapm (fn [_ v] (-> v first :params))))
        flows      (parse-flows node)
        guides     (parse-guides node)]
    (cond-> {}
      (some? background)
      (assoc-in [:options :background] background)

      (d/not-empty? grids)
      (assoc-in [:options :saved-grids] grids)

      (d/not-empty? flows)
      (assoc-in [:options :flows] flows)

      (d/not-empty? guides)
      (assoc-in [:options :guides] guides))))

(defn parse-interactions
  [node]
  (let [interactions-node (get-data node :penpot:interactions)]
    (->> (find-all-nodes interactions-node :penpot:interaction)
         (mapv (fn [node]
                 (let [interaction {:event-type  (get-meta node :event-type keyword)
                                    :action-type (get-meta node :action-type keyword)}]
                   (cond-> interaction
                     (cti/has-delay interaction)
                     (assoc :delay (get-meta node :delay d/parse-double))

                     (cti/has-destination interaction)
                     (assoc :destination     (get-meta node :destination uuid/uuid)
                            :preserve-scroll (get-meta node :preserve-scroll str->bool))

                     (cti/has-url interaction)
                     (assoc :url (get-meta node :url str))

                     (cti/has-overlay-opts interaction)
                     (assoc :overlay-pos-type    (get-meta node :overlay-pos-type keyword)
                            :overlay-position    (gpt/point
                                                   (get-meta node :overlay-position-x d/parse-double)
                                                   (get-meta node :overlay-position-y d/parse-double))
                            :close-click-outside (get-meta node :close-click-outside str->bool)
                            :background-overlay  (get-meta node :background-overlay str->bool)))))))))

