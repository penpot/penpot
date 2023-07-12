;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.svg-upload
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.schema :as sm :refer [max-safe-int min-safe-int]]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [app.util.color :as uc]
   [app.util.path.parser :as upp]
   [app.util.svg :as usvg]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(def default-rect
  {:x 0 :y 0 :width 1 :height 1})

(defn- assert-valid-num [attr num]
  (dm/verify!
   ["%1 attribute has invalid value: %2" (d/name attr) num]
   (and (d/num? num)
        (<= num max-safe-int)
        (>= num min-safe-int)))

  (cond
    (and (> num 0) (< num 1))    1
    (and (< num 0) (> num -1))  -1
    :else                       num))

(defn- assert-valid-pos-num
  [attr num]

  (dm/verify!
   ["%1 attribute should be positive" (d/name attr)]
   (pos? num))

  num)

(defn- assert-valid-blend-mode
  [mode]
  (let [clean-value (-> mode str/trim str/lower keyword)]
    (dm/verify!
     ["%1 is not a valid blend mode" clean-value]
     (contains? cts/blend-modes clean-value))
    clean-value))

(defn- svg-dimensions [data]
  (let [width (dm/get-in data [:attrs :width] 100)
        height (dm/get-in data [:attrs :height] 100)
        viewbox (dm/get-in data [:attrs :viewBox] (str "0 0 " width " " height))
        [x y width height] (->> (str/split viewbox #"\s+")
                                (map d/parse-double))
        width (if (= width 0) 1 width)
        height (if (= height 0) 1 height)]
    [(assert-valid-num :x x)
     (assert-valid-num :y y)
     (assert-valid-pos-num :width width)
     (assert-valid-pos-num :height height)]))

(defn tag->name
  "Given a tag returns its layer name"
  [tag]
  (str "svg-" (cond (string? tag) tag
                    (keyword? tag) (d/name tag)
                    (nil? tag) "node"
                    :else (str tag))))

(defn setup-fill [shape]
  (let [color-attr (str/trim (dm/get-in shape [:svg-attrs :fill]))
        color-attr (if (= color-attr "currentColor") clr/black color-attr)
        color-style (str/trim (dm/get-in shape [:svg-attrs :style :fill]))
        color-style (if (= color-style "currentColor") clr/black color-style)]
    (cond-> shape
      ;; Color present as attribute
      (uc/color? color-attr)
      (-> (update :svg-attrs dissoc :fill)
          (update-in [:svg-attrs :style] dissoc :fill)
          (assoc-in [:fills 0 :fill-color] (uc/parse-color color-attr)))

      ;; Color present as style
      (uc/color? color-style)
      (-> (update-in [:svg-attrs :style] dissoc :fill)
          (update :svg-attrs dissoc :fill)
          (assoc-in [:fills 0 :fill-color] (uc/parse-color color-style)))

      (dm/get-in shape [:svg-attrs :fill-opacity])
      (-> (update :svg-attrs dissoc :fill-opacity)
          (update-in [:svg-attrs :style] dissoc :fill-opacity)
          (assoc-in [:fills 0 :fill-opacity] (-> (dm/get-in shape [:svg-attrs :fill-opacity])
                                                 (d/parse-double 1))))

      (dm/get-in shape [:svg-attrs :style :fill-opacity])
      (-> (update-in [:svg-attrs :style] dissoc :fill-opacity)
          (update :svg-attrs dissoc :fill-opacity)
          (assoc-in [:fills 0 :fill-opacity] (-> (dm/get-in shape [:svg-attrs :style :fill-opacity])
                                                 (d/parse-double 1)))))))

(defn setup-stroke [shape]
  (let [stroke-linecap (-> (or (dm/get-in shape [:svg-attrs :stroke-linecap])
                               (dm/get-in shape [:svg-attrs :style :stroke-linecap]))
                           ((d/nilf str/trim))
                           ((d/nilf keyword)))
        color-attr (str/trim (dm/get-in shape [:svg-attrs :stroke]))
        color-attr (if (= color-attr "currentColor") clr/black color-attr)
        color-style (str/trim (dm/get-in shape [:svg-attrs :style :stroke]))
        color-style (if (= color-style "currentColor") clr/black color-style)

        shape
        (cond-> shape
          ;; Color present as attribute
          (uc/color? color-attr)
          (-> (update :svg-attrs dissoc :stroke)
              (assoc-in [:strokes 0 :stroke-color] (uc/parse-color color-attr)))

          ;; Color present as style
          (uc/color? color-style)
          (-> (update-in [:svg-attrs :style] dissoc :stroke)
              (assoc-in [:strokes 0 :stroke-color] (uc/parse-color color-style)))

          (dm/get-in shape [:svg-attrs :stroke-opacity])
          (-> (update :svg-attrs dissoc :stroke-opacity)
              (assoc-in [:strokes 0 :stroke-opacity] (-> (dm/get-in shape [:svg-attrs :stroke-opacity])
                                                         (d/parse-double 1))))

          (dm/get-in shape [:svg-attrs :style :stroke-opacity])
          (-> (update-in [:svg-attrs :style] dissoc :stroke-opacity)
              (assoc-in [:strokes 0 :stroke-opacity] (-> (dm/get-in shape [:svg-attrs :style :stroke-opacity])
                                                         (d/parse-double 1))))

          (dm/get-in shape [:svg-attrs :stroke-width])
          (-> (update :svg-attrs dissoc :stroke-width)
              (assoc-in [:strokes 0 :stroke-width] (-> (dm/get-in shape [:svg-attrs :stroke-width])
                                                       (d/parse-double))))

          (dm/get-in shape [:svg-attrs :style :stroke-width])
          (-> (update-in [:svg-attrs :style] dissoc :stroke-width)
              (assoc-in [:strokes 0 :stroke-width] (-> (dm/get-in shape [:svg-attrs :style :stroke-width])
                                                       (d/parse-double))))

          (and stroke-linecap (= (:type shape) :path))
          (-> (update-in [:svg-attrs :style] dissoc :stroke-linecap)
              (cond-> (#{:round :square} stroke-linecap)
                (assoc :stroke-cap-start stroke-linecap
                       :stroke-cap-end   stroke-linecap))))]

    (cond-> shape
      (d/any-key? (dm/get-in shape [:strokes 0]) :stroke-color :stroke-opacity :stroke-width :stroke-cap-start :stroke-cap-end)
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

    (dm/get-in shape [:svg-attrs :mix-blend-mode])
    (-> (update :svg-attrs dissoc :mix-blend-mode)
        (assoc :blend-mode (-> (dm/get-in shape [:svg-attrs :mix-blend-mode]) assert-valid-blend-mode)))

    (dm/get-in shape [:svg-attrs :style :mix-blend-mode])
    (-> (update-in [:svg-attrs :style] dissoc :mix-blend-mode)
        (assoc :blend-mode (-> (dm/get-in shape [:svg-attrs :style :mix-blend-mode]) assert-valid-blend-mode)))))

(defn create-raw-svg
  [name frame-id {:keys [x y width height offset-x offset-y]} {:keys [attrs] :as data}]
  (cts/setup-shape
   {:type :svg-raw
    :name name
    :frame-id frame-id
    :width width
    :height height
    :x x
    :y y
    :content (cond-> data
               (map? data) (update :attrs usvg/clean-attrs))
    :svg-attrs attrs
    :svg-viewbox {:width width
                  :height height
                  :x offset-x
                  :y offset-y}}))

(defn create-svg-root
  [frame-id parent-id {:keys [name x y width height offset-x offset-y attrs]}]
  (cts/setup-shape
   {:type :group
    :name name
    :frame-id frame-id
    :parent-id parent-id
    :width width
    :height height
    :x (+ x offset-x)
    :y (+ y offset-y)
    :svg-attrs (-> attrs
                   (dissoc :viewBox)
                   (dissoc :xmlns)
                   (d/without-keys usvg/inheritable-props))}))

(defn create-group
  [name frame-id {:keys [x y width height offset-x offset-y] :as svg-data} {:keys [attrs]}]
  (let [svg-transform (usvg/parse-transform (:transform attrs))]
    (cts/setup-shape
     {:type :group
      :name name
      :frame-id frame-id
      :x (+ x offset-x)
      :y (+ y offset-y)
      :width width
      :height height
      :svg-transform svg-transform
      :svg-attrs (d/without-keys attrs usvg/inheritable-props)

      :svg-viewbox {:width width
                    :height height
                    :x offset-x
                    :y offset-y}})))

(defn create-path-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (when (and (contains? attrs :d) (seq (:d attrs)))

    (let [svg-transform (usvg/parse-transform (:transform attrs))
          path-content  (upp/parse-path (:d attrs))
          content       (cond-> path-content
                          svg-transform
                          (gsh/transform-content svg-transform))

          selrect       (gsh/content->selrect content)
          points        (grc/rect->points selrect)

          origin        (gpt/negate (gpt/point svg-data))]

      (-> (cts/setup-shape
           {:type :path
            :name name
            :frame-id frame-id
            :content content
            :selrect selrect
            :points points
            :svg-viewbox (select-keys selrect [:x :y :width :height])
            :svg-attrs (dissoc attrs :d :transform)
            :svg-transform svg-transform})
          (gsh/translate-to-frame origin)))))

(defn calculate-rect-metadata [rect-data transform]
  (let [points (-> (grc/make-rect rect-data)
                   (grc/rect->points)
                   (gsh/transform-points transform))

        [selrect transform transform-inverse] (gsh/calculate-geometry points)]

    {:x (:x selrect)
     :y (:y selrect)
     :width (:width selrect)
     :height (:height selrect)
     :selrect selrect
     :points points
     :transform transform
     :transform-inverse transform-inverse}))

(defn- parse-rect-attrs
  [{:keys [x y width height]}]
  {:x (d/parse-double x 0)
   :y (d/parse-double y 0)
   :width (d/parse-double width 1)
   :height (d/parse-double height 1)})

(defn create-rect-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [transform (->> (usvg/parse-transform (:transform attrs))
                       (gmt/transform-in (gpt/point svg-data)))

        origin    (gpt/negate (gpt/point svg-data))

        rect-data (-> (parse-rect-attrs attrs)
                      (update :x - (:x origin))
                      (update :y - (:y origin)))]

    (cts/setup-shape
     (-> (calculate-rect-metadata rect-data transform)
         (assoc :type :rect)
         (assoc :name name)
         (assoc :frame-id frame-id)
         (assoc :svg-viewbox (select-keys rect-data [:x :y :width :height]))
         (assoc :svg-attrs (dissoc attrs :x :y :width :height :rx :ry :transform))
         (cond-> (contains? attrs :rx)
           (assoc :rx (d/parse-double (:rx attrs) 0)))
         (cond-> (contains? attrs :ry)
           (assoc :ry (d/parse-double (:ry attrs) 0)))))))


(defn- parse-circle-attrs
  [attrs]
  (into [] (comp (map (d/getf attrs))
                 (map d/parse-double))
        [:cx :cy :r :rx :ry]))

(defn create-circle-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [[cx cy r rx ry]
        (parse-circle-attrs attrs)

        transform (->> (usvg/parse-transform (:transform attrs))
                       (gmt/transform-in (gpt/point svg-data)))

        rx        (or r rx)
        ry        (or r ry)
        origin    (gpt/negate (gpt/point svg-data))

        rect-data {:x (- cx rx (:x origin))
                   :y (- cy ry (:y origin))
                   :width (* 2 rx)
                   :height (* 2 ry)}]

    (cts/setup-shape
     (-> (calculate-rect-metadata rect-data transform)
         (assoc :type :circle)
         (assoc :name name)
         (assoc :frame-id frame-id)
         (assoc :svg-viewbox rect-data)
         (assoc :svg-attrs (dissoc attrs :cx :cy :r :rx :ry :transform))))))

(defn create-image-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [transform  (->> (usvg/parse-transform (:transform attrs))
                        (gmt/transform-in (gpt/point svg-data)))

        image-url  (or (:href attrs) (:xlink:href attrs))
        image-data (dm/get-in svg-data [:image-data image-url])


        metadata   {:width (:width image-data)
                    :height (:height image-data)
                    :mtype (:mtype image-data)
                    :id (:id image-data)}

        origin     (gpt/negate (gpt/point svg-data))
        rect-data  (-> (parse-rect-attrs attrs)
                       (update :x - (:x origin))
                       (update :y - (:y origin)))]

    (when (some? image-data)
      (cts/setup-shape
       (-> (calculate-rect-metadata rect-data transform)
           (assoc :type :image)
           (assoc :name name)
           (assoc :frame-id frame-id)
           (assoc :metadata metadata)
           (assoc :svg-viewbox (select-keys rect-data [:x :y :width :height]))
           (assoc :svg-attrs (dissoc attrs :x :y :width :height :href :xlink:href)))))))


