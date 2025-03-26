;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg.shapes-builder
  "A SVG to Shapes builder."
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.common :as gsc]
   [app.common.geom.shapes.transforms :as gst]
   [app.common.math :as mth]
   [app.common.schema :as sm :refer [max-safe-int min-safe-int]]
   [app.common.svg :as csvg]
   [app.common.svg.path :as path]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(def default-rect
  {:x 0 :y 0 :width 1 :height 1})

(defn- assert-valid-num [attr num]
  (when-not (and (d/num? num)
                 (<= num max-safe-int)
                 (>= num min-safe-int))
    (ex/raise :type :assertion
              :code :data-validation
              :hint (str "invalid numeric value for `" attr "`: " num)))
  (cond
    (and (> num 0) (< num 1))    1
    (and (< num 0) (> num -1))  -1
    :else                       num))

(defn- assert-valid-pos-num
  [attr num]

  (when-not (pos? num)
    (ex/raise :type :assertion
              :code :data-validation
              :hint (str "invalid numeric value for `" attr "`: " num " (should be positive)")))
  num)

(defn- assert-valid-blend-mode
  [mode]
  (let [value (-> mode str/trim str/lower keyword)]

    (when-not (contains? cts/blend-modes value)
      (ex/raise :type :assertion
                :code :data-validation
                :hint (str "unexpected blend mode: " value)))
    value))

(defn- svg-dimensions
  [{:keys [attrs] :as data}]
  (let [width   (:width attrs 100)
        height  (:height attrs 100)
        viewbox (or (:viewBox attrs)
                    (dm/str "0 0 " width " " height))

        [x y width height] (csvg/parse-numbers viewbox)

        width   (if (= width 0) 1 width)
        height  (if (= height 0) 1 height)]

    [(assert-valid-num :x x)
     (assert-valid-num :y y)
     (assert-valid-pos-num :width width)
     (assert-valid-pos-num :height height)]))

(declare create-svg-root)
(declare create-svg-children)
(declare parse-svg-element)

