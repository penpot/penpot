;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.svg-upload
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.util.color :as uc]
   [app.util.geom.path :as ugp]
   [app.util.object :as obj]
   [app.util.svg :as usvg]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defn- svg-dimensions [data]
  (let [width (get-in data [:attrs :width] 100)
        height (get-in data [:attrs :height] 100)
        viewbox (get-in data [:attrs :viewBox] (str "0 0 " width " " height))
        [x y width height] (->> (str/split viewbox " ")
                                (map d/parse-double))]
    [x y width height]))

(defn tag->name
  "Given a tag returns its layer name"
  [tag]
  (str "svg-" (cond (string? tag) tag
                    (keyword? tag) (name tag)
                    (nil? tag) "node"
                    :else (str tag))))

(defn setup-fill [shape]
  (cond-> shape
    ;; Color present as attribute
    (uc/color? (get-in shape [:svg-attrs :fill]))
    (-> (update :svg-attrs dissoc :fill)
        (assoc :fill-color (-> (get-in shape [:svg-attrs :fill])
                               (uc/parse-color))))

    ;; Color present as style
    (uc/color? (get-in shape [:svg-attrs :style :fill]))
    (-> (update-in [:svg-attrs :style] dissoc :fill)
        (assoc :fill-color (-> (get-in shape [:svg-attrs :style :fill])
                               (uc/parse-color))))

    (get-in shape [:svg-attrs :fill-opacity])
    (-> (update :svg-attrs dissoc :fill-opacity)
        (assoc :fill-opacity (-> (get-in shape [:svg-attrs :fill-opacity])
                                 (d/parse-double))))

    (get-in shape [:svg-attrs :style :fill-opacity])
    (-> (update-in [:svg-attrs :style] dissoc :fill-opacity)
        (assoc :fill-opacity (-> (get-in shape [:svg-attrs :style :fill-opacity])
                                 (d/parse-double))))))

(defn setup-stroke [shape]
  (let [shape
        (cond-> shape
          (uc/color? (get-in shape [:svg-attrs :stroke]))
          (-> (update :svg-attrs dissoc :stroke)
              (assoc :stroke-color (get-in shape [:svg-attrs :stroke])))

          (uc/color? (get-in shape [:svg-attrs :style :stroke]))
          (-> (update-in [:svg-attrs :style] dissoc :stroke)
              (assoc :stroke-color (get-in shape [:svg-attrs :style :stroke])))

          (get-in shape [:svg-attrs :stroke-width])
          (-> (update :svg-attrs dissoc :stroke-width)
              (assoc :stroke-width (-> (get-in shape [:svg-attrs :stroke-width])
                                       (d/parse-double))))

          (get-in shape [:svg-attrs :style :stroke-width])
          (-> (update-in [:svg-attrs :style] dissoc :stroke-width)
              (assoc :stroke-width (-> (get-in shape [:svg-attrs :style :stroke-width])
                                       (d/parse-double)))))]
    (if (d/any-key? shape :stroke-color :stroke-opacity :stroke-width)
      (merge {:stroke-style :svg} shape)
      shape)))

(defn create-raw-svg [name frame-id svg-data {:keys [attrs] :as data}]
  (let [{:keys [x y width height]} svg-data]
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
        (assoc :svg-viewbox (select-keys svg-data [0 0 :width :height]))
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
                              (dissoc :viewBox :xmlns))))))

(defn create-group [name frame-id svg-data {:keys [attrs]}]
  (let [{:keys [x y width height offset-x offset-y]} svg-data]
    (-> {:id (uuid/next)
         :type :group
         :name name
         :frame-id frame-id
         :x (+ x offset-x)
         :y (+ y offset-y)
         :width width
         :height height}
        (assoc :svg-attrs (dissoc attrs :transform))
        (assoc :svg-viewbox (select-keys svg-data [:x :y :width :height]))
        (gsh/setup-selrect))))

(defn create-path-shape [name frame-id svg-data {:keys [attrs] :as data}]
  (let [svg-transform (usvg/parse-transform (:transform attrs))
        content (cond-> (ugp/path->content (:d attrs))
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
        (gsh/translate-to-frame origin))))

(defn inverse-matrix [{:keys [a b c d e f]}]
  (let [dom-matrix (-> (js/DOMMatrix.)
                       (obj/set! "a" a)
                       (obj/set! "b" b)
                       (obj/set! "c" c)
                       (obj/set! "d" d)
                       (obj/set! "e" e)
                       (obj/set! "f" f)
                       (.inverse))]
    (gmt/matrix (obj/get dom-matrix "a")
                (obj/get dom-matrix "b")
                (obj/get dom-matrix "c")
                (obj/get dom-matrix "d")
                (obj/get dom-matrix "e")
                (obj/get dom-matrix "f"))))

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
        (gsh/calculate-adjust-matrix points rect-points)]

    (merge rect-shape
           {:selrect selrect
            :points points
            :rotation rotation
            :transform shape-transform
            :transform-inverse shape-transform-inv})))

