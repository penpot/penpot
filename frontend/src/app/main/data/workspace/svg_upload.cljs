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
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :refer [max-safe-int min-safe-int]]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.util.color :as uc]
   [app.util.names :as un]
   [app.util.path.parser :as upp]
   [app.util.svg :as usvg]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defonce default-rect {:x 0 :y 0 :width 1 :height 1 :rx 0 :ry 0})
(defonce default-circle {:r 0 :cx 0 :cy 0})
(defonce default-image {:x 0 :y 0 :width 1 :height 1 :rx 0 :ry 0})

(defn- assert-valid-num [attr num]
  (when (or (not (d/num? num))
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
        (update-in [:svg-attrs :style] dissoc :fill)
        (assoc-in [:fills 0 :fill-color] (-> (get-in shape [:svg-attrs :fill])
                                             (str/trim)
                                             (uc/parse-color))))

    ;; Color present as style
    (uc/color? (str/trim (get-in shape [:svg-attrs :style :fill])))
    (-> (update-in [:svg-attrs :style] dissoc :fill)
        (update :svg-attrs dissoc :fill)
        (assoc-in [:fills 0 :fill-color] (-> (get-in shape [:svg-attrs :style :fill])
                                             (str/trim)
                                             (uc/parse-color))))

    (get-in shape [:svg-attrs :fill-opacity])
    (-> (update :svg-attrs dissoc :fill-opacity)
        (update-in [:svg-attrs :style] dissoc :fill-opacity)
        (assoc-in [:fills 0 :fill-opacity] (-> (get-in shape [:svg-attrs :fill-opacity])
                                               (d/parse-double))))

    (get-in shape [:svg-attrs :style :fill-opacity])
    (-> (update-in [:svg-attrs :style] dissoc :fill-opacity)
        (update :svg-attrs dissoc :fill-opacity)
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
              (assoc-in [:strokes 0 :stroke-color] (-> (get-in shape [:svg-attrs :stroke])
                                                       (str/trim)
                                                       (uc/parse-color))))

          (uc/color? (str/trim (get-in shape [:svg-attrs :style :stroke])))
          (-> (update-in [:svg-attrs :style] dissoc :stroke)
              (assoc-in [:strokes 0 :stroke-color] (-> (get-in shape [:svg-attrs :style :stroke])
                                                       (str/trim)
                                                       (uc/parse-color))))

          (get-in shape [:svg-attrs :stroke-opacity])
          (-> (update :svg-attrs dissoc :stroke-opacity)
              (assoc-in [:strokes 0 :stroke-opacity] (-> (get-in shape [:svg-attrs :stroke-opacity])
                                                         (d/parse-double))))

          (get-in shape [:svg-attrs :style :stroke-opacity])
          (-> (update-in [:svg-attrs :style] dissoc :stroke-opacity)
              (assoc-in [:strokes 0 :stroke-opacity] (-> (get-in shape [:svg-attrs :style :stroke-opacity])
                                                       (d/parse-double))))

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
              (cond-> (#{:round :square} stroke-linecap)
                (assoc :stroke-cap-start stroke-linecap
                       :stroke-cap-end   stroke-linecap))))]

    (cond-> shape
      (d/any-key? (get-in shape [:strokes 0]) :stroke-color :stroke-opacity :stroke-width :stroke-cap-start :stroke-cap-end)
      (assoc-in [:strokes 0 :stroke-style] :svg))))

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
        (cp/setup-rect-selrect))))

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
        (cp/setup-rect-selrect)
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
        (cp/setup-rect-selrect))))

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

        center      (gsh/center-points points)
        rect-shape  (gsh/center->rect center (:width rect-data) (:height rect-data))
        selrect     (gsh/rect->selrect rect-shape)
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
            (contains? attrs :rx) (assoc :rx (d/parse-double (:rx attrs 0)))
            (contains? attrs :ry) (assoc :ry (d/parse-double (:ry attrs 0))))

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

        image-url (or (:href attrs) (:xlink:href attrs))
        image-data (get-in svg-data [:image-data image-url])

        rect (->> (select-keys attrs [:x :y :width :height])
                  (d/mapm #(d/parse-double %2)))

        origin (gpt/negate (gpt/point svg-data))

        rect-data (-> (merge default-image rect)
                      (update :x - (:x origin))
                      (update :y - (:y origin)))

        rect-metadata (calculate-rect-metadata rect-data transform)]

    (when (some? image-data)
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
          (assoc :svg-attrs (dissoc attrs :x :y :width :height :href :xlink:href))))))

