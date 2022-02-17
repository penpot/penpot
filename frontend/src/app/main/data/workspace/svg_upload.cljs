;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.svg-upload
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.spec :refer [max-safe-int min-safe-int]]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.repo :as rp]
   [app.util.color :as uc]
   [app.util.path.parser :as upp]
   [app.util.svg :as usvg]
   [app.util.uri :as uu]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defonce default-rect {:x 0 :y 0 :width 1 :height 1 :rx 0 :ry 0})
(defonce default-circle {:r 0 :cx 0 :cy 0})
(defonce default-image {:x 0 :y 0 :width 1 :height 1})

(defn- assert-valid-num [attr num]
  (when (or (nil? num)
            (mth/nan? num)
            (not (mth/finite? num))
            (>= num max-safe-int )
            (<= num  min-safe-int))
    (ex/raise (str (d/name attr) " attribute invalid: " num)))

  ;; If the number is between 0-1 we round to 1 (same in negative form
  (cond
    (and (> num 0) (< num 1))  1
    (and (< num 0) (> num -1)) -1
    :else                      num))

(defn- assert-valid-pos-num [attr num]
  (let [num (assert-valid-num attr num)]
    (when (< num 0)
      (ex/raise (str (d/name attr) " attribute invalid: " num)))
    num))

(defn- svg-dimensions [data]
  (let [width (get-in data [:attrs :width] 100)
        height (get-in data [:attrs :height] 100)
        viewbox (get-in data [:attrs :viewBox] (str "0 0 " width " " height))
        [x y width height] (->> (str/split viewbox #"\s+")
                                (map d/parse-double))]
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
  (cond-> shape
    ;; Color present as attribute
    (uc/color? (str/trim (get-in shape [:svg-attrs :fill])))
    (-> (update :svg-attrs dissoc :fill)
        (assoc-in [:fills 0 :fill-color] (-> (get-in shape [:svg-attrs :fill])
                                             (str/trim)
                                             (uc/parse-color))))

    ;; Color present as style
    (uc/color? (str/trim (get-in shape [:svg-attrs :style :fill])))
    (-> (update-in [:svg-attrs :style] dissoc :fill)
        (assoc-in [:fills 0 :fill-color] (-> (get-in shape [:svg-attrs :style :fill])
                                             (str/trim)
                                             (uc/parse-color))))

    (get-in shape [:svg-attrs :fill-opacity])
    (-> (update :svg-attrs dissoc :fill-opacity)
        (assoc-in [:fills 0 :fill-opacity] (-> (get-in shape [:svg-attrs :fill-opacity])
                                                (d/parse-double))))

    (get-in shape [:svg-attrs :style :fill-opacity])
    (-> (update-in [:svg-attrs :style] dissoc :fill-opacity)
        (assoc-in [:fills 0 :fill-opacity] (-> (get-in shape [:svg-attrs :style :fill-opacity])
                                                (d/parse-double))))))

(defn setup-stroke [shape]
  (let [stroke-linecap (-> (or (get-in shape [:svg-attrs :stroke-linecap])
                               (get-in shape [:svg-attrs :style :stroke-linecap]))
                           ((d/nilf str/trim))
                           ((d/nilf keyword)))

        shape
        (cond-> shape
          (uc/color? (str/trim (get-in shape [:svg-attrs :stroke])))
          (-> (update :svg-attrs dissoc :stroke)
              (assoc-in [:strokes 0 :stroke-color] (get-in shape [:svg-attrs :stroke])))

          (uc/color? (str/trim (get-in shape [:svg-attrs :style :stroke])))
          (-> (update-in [:svg-attrs :style] dissoc :stroke)
              (assoc-in [:strokes 0 :stroke-color] (get-in shape [:svg-attrs :style :stroke])))

          (get-in shape [:svg-attrs :stroke-width])
          (-> (update :svg-attrs dissoc :stroke-width)
              (assoc-in [:strokes 0 :stroke-width] (-> (get-in shape [:svg-attrs :stroke-width])
                                                       (d/parse-double))))

          (get-in shape [:svg-attrs :style :stroke-width])
          (-> (update-in [:svg-attrs :style] dissoc :stroke-width)
              (assoc-in [:strokes 0 :stroke-width] (-> (get-in shape [:svg-attrs :style :stroke-width])
                                                       (d/parse-double))))

          (and stroke-linecap (= (:type shape) :path))
          (-> (update-in [:svg-attrs :style] dissoc :stroke-linecap)
              (cond->
                (#{:round :square} stroke-linecap)
                (assoc :stroke-cap-start stroke-linecap
                       :stroke-cap-end   stroke-linecap))))]

    (if (d/any-key? (get-in [:strokes 0] shape) :stroke-color :stroke-opacity :stroke-width :stroke-cap-start :stroke-cap-end)
      (assoc-in shape [:strokes 0 :stroke-style] :svg)
      shape)))

(defn setup-opacity [shape]
  (cond-> shape
    (get-in shape [:svg-attrs :opacity])
    (-> (update :svg-attrs dissoc :opacity)
        (assoc :opacity (get-in shape [:svg-attrs :opacity])))

    (get-in shape [:svg-attrs :style :opacity])
    (-> (update-in [:svg-attrs :style] dissoc :opacity)
        (assoc :opacity (get-in shape [:svg-attrs :style :opacity])))


    (get-in shape [:svg-attrs :mix-blend-mode])
    (-> (update :svg-attrs dissoc :mix-blend-mode)
        (assoc :blend-mode (-> (get-in shape [:svg-attrs :mix-blend-mode]) keyword)))

    (get-in shape [:svg-attrs :style :mix-blend-mode])
    (-> (update-in [:svg-attrs :style] dissoc :mix-blend-mode)
        (assoc :blend-mode (-> (get-in shape [:svg-attrs :style :mix-blend-mode]) keyword)))))

(defn create-raw-svg [name frame-id svg-data {:keys [attrs] :as data}]
  (let [{:keys [x y width height offset-x offset-y]} svg-data]
    (-> {:id (uuid/next)
         :type :svg-raw
         :name name
         :frame-id frame-id
         :width width
         :height height
         :x x
         :y y
         :content (cond-> data
                    (map? data) (update :attrs usvg/clean-attrs))}
        (assoc :svg-attrs attrs)
        (assoc :svg-viewbox (-> (select-keys svg-data [:width :height])
                                (assoc :x offset-x :y offset-y)))
        (gsh/setup-selrect))))

(defn create-svg-root [frame-id svg-data]
  (let [{:keys [name x y width height offset-x offset-y]} svg-data]
    (-> {:id (uuid/next)
         :type :group
         :name name
         :frame-id frame-id
         :width width
         :height height
         :x (+ x offset-x)
         :y (+ y offset-y)}
        (gsh/setup-selrect)
        (assoc :svg-attrs (-> (:attrs svg-data)
                              (dissoc :viewBox :xmlns)
                              (d/without-keys usvg/inheritable-props))))))

(defn create-group [name frame-id svg-data {:keys [attrs]}]
  (let [svg-transform (usvg/parse-transform (:transform attrs))
        {:keys [x y width height offset-x offset-y]} svg-data]
    (-> {:id (uuid/next)
         :type :group
         :name name
         :frame-id frame-id
         :x (+ x offset-x)
         :y (+ y offset-y)
         :width width
         :height height}
        (assoc :svg-transform svg-transform)
        (assoc :svg-attrs (d/without-keys attrs usvg/inheritable-props))
        (assoc :svg-viewbox (-> (select-keys svg-data [:width :height])
                                (assoc :x offset-x :y offset-y)))
        (gsh/setup-selrect))))

(defn create-path-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (when (and (contains? attrs :d) (seq (:d attrs)))
    (let [svg-transform (usvg/parse-transform (:transform attrs))
          path-content (upp/parse-path (:d attrs))
          content (cond-> path-content
                    svg-transform
                    (gsh/transform-content svg-transform))

          selrect (gsh/content->selrect content)
          points (gsh/rect->points selrect)

          origin (gpt/negate (gpt/point svg-data))]
      (-> {:id (uuid/next)
           :type :path
           :name name
           :frame-id frame-id
           :content content
           :selrect selrect
           :points points}
          (assoc :svg-viewbox (select-keys selrect [:x :y :width :height]))
          (assoc :svg-attrs (dissoc attrs :d :transform))
          (assoc :svg-transform svg-transform)
          (gsh/translate-to-frame origin)))))

(defn calculate-rect-metadata [rect-data transform]
  (let [points (-> (gsh/rect->points rect-data)
                   (gsh/transform-points transform))

        center (gsh/center-points points)

        rect-shape (-> (gsh/make-centered-rect center (:width rect-data) (:height rect-data))
                       (update :width max 1)
                       (update :height max 1))

        selrect (gsh/rect->selrect rect-shape)

        rect-points (gsh/rect->points rect-shape)

        [shape-transform shape-transform-inv rotation]
        (gsh/calculate-adjust-matrix points rect-points (neg? (:a transform)) (neg? (:d transform)))]

    (merge rect-shape
           {:selrect selrect
            :points points
            :rotation rotation
            :transform shape-transform
            :transform-inverse shape-transform-inv})))


(defn create-rect-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [svg-transform (usvg/parse-transform (:transform attrs))
        transform (->> svg-transform
                       (gmt/transform-in (gpt/point svg-data)))

        rect (->> (select-keys attrs [:x :y :width :height])
                  (d/mapm #(d/parse-double %2)))

        origin (gpt/negate (gpt/point svg-data))

        rect-data (-> (merge default-rect rect)
                      (update :x - (:x origin))
                      (update :y - (:y origin)))

        metadata (calculate-rect-metadata rect-data transform)]
    (-> {:id (uuid/next)
         :type :rect
         :name name
         :frame-id frame-id}
        (cond->
            (contains? attrs :rx) (assoc :rx (d/parse-double (:rx attrs)))
            (contains? attrs :ry) (assoc :ry (d/parse-double (:ry attrs))))

        (merge metadata)
        (assoc :svg-viewbox (select-keys rect [:x :y :width :height]))
        (assoc :svg-attrs (dissoc attrs :x :y :width :height :rx :ry :transform)))))


(defn create-circle-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [svg-transform (usvg/parse-transform (:transform attrs))
        transform (->> svg-transform
                       (gmt/transform-in (gpt/point svg-data)))

        circle (->> (select-keys attrs [:r :ry :rx :cx :cy])
                    (d/mapm #(d/parse-double %2)))

        {:keys [cx cy]} circle

        rx (or (:r circle) (:rx circle))
        ry (or (:r circle) (:ry circle))

        rect {:x (- cx rx)
              :y (- cy ry)
              :width (* 2 rx)
              :height (* 2 ry)}

        origin (gpt/negate (gpt/point svg-data))

        rect-data (-> rect
                      (update :x - (:x origin))
                      (update :y - (:y origin)))

        metadata (calculate-rect-metadata rect-data transform)]
    (-> {:id (uuid/next)
         :type :circle
         :name name
         :frame-id frame-id}

        (merge metadata)
        (assoc :svg-viewbox (select-keys rect [:x :y :width :height]))
        (assoc :svg-attrs (dissoc attrs :cx :cy :r :rx :ry :transform)))))

(defn create-image-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [svg-transform (usvg/parse-transform (:transform attrs))
        transform (->> svg-transform
                       (gmt/transform-in (gpt/point svg-data)))

        image-url (:xlink:href attrs)
        image-data (get-in svg-data [:image-data image-url])

        rect (->> (select-keys attrs [:x :y :width :height])
                  (d/mapm #(d/parse-double %2)))

        origin (gpt/negate (gpt/point svg-data))

        rect-data (-> (merge default-image rect)
                      (update :x - (:x origin))
                      (update :y - (:y origin)))

        rect-metadata (calculate-rect-metadata rect-data transform)]
    (-> {:id (uuid/next)
         :type :image
         :name name
         :frame-id frame-id
         :metadata {:width (:width image-data)
                    :height (:height image-data)
                    :mtype (:mtype image-data)
                    :id (:id image-data)}}

        (merge rect-metadata)
        (assoc :svg-viewbox (select-keys rect [:x :y :width :height]))
        (assoc :svg-attrs (dissoc attrs :x :y :width :height :xlink:href)))))

(defn parse-svg-element [frame-id svg-data element-data unames]
  (let [{:keys [tag attrs]} element-data
        attrs (usvg/format-styles attrs)
        element-data (cond-> element-data (map? element-data) (assoc :attrs attrs))
        name (dwc/generate-unique-name unames (or (:id attrs) (tag->name tag)))
        att-refs (usvg/find-attr-references attrs)
        references (usvg/find-def-references (:defs svg-data) att-refs)

        href-id (-> (or (:href attrs) (:xlink:href attrs) "")
                    (subs 1))
        defs (:defs svg-data)

        use-tag? (and (= :use tag) (contains? defs href-id))]

    (if use-tag?
      (let [use-data (get defs href-id)

            displacement (gpt/point (d/parse-double (:x attrs "0")) (d/parse-double (:y attrs "0")))
            disp-matrix (str (gmt/translate-matrix displacement))
            element-data (-> element-data
                             (assoc :tag :g)
                             (update :attrs dissoc :x :y :width :height :href :xlink:href)
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
                        #_other      (create-raw-svg name frame-id svg-data element-data)))

            shape (assoc shape :fills [])
            shape (assoc shape :strokes [])

            shape (when (some? shape)
                    (-> shape
                        (assoc :svg-defs (select-keys (:defs svg-data) references))
                        (setup-fill)
                        (setup-stroke)))

            children (cond->> (:content element-data)
                       (or (= tag :g) (= tag :svg))
                       (mapv #(usvg/inherit-attributes attrs %)))]
        [shape children]))))

(defn add-svg-child-changes [page-id objects selected frame-id parent-id svg-data [unames [rchs uchs]] [index data]]
  (let [[shape children] (parse-svg-element frame-id svg-data data unames)]
    (if (some? shape)
      (let [shape-id (:id shape)

            [rch1 uch1] (dwc/add-shape-changes page-id objects selected shape false)

            ;; Mov-objects won't have undo because we "delete" the object in the undo of the
            ;; previous operation
            rch2 [{:type :mov-objects
                   :parent-id parent-id
                   :frame-id frame-id
                   :page-id page-id
                   :index index
                   :shapes [shape-id]}]

            ;; Careful! the undo changes are concatenated reversed (we undo in reverse order
            changes [(d/concat-vec rchs rch1 rch2)
                     (d/concat-vec uch1 uchs)]
            unames  (conj unames (:name shape))

            reducer-fn (partial add-svg-child-changes page-id objects selected frame-id shape-id svg-data)]
        (reduce reducer-fn [unames changes] (d/enumerate children)))

      ;; Cannot create the data from current tags
      [unames [rchs uchs]])))

(declare create-svg-shapes)

(defn svg-uploaded
  [svg-data file-id position]
  (ptk/reify ::svg-uploaded
    ptk/WatchEvent
    (watch [_ _ _]
      ;; Once the SVG is uploaded, we need to extract all the bitmap
      ;; images and upload them separately, then proceed to create
      ;; all shapes.
      (->> (rx/from (usvg/collect-images svg-data))
           (rx/map (fn [uri]
                     (d/merge
                      {:file-id file-id
                       :is-local true
                       :url uri}

                      (if (str/starts-with? uri "data:")
                        {:name "image"
                         :content (uu/data-uri->blob uri)}
                        {:name (uu/uri-name uri)}))))
           (rx/mapcat (fn [uri-data]
                        (->> (rp/mutation! (if (contains? uri-data :content)
                                             :upload-file-media-object
                                             :create-file-media-object-from-url) uri-data)
                             (rx/map #(vector (:url uri-data) %)))))
           (rx/reduce (fn [acc [url image]] (assoc acc url image)) {})
           (rx/map #(create-svg-shapes (assoc svg-data :image-data %) position))))))

(defn create-svg-shapes
  [svg-data {:keys [x y] :as position}]
  (ptk/reify ::create-svg-shapes
    ptk/WatchEvent
    (watch [it state _]
      (try
        (let [page-id  (:current-page-id state)
              objects  (wsh/lookup-page-objects state page-id)
              frame-id (cph/frame-id-by-position objects position)
              selected (wsh/lookup-selected state)

              [vb-x vb-y vb-width vb-height] (svg-dimensions svg-data)
              x (- x vb-x (/ vb-width 2))
              y (- y vb-y (/ vb-height 2))

              unames (dwc/retrieve-used-names objects)

              svg-name (->> (str/replace (:name svg-data) ".svg" "")
                            (dwc/generate-unique-name unames))

              svg-data (-> svg-data
                           (assoc :x x
                                  :y y
                                  :offset-x vb-x
                                  :offset-y vb-y
                                  :width vb-width
                                  :height vb-height
                                  :name svg-name))

              [def-nodes svg-data] (-> svg-data
                                       (usvg/fix-default-values)
                                       (usvg/fix-percents)
                                       (usvg/extract-defs))

              svg-data (assoc svg-data :defs def-nodes)

              root-shape (create-svg-root frame-id svg-data)
              root-id (:id root-shape)

              ;; Creates the root shape
              changes (dwc/add-shape-changes page-id objects selected root-shape false)

              root-attrs (-> (:attrs svg-data)
                             (usvg/format-styles))

              ;; Reduces the children to create the changes to add the children shapes
              [_ [rchanges uchanges]]
              (reduce (partial add-svg-child-changes page-id objects selected frame-id root-id svg-data)
                      [unames changes]
                      (d/enumerate (->> (:content svg-data)
                                        (mapv #(usvg/inherit-attributes root-attrs %)))))

              reg-objects-action {:type :reg-objects
                                  :page-id page-id
                                  :shapes (->> rchanges (filter #(= :add-obj (:type %))) (map :id) reverse vec)}

              rchanges (conj rchanges reg-objects-action)]

          (rx/of (dch/commit-changes {:redo-changes rchanges
                                      :undo-changes uchanges
                                      :origin it})
                 (dwc/select-shapes (d/ordered-set root-id))))

        (catch :default e
          (.error js/console "Error SVG" e)
          (rx/throw {:type :svg-parser
                     :data e}))))))