(defn create-svg-shapes
  ([svg-data pos objects frame-id parent-id selected center?]
   (create-svg-shapes (uuid/next) svg-data pos objects frame-id parent-id selected center?))
  ([id svg-data {:keys [x y]} objects frame-id parent-id selected center?]
   (let [[vb-x vb-y vb-width vb-height] (svg-dimensions svg-data)

         unames   (cfh/get-used-names objects)
         svg-name (str/replace (:name svg-data) ".svg" "")

         svg-data (-> svg-data
                      (assoc :x (mth/round
                                 (if center?
                                   (- x vb-x (/ vb-width 2))
                                   x)))
                      (assoc :y (mth/round
                                 (if center?
                                   (- y vb-y (/ vb-height 2))
                                   y)))
                      (assoc :offset-x vb-x)
                      (assoc :offset-y vb-y)
                      (assoc :width vb-width)
                      (assoc :height vb-height)
                      (assoc :name svg-name))

         [def-nodes svg-data]
         (-> svg-data
             (csvg/fix-default-values)
             (csvg/fix-percents)
             (csvg/extract-defs))

         ;; In penpot groups have the size of their children. To
         ;; respect the imported svg size and empty space let's create
         ;; a transparent shape as background to respect the imported
         ;; size
         background
         {:tag :rect
          :attrs {:x      (dm/str vb-x)
                  :y      (dm/str vb-y)
                  :width  (dm/str vb-width)
                  :height (dm/str vb-height)
                  :fill   "none"
                  :id     "base-background"}
          :hidden true
          :content []}

         svg-data   (-> svg-data
                        (assoc :defs def-nodes)
                        (assoc :content (into [background] (:content svg-data))))

         root-shape (create-svg-root id frame-id parent-id svg-data)
         root-id    (:id root-shape)

         ;; Create the root shape
         root-attrs (-> (:attrs svg-data)
                        (csvg/format-styles))

         [_ children]
         (reduce (partial create-svg-children objects selected frame-id root-id svg-data)
                 [unames []]
                 (d/enumerate (->> (:content svg-data)
                                   (mapv #(csvg/inherit-attributes root-attrs %)))))]

     [root-shape children])))

(defn create-raw-svg
  [name frame-id {:keys [x y width height offset-x offset-y]} {:keys [attrs] :as data}]
  (let [props (csvg/attrs->props attrs)
        vbox  (grc/make-rect offset-x offset-y width height)]
    (cts/setup-shape
     {:type :svg-raw
      :name name
      :frame-id frame-id
      :width width
      :height height
      :x x
      :y y
      :content data
      :svg-attrs props
      :svg-viewbox vbox})))

(defn create-svg-root
  [id frame-id parent-id {:keys [name x y width height offset-x offset-y attrs]}]
  (let [props (-> (dissoc attrs :viewBox :view-box :xmlns)
                  (d/without-keys csvg/inheritable-props)
                  (csvg/attrs->props))]
    (cts/setup-shape
     {:id id
      :type :group
      :name name
      :frame-id frame-id
      :parent-id parent-id
      :width width
      :height height
      :x (+ x offset-x)
      :y (+ y offset-y)
      :svg-attrs props})))

(defn create-svg-children
  [objects selected frame-id parent-id svg-data [unames children] [_index svg-element]]
  (let [[shape new-children] (parse-svg-element frame-id svg-data svg-element unames)]
    (if (some? shape)
      (let [shape-id (:id shape)
            shape    (-> shape
                         (assoc :frame-id frame-id)
                         (assoc :parent-id parent-id))
            children (conj children shape)
            unames   (conj unames (:name shape))]

        (reduce (partial create-svg-children objects selected frame-id shape-id svg-data)
                [unames children]
                (d/enumerate new-children)))

      [unames children])))


(defn create-group
  [name frame-id {:keys [x y width height offset-x offset-y] :as svg-data} {:keys [attrs]}]
  (let [transform (csvg/parse-transform (:transform attrs))
        attrs     (-> attrs
                      (d/without-keys csvg/inheritable-props)
                      (csvg/attrs->props))
        vbox      (grc/make-rect offset-x offset-y width height)]
    (cts/setup-shape
     {:type :group
      :name name
      :frame-id frame-id
      :x (+ x offset-x)
      :y (+ y offset-y)
      :width width
      :height height
      :svg-transform transform
      :svg-attrs attrs
      :svg-viewbox vbox})))

(defn create-path-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (when (and (contains? attrs :d) (seq (:d attrs)))
    (let [transform (csvg/parse-transform (:transform attrs))
          content   (cond-> (path/parse (:d attrs))
                      (some? transform)
                      (gsh/transform-content transform))

          selrect    (gsh/content->selrect content)
          points     (grc/rect->points selrect)
          origin     (gpt/negate (gpt/point svg-data))
          attrs      (-> (dissoc attrs :d :transform)
                         (csvg/attrs->props))]
      (-> (cts/setup-shape
           {:type :path
            :name name
            :frame-id frame-id
            :content content
            :selrect selrect
            :points points
            :svg-viewbox selrect
            :svg-attrs attrs
            :svg-transform transform
            :strokes []
            :fills []})
          (gsh/translate-to-frame origin)))))

(defn calculate-rect-metadata
  [rect transform]
  (let [points    (-> rect
                      (grc/rect->points)
                      (gsh/transform-points transform))
        center    (gsc/points->center points)
        selrect   (gst/calculate-selrect points center)
        transform (gst/calculate-transform points center selrect)]

    {:x (:x selrect)
     :y (:y selrect)
     :width (:width selrect)
     :height (:height selrect)
     :selrect selrect
     :points points
     :transform transform
     :transform-inverse (when (some? transform)
                          (gmt/inverse transform))}))

(defn- parse-rect-attrs
  [{:keys [x y width height]}]
  (grc/make-rect
   (d/parse-double x 0)
   (d/parse-double y 0)
   (d/parse-double width 1)
   (d/parse-double height 1)))

(defn create-rect-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [transform (->> (csvg/parse-transform (:transform attrs))
                       (gmt/transform-in (gpt/point svg-data)))

        origin    (gpt/negate (gpt/point svg-data))
        vbox      (parse-rect-attrs attrs)
        rect      (-> vbox
                      (update :x - (:x origin))
                      (update :y - (:y origin)))

        props     (-> (dissoc attrs :x :y :width :height :rx :ry :transform)
                      (csvg/attrs->props))]
    (cts/setup-shape
     (-> (calculate-rect-metadata rect transform)
         (assoc :type :rect)
         (assoc :name name)
         (assoc :frame-id frame-id)
         (assoc :svg-viewbox vbox)
         (assoc :svg-attrs props)
         ;; We need to ensure fills are empty on import process
         ;; because setup-shape assings one by default.
         (assoc :fills [])
         (cond-> (contains? attrs :rx)
           (assoc :rx (d/parse-double (:rx attrs) 0)))
         (cond-> (contains? attrs :ry)
           (assoc :ry (d/parse-double (:ry attrs) 0)))))))

(defn- parse-circle-attrs
  [attrs]
  (into [] (comp (map (d/getf attrs))
                 (map d/parse-double))
        [:cx :cy :r :rx :ry]))

(defn create-circle-shape
  [name frame-id svg-data {:keys [attrs] :as data}]
  (let [[cx cy r rx ry]
        (parse-circle-attrs attrs)

        transform (->> (csvg/parse-transform (:transform attrs))
                       (gmt/transform-in (gpt/point svg-data)))

        rx        (d/nilv r rx)
        ry        (d/nilv r ry)
        rx        (d/nilv rx 0)
        ry        (d/nilv ry 0)

        ;; There are some svg circles in the internet that does not
        ;; have cx and cy attrs, so we default them to 0
        cx        (d/nilv cx 0)
        cy        (d/nilv cy 0)
        origin    (gpt/negate (gpt/point svg-data))

        rect      (grc/make-rect
                   (- cx rx (:x origin))
                   (- cy ry (:y origin))
                   (* 2 rx)
                   (* 2 ry))
        props     (-> (dissoc attrs :cx :cy :r :rx :ry :transform)
                      (csvg/attrs->props))]

    (cts/setup-shape
     (-> (calculate-rect-metadata rect transform)
         (assoc :type :circle)
         (assoc :name name)
         (assoc :frame-id frame-id)
         (assoc :svg-viewbox rect)
         (assoc :svg-attrs props)
         (assoc :fills [])))))

(defn create-image-shape
  [name frame-id svg-data {:keys [attrs] :as data}]
  (let [transform  (->> (csvg/parse-transform (:transform attrs))
                        (gmt/transform-in (gpt/point svg-data)))

        image-url  (or (:href attrs) (:xlink:href attrs))
        image-data (dm/get-in svg-data [:image-data image-url])

        metadata   {:name name
                    :width (:width image-data)
                    :height (:height image-data)
                    :mtype (:mtype image-data)
                    :id (:id image-data)}

        origin     (gpt/negate (gpt/point svg-data))
        rect       (-> (parse-rect-attrs attrs)
                       (update :x - (:x origin))
                       (update :y - (:y origin)))
        props      (-> (dissoc attrs :x :y :width :height :href :xlink:href)
                       (csvg/attrs->props))]

    (when (some? image-data)
      (cts/setup-shape
       (-> (calculate-rect-metadata rect transform)
           (assoc :type :rect)
           (assoc :name name)
           (assoc :frame-id frame-id)
           (assoc :fills [{:fill-opacity 1
                           :fill-image metadata}])
           (assoc :metadata metadata)
           (assoc :svg-viewbox rect)
           (assoc :svg-attrs props))))))

(defn setup-fill
  [shape]
  (let [color-attr  (str/trim (dm/get-in shape [:svg-attrs :fill]))
        color-attr  (if (= color-attr "currentColor") clr/black color-attr)
        color-style (str/trim (dm/get-in shape [:svg-attrs :style :fill]))
        color-style (if (= color-style "currentColor") clr/black color-style)]
    (cond-> shape
      ;; Color present as attribute
      (clr/color-string? color-attr)
      (-> (update :svg-attrs dissoc :fill)
          (update-in [:svg-attrs :style] dissoc :fill)
          (assoc-in [:fills 0 :fill-color] (clr/parse color-attr)))

      ;; Color present as style
      (clr/color-string? color-style)
      (-> (update-in [:svg-attrs :style] dissoc :fill)
          (update :svg-attrs dissoc :fill)
          (assoc-in [:fills 0 :fill-color] (clr/parse color-style)))

      ;; Only create an opacity if the color is setted. Othewise can create problems down the line
      (and (or (clr/color-string? color-attr) (clr/color-string? color-style))
           (dm/get-in shape [:svg-attrs :fillOpacity]))
      (-> (update :svg-attrs dissoc :fillOpacity)
          (update-in [:svg-attrs :style] dissoc :fillOpacity)
          (assoc-in [:fills 0 :fill-opacity] (-> (dm/get-in shape [:svg-attrs :fillOpacity])
                                                 (d/parse-double 1))))

      (and (or (clr/color-string? color-attr) (clr/color-string? color-style))
           (dm/get-in shape [:svg-attrs :style :fillOpacity]))
      (-> (update-in [:svg-attrs :style] dissoc :fillOpacity)
          (update :svg-attrs dissoc :fillOpacity)
          (assoc-in [:fills 0 :fill-opacity] (-> (dm/get-in shape [:svg-attrs :style :fillOpacity])
                                                 (d/parse-double 1)))))))

(defn- setup-stroke
  [shape]
  (let [attrs   (get shape :svg-attrs)
        style   (get attrs :style)

        stroke  (or (str/trim (:stroke attrs))
                    (str/trim (:stroke style)))

        color   (cond
                  (= stroke "currentColor")  clr/black
                  (= stroke "none")          nil
                  (clr/color-string? stroke) (clr/parse stroke))

        opacity (when (some? color)
                  (d/parse-double
                   (or (:strokeOpacity attrs)
                       (:strokeOpacity style))
                   1))

        width   (when (some? color)
                  (d/parse-double
                   (or (:strokeWidth attrs)
                       (:strokeWidth style))
                   1))

        linecap (or (get attrs :strokeLinecap)
                    (get style :strokeLinecap))
        linecap (some-> linecap str/trim keyword)

        attrs
        (-> attrs
            (cond-> (some? color)
              (dissoc :stroke :strokeWidth :strokeOpacity))
            (update
             :style
             (fn [style]
               (-> style
                   (cond-> (some? color)
                     (dissoc :stroke :strokeWidth :strokeOpacity)))))
            (d/without-nils))]

    (cond-> (assoc shape :svg-attrs attrs)
      (some? color)
      (assoc-in [:strokes 0 :stroke-color] color)

      (and (some? color) (some? opacity))
      (assoc-in [:strokes 0 :stroke-opacity] opacity)

      (and (some? color) (some? width))
      (assoc-in [:strokes 0 :stroke-width] width)

      (and (some? linecap) (cfh/path-shape? shape)
           (or (= linecap :round) (= linecap :square)))

      (assoc :stroke-cap-start linecap
             :stroke-cap-end linecap
             :stroke-linecap linecap)

      (d/any-key? (dm/get-in shape [:strokes 0])
                  :strokeColor :strokeOpacity :strokeWidth
                  :strokeLinecap :strokeCapStart :strokeCapEnd)
      (assoc-in [:strokes 0 :stroke-style] :svg))))

(defn setup-opacity [shape]
  (cond-> shape
    (dm/get-in shape [:svg-attrs :opacity])
    (-> (update :svg-attrs dissoc :opacity)
        (assoc :opacity (-> (dm/get-in shape [:svg-attrs :opacity])
                            (d/parse-double 1))))

    (dm/get-in shape [:svg-attrs :style :opacity])
    (-> (update-in [:svg-attrs :style] dissoc :opacity)
        (assoc :opacity (-> (dm/get-in shape [:svg-attrs :style :opacity])
                            (d/parse-double 1))))

    (dm/get-in shape [:svg-attrs :mixBlendMode])
    (-> (update :svg-attrs dissoc :mixBlendMode)
        (assoc :blend-mode (-> (dm/get-in shape [:svg-attrs :mixBlendMode]) assert-valid-blend-mode)))

    (dm/get-in shape [:svg-attrs :style :mixBlendMode])
    (-> (update-in [:svg-attrs :style] dissoc :mixBlendMode)
        (assoc :blend-mode (-> (dm/get-in shape [:svg-attrs :style :mixBlendMode]) assert-valid-blend-mode)))))

(defn setup-other [shape]
  (cond-> shape
    (= (dm/get-in shape [:svg-attrs :display]) "none")
    (-> (update-in [:svg-attrs :style] dissoc :display)
        (assoc :hidden true))

    (= (dm/get-in shape [:svg-attrs :style :display]) "none")
    (-> (update :svg-attrs dissoc :display)
        (assoc :hidden true))))

(defn tag->name
  "Given a tag returns its layer name"
  [tag]
  (let [suffix (cond
                 (string? tag) tag
                 (keyword? tag) (d/name tag)
                 (nil? tag) "node"
                 :else (dm/str tag))]
    (dm/str "svg-" suffix)))

(defn parse-svg-element
  [frame-id svg-data {:keys [tag attrs hidden] :as element} unames]

  ;; FIXME: there are cases where element is directly a string, I
  ;; think we should handle this case early and avoid some code
  ;; execution

  (let [name         (or (:id attrs) (tag->name tag))
        att-refs     (csvg/find-attr-references attrs)
        defs         (get svg-data :defs)
        references   (csvg/find-def-references defs att-refs)

        href-id      (or (:href attrs) (:xlink:href attrs) " ")
        href-id      (if (and (string? href-id)
                              (pos? (count href-id)))
                       (subs href-id 1)
                       href-id)

        use-tag?     (and (= :use tag)
                          (some? href-id)
                          (contains? defs href-id))]

    (if use-tag?
      (let [;; Merge the data of the use definition with the properties passed as attributes
            use-data     (-> (get defs href-id)
                             (update :attrs #(d/deep-merge % (dissoc attrs :xlink:href :href))))
            displacement (gpt/point (d/parse-double (:x attrs "0")) (d/parse-double (:y attrs "0")))
            disp-matrix  (dm/str (gmt/translate-matrix displacement))
            element      (-> element
                             (assoc :tag :g)
                             (update :attrs dissoc :x :y :width :height :href :xlink:href :transform)
                             (update :attrs csvg/add-transform disp-matrix)
                             (assoc :content [use-data]))]
        (parse-svg-element frame-id svg-data element unames))

      (let [;; SVG graphic elements
            ;; :circle :ellipse :image :line :path :polygon :polyline :rect :text :use
            shape   (case tag
                      (:g :a :svg) (create-group name frame-id svg-data element)
                      :rect        (create-rect-shape name frame-id svg-data element)
                      (:circle
                       :ellipse)   (create-circle-shape name frame-id svg-data element)
                      :path        (create-path-shape name frame-id svg-data element)
                      :polyline    (create-path-shape name frame-id svg-data (-> element csvg/polyline->path))
                      :polygon     (create-path-shape name frame-id svg-data (-> element csvg/polygon->path))
                      :line        (create-path-shape name frame-id svg-data (-> element csvg/line->path))
                      :image       (create-image-shape name frame-id svg-data element)
                      #_other      (create-raw-svg name frame-id svg-data element))]

        (when (some? shape)
          [(-> shape
               (assoc :svg-defs (select-keys defs references))
               (setup-fill)
               (setup-stroke)
               (setup-opacity)
               (setup-other)
               (update :svg-attrs (fn [attrs]
                                    (if (empty? (:style attrs))
                                      (dissoc attrs :style)
                                      attrs)))
               (cond-> ^boolean hidden
                 (assoc :hidden true)))

           (cond->> (:content element)
             (contains? csvg/parent-tags tag)
             (mapv (partial csvg/inherit-attributes attrs)))])))))