(defn parse-svg-element [frame-id svg-data element-data unames]
  (let [{:keys [tag attrs hidden]} element-data
        attrs (usvg/format-styles attrs)
        element-data (cond-> element-data (map? element-data) (assoc :attrs attrs))
        name (un/generate-unique-name unames (or (:id attrs) (tag->name tag)))
        att-refs (usvg/find-attr-references attrs)
        references (usvg/find-def-references (:defs svg-data) att-refs)

        href-id (-> (or (:href attrs) (:xlink:href attrs) "")
                    (subs 1))
        defs (:defs svg-data)

        use-tag? (and (= :use tag) (contains? defs href-id))]

    (if use-tag?
      (let [;; Merge the data of the use definition with the properties passed as attributes
            use-data (-> (get defs href-id)
                         (update :attrs #(d/deep-merge % (dissoc attrs :xlink:href :href))))
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
                        #_other      (create-raw-svg name frame-id svg-data element-data)))]
        (when (some? shape)
          (let [shape (-> shape
                          (assoc :fills [])
                          (assoc :strokes [])
                          (assoc :svg-defs (select-keys (:defs svg-data) references))
                          (setup-fill)
                          (setup-stroke))

                shape (cond-> shape
                        hidden (assoc :hidden true))

                children (cond->> (:content element-data)
                           (or (= tag :g) (= tag :svg))
                           (mapv #(usvg/inherit-attributes attrs %)))]
            [shape children]))))))

(defn add-svg-child-changes [page-id objects selected frame-id parent-id svg-data [unames changes] [index data]]
  (let [[shape children] (parse-svg-element frame-id svg-data data unames)]
    (if (some? shape)
      (let [shape-id (:id shape)

            new-shape (dwsh/make-new-shape shape objects selected)
            changes   (-> changes
                          (pcb/add-object new-shape)
                          (pcb/change-parent parent-id [new-shape] index))

            unames  (conj unames (:name new-shape))

            reducer-fn (partial add-svg-child-changes page-id objects selected frame-id shape-id svg-data)]
        (reduce reducer-fn [unames changes] (d/enumerate children)))

      [unames changes])))

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

              unames (un/retrieve-used-names objects)

              svg-name (->> (str/replace (:name svg-data) ".svg" "")
                            (un/generate-unique-name unames))

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

              ;; In penpot groups have the size of their children. To respect the imported svg size and empty space let's create a transparent shape as background to respect the imported size
              base-background-shape {:tag :rect
                                     :attrs {:x "0"
                                             :y "0"
                                             :width (str (:width root-shape))
                                             :height (str (:height root-shape))
                                             :fill "none"
                                             :id "base-background"}
                                     :hidden true
                                     :content []}

              svg-data (-> svg-data
                           (assoc :defs def-nodes)
                           (assoc :content (into [base-background-shape] (:content svg-data))))

              ;; Creates the root shape
              new-shape (dwsh/make-new-shape root-shape objects selected)

              changes   (-> (pcb/empty-changes it page-id)
                            (pcb/with-objects objects)
                            (pcb/add-object new-shape))

              root-attrs (-> (:attrs svg-data)
                             (usvg/format-styles))

              ;; Reduce the children to create the changes to add the children shapes
              [_ changes]
              (reduce (partial add-svg-child-changes page-id objects selected frame-id root-id svg-data)
                      [unames changes]
                      (d/enumerate (->> (:content svg-data)
                                        (mapv #(usvg/inherit-attributes root-attrs %)))))
              changes (pcb/resize-parents changes
                                          (->> changes
                                               :redo-changes
                                               (filter #(= :add-obj (:type %)))
                                               (map :id)
                                               reverse
                                               vec))]

          (rx/of (dch/commit-changes changes)
                 (dws/select-shapes (d/ordered-set root-id))))

        (catch :default e
          (.error js/console "Error SVG" e)
          (rx/throw {:type :svg-parser
                     :data e}))))))