(defn parse-svg-element [frame-id svg-data {:keys [tag attrs hidden] :as element-data} unames]
  (let [attrs        (usvg/format-styles attrs)
        element-data (cond-> element-data (map? element-data) (assoc :attrs attrs))
        name         (or (:id attrs) (tag->name tag))
        att-refs     (usvg/find-attr-references attrs)
        references   (usvg/find-def-references (:defs svg-data) att-refs)

        href-id      (-> (or (:href attrs) (:xlink:href attrs) "") (subs 1))
        defs         (:defs svg-data)

        use-tag?     (and (= :use tag) (contains? defs href-id))]

    (if use-tag?
      (let [;; Merge the data of the use definition with the properties passed as attributes
            use-data     (-> (get defs href-id)
                             (update :attrs #(d/deep-merge % (dissoc attrs :xlink:href :href))))
            displacement (gpt/point (d/parse-double (:x attrs "0")) (d/parse-double (:y attrs "0")))
            disp-matrix  (str (gmt/translate-matrix displacement))
            element-data (-> element-data
                             (assoc :tag :g)
                             (update :attrs dissoc :x :y :width :height :href :xlink:href :transform)
                             (update :attrs usvg/add-transform disp-matrix)
                             (assoc :content [use-data]))]
        (parse-svg-element frame-id svg-data element-data unames))

      ;; SVG graphic elements
      ;; :circle :ellipse :image :line :path :polygon :polyline :rect :text :use
      (let [shape (-> (case tag
                        (:g :a :svg) (create-group name frame-id svg-data element-data)
                        :rect        (create-rect-shape name frame-id svg-data element-data)
                        (:circle
                         :ellipse)   (create-circle-shape name frame-id svg-data element-data)
                        :path        (create-path-shape name frame-id svg-data element-data)
                        :polyline    (create-path-shape name frame-id svg-data (-> element-data usvg/polyline->path))
                        :polygon     (create-path-shape name frame-id svg-data (-> element-data usvg/polygon->path))
                        :line        (create-path-shape name frame-id svg-data (-> element-data usvg/line->path))
                        :image       (create-image-shape name frame-id svg-data element-data)
                        #_other      (create-raw-svg name frame-id svg-data element-data)))]
        (when (some? shape)
          (let [shape (-> shape
                          (assoc :svg-defs (select-keys (:defs svg-data) references))
                          (setup-fill)
                          (setup-stroke)
                          (setup-opacity))]

            [(cond-> shape
               hidden (assoc :hidden true))

             (cond->> (:content element-data)
               (contains? usvg/parent-tags tag)
               (mapv #(usvg/inherit-attributes attrs %)))]))))))

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

(defn data-uri->blob
  [data-uri]
  (let [[mtype b64-data] (str/split data-uri ";base64,")
        mtype   (subs mtype (inc (str/index-of mtype ":")))
        decoded (.atob js/window b64-data)
        size    (.-length ^js decoded)
        content (js/Uint8Array. size)]

    (doseq [i (range 0 size)]
      (aset content i (.charCodeAt decoded i)))

    (wapi/create-blob content mtype)))

(defn extract-name [url]
  (let [query-idx (str/last-index-of url "?")
        url (if (> query-idx 0) (subs url 0 query-idx) url)
        filename (->> (str/split url "/") (last))
        ext-idx (str/last-index-of filename ".")]
    (if (> ext-idx 0) (subs filename 0 ext-idx) filename)))

(defn upload-images
  "Extract all bitmap images inside the svg data, and upload them, associated to the file.
  Return a map {<url> <image-data>}."
  [svg-data file-id]
  (->> (rx/from (usvg/collect-images svg-data))
       (rx/map (fn [uri]
                 (merge
                   {:file-id file-id
                    :is-local true
                    :url uri}
                   (if (str/starts-with? uri "data:")
                     {:name "image"
                      :content (data-uri->blob uri)}
                     {:name (extract-name uri)}))))
       (rx/mapcat (fn [uri-data]
                    (->> (rp/cmd! (if (contains? uri-data :content)
                                    :upload-file-media-object
                                    :create-file-media-object-from-url)
                                  uri-data)
                         ;; When the image uploaded fail we skip the shape
                         ;; returning `nil` will afterward not create the shape.
                         (rx/catch #(rx/of nil))
                         (rx/map #(vector (:url uri-data) %)))))
       (rx/reduce (fn [acc [url image]] (assoc acc url image)) {})))


(defn create-svg-shapes
  [svg-data {:keys [x y]} objects frame-id parent-id selected center?]
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
            (usvg/fix-default-values)
            (usvg/fix-percents)
            (usvg/extract-defs))

        svg-data   (assoc svg-data :defs def-nodes)
        root-shape (create-svg-root frame-id parent-id svg-data)
        root-id    (:id root-shape)

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

        ;; Create the root shape
        root-attrs (-> (:attrs svg-data)
                       (usvg/format-styles))

        [_ children]
        (reduce (partial create-svg-children objects selected frame-id root-id svg-data)
                [unames []]
                (d/enumerate (->> (:content svg-data)
                                  (mapv #(usvg/inherit-attributes root-attrs %)))))]

    [root-shape children]))

(defn add-svg-shapes
  [svg-data position]
  ;; (app.common.pprint/pprint svg-data {:length 100 :level 100})
  (ptk/reify ::add-svg-shapes
    ptk/WatchEvent
    (watch [it state _]
      (try
        (let [page-id         (:current-page-id state)
              objects         (wsh/lookup-page-objects state page-id)
              frame-id        (ctst/top-nested-frame objects position)
              selected        (wsh/lookup-selected state)
              base            (cph/get-base-shape objects selected)

              selected-id     (first selected)
              selected-frame? (and (= 1 (count selected))
                                   (= :frame (dm/get-in objects [selected-id :type])))

              parent-id       (if (or selected-frame? (empty? selected))
                                frame-id
                                (:parent-id base))

              [new-shape new-children]
              (create-svg-shapes svg-data position objects frame-id parent-id selected true)

              changes         (-> (pcb/empty-changes it page-id)
                                  (pcb/with-objects objects)
                                  (pcb/add-object new-shape))

              changes         (reduce (fn [changes new-child]
                                        (pcb/add-object changes new-child))
                                      changes
                                      new-children)

              changes         (pcb/resize-parents changes
                                                  (->> (:redo-changes changes)
                                                       (filter #(= :add-obj (:type %)))
                                                       (map :id)
                                                       (reverse)
                                                       (vec)))
              undo-id         (js/Symbol)]

          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/commit-changes changes)
                 (dws/select-shapes (d/ordered-set (:id new-shape)))
                 (ptk/data-event :layout/update [(:id new-shape)])
                 (dwu/commit-undo-transaction undo-id)))

        (catch :default cause
          (js/console.log (.-stack cause))
          (rx/throw {:type :svg-parser
                     :data cause}))))))