(def default-rect {:x 0 :y 0 :width 1 :height 1 :rx 0 :ry 0})

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
        (assoc :svg-transform transform)
        (assoc :svg-viewbox (select-keys rect [:x :y :width :height]))
        (assoc :svg-attrs (dissoc attrs :x :y :width :height :rx :ry :transform)))))

(def default-circle {:r 0 :cx 0 :cy 0})

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
        (assoc :svg-transform transform)
        (assoc :svg-viewbox (select-keys rect [:x :y :width :height]))
        (assoc :svg-attrs (dissoc attrs :cx :cy :r :rx :ry :transform)))))

(defn add-transform [transform node]
  (letfn [(append-transform [old-transform]
            (if (or (nil? old-transform) (empty? old-transform))
              transform
              (str old-transform " " transform)))]

    (cond-> node
      transform
      (update-in [:attrs :transform] append-transform))))

(defn parse-svg-element [frame-id svg-data element-data unames]
  (let [{:keys [tag attrs]} element-data
        attrs (cond-> attrs (contains? attrs :style) usvg/format-styles)
        element-data (cond-> element-data (map? element-data) (assoc :attrs attrs))
        name (dwc/generate-unique-name unames (or (:id attrs) (tag->name tag)) true)
        att-refs (usvg/find-attr-references attrs)
        references (usvg/find-def-references (:defs svg-data) att-refs)

        href-id (-> (or (:href attrs) (:xlink:href attrs) "")
                    (subs 1))
        defs (:defs svg-data)

        use-tag? (and (= :use tag) (contains? defs href-id))]

    (if use-tag?
      (let [use-data (get defs href-id)
            translate (gpt/point (:x attrs 0) (:y attrs 0))
            attrs' (dissoc attrs :x :y :width :height :href :xlink:href)
            ;; TODO: If the child is a symbol we've to take the width/height into account
            use-data (update use-data :attrs #(d/deep-merge attrs' %))
            [shape children] (parse-svg-element frame-id svg-data use-data unames)]
        [(-> shape (gsh/move translate)) children])
      
      ;; SVG graphic elements
      ;; :circle :ellipse :image :line :path :polygon :polyline :rect :text :use
      (let [shape (-> (case tag
                        (:g :a)    (create-group name frame-id svg-data element-data)
                        :rect      (create-rect-shape name frame-id svg-data element-data)
                        (:circle
                         :ellipse) (create-circle-shape name frame-id svg-data element-data)
                        :path      (create-path-shape name frame-id svg-data element-data)
                        #_other    (create-raw-svg name frame-id svg-data element-data))

                      (assoc :svg-defs (select-keys (:defs svg-data) references))
                      (setup-fill)
                      (setup-stroke))

            children (cond->> (:content element-data)
                       (= tag :g)
                       (mapv #(add-transform (:transform attrs) %)))]
        [shape children]))))

(defn add-svg-child-changes [page-id objects selected frame-id parent-id svg-data [unames [rchs uchs]] [index data]]
  (let [[shape children] (parse-svg-element frame-id svg-data data unames)
        shape-id (:id shape)

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
        changes [(d/concat rchs rch1 rch2) (d/concat uch1 uchs)]
        unames (conj unames (:name shape))
        reducer-fn (partial add-svg-child-changes page-id objects selected frame-id shape-id svg-data)]
    (reduce reducer-fn [unames changes] (d/enumerate children))))

(defn svg-uploaded [svg-data x y]
  (ptk/reify ::svg-uploaded
    ptk/WatchEvent
    (watch [_ state stream]
      (try
        (let [page-id (:current-page-id state)
              objects (dwc/lookup-page-objects state page-id)
              frame-id (cp/frame-id-by-position objects {:x x :y y})
              selected (get-in state [:workspace-local :selected])

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

              [def-nodes svg-data] (usvg/extract-defs svg-data)
              svg-data (assoc svg-data :defs def-nodes)

              root-shape (create-svg-root frame-id svg-data)
              root-id (:id root-shape)

              changes (dwc/add-shape-changes page-id objects selected root-shape)

              reducer-fn (partial add-svg-child-changes page-id objects selected frame-id root-id svg-data)
              [_ [rchanges uchanges]] (reduce reducer-fn [unames changes] (d/enumerate (:content svg-data)))

              reg-objects-action {:type :reg-objects
                                  :page-id page-id
                                  :shapes (->> rchanges (map :id) (remove nil?) (into #{root-id}) vec)}

              rchanges (conj rchanges reg-objects-action)]

          (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                 (dwc/select-shapes (d/ordered-set root-id))))

        (catch :default e
          (.error js/console "Error upload" e))))))
