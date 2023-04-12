;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.grid-layout-editor
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn apply-to-point [result next-fn]
  (conj result (next-fn (last result))))

(defn format-size [{:keys [type value]}]
  (case type
    :fixed (str value "PX")
    :percent (str value "%")
    :flex (str value "FR")
    :auto "AUTO"))

(mf/defc track-marker
  {::mf/wrap-props false}
  [props]

  (let [center (unchecked-get props "center")
        value (unchecked-get props "value")
        zoom (unchecked-get props "zoom")

        marker-points
        (reduce
         apply-to-point
         [(gpt/subtract center
                        (gpt/point (/ 13 zoom) (/ 16 zoom)))]
         [#(gpt/add % (gpt/point (/ 26 zoom) 0))
          #(gpt/add % (gpt/point 0 (/ 24 zoom)))
          #(gpt/add % (gpt/point (- (/ 13 zoom)) (/ 8 zoom)))
          #(gpt/subtract % (gpt/point (/ 13 zoom) (/ 8 zoom)))])

        text-x (:x center)
        text-y (:y center)]

    [:g.grid-track-marker
     [:polygon {:points (->> marker-points
                             (map #(dm/fmt "%,%" (:x %) (:y %)))
                             (str/join " "))

                :style {:fill "var(--color-distance)"
                        :fill-opacity 0.3}}]
     [:text {:x text-x
             :y text-y
             :width (/ 26.26 zoom)
             :height (/ 32 zoom)
             :font-size (/ 16 zoom)
             :font-family "worksans"
             :font-weight 600
             :text-anchor "middle"
             :dominant-baseline "middle"
             :style {:fill "var(--color-distance)"}}
      (dm/str value)]]))

(mf/defc grid-editor-frame
  {::mf/wrap-props false}
  [props]

  (let [bounds (unchecked-get props "bounds")
        zoom (unchecked-get props "zoom")
        hv     #(gpo/start-hv bounds %)
        vv     #(gpo/start-vv bounds %)
        width  (gpo/width-points bounds)
        height (gpo/height-points bounds)
        origin (gpo/origin bounds)

        frame-points
        (reduce
         apply-to-point
         [origin]
         [#(gpt/add % (hv width))
          #(gpt/subtract % (vv (/ 40 zoom)))
          #(gpt/subtract % (hv (+ width (/ 40 zoom))))
          #(gpt/add % (vv (+ height (/ 40 zoom))))
          #(gpt/add % (hv (/ 40 zoom)))])]

    [:polygon {:points (->> frame-points
                            (map #(dm/fmt "%,%" (:x %) (:y %)))
                            (str/join " "))
               :style {:stroke "var(--color-distance)"
                       :stroke-width (/ 1 zoom)}}]))

(mf/defc plus-btn
  {::mf/wrap-props false}
  [props]

  (let [start-p  (unchecked-get props "start-p")
        zoom     (unchecked-get props "zoom")
        type     (unchecked-get props "type")
        on-click (unchecked-get props "on-click")

        [rect-x rect-y icon-x icon-y]
        (if (= type :column)
          [(:x start-p)
           (- (:y start-p) (/ 40 zoom))
           (+ (:x start-p) (/ 12 zoom))
           (- (:y start-p) (/ 28 zoom))]

          [(- (:x start-p) (/ 40 zoom))
           (:y start-p)
           (- (:x start-p) (/ 28 zoom))
           (+ (:y start-p) (/ 12 zoom))])

        handle-click
        (mf/use-callback
         (mf/deps on-click)
         #(when on-click (on-click)))]

    [:g.plus-button {:cursor "pointer"
                     :on-click handle-click}
     [:rect {:x rect-x
             :y rect-y
             :width (/ 40 zoom)
             :height (/ 40 zoom)
             :style {:fill "var(--color-distance)"
                     :stroke "var(--color-distance)"
                     :stroke-width (/ 1 zoom)}}]

     [:use {:x icon-x
            :y icon-y
            :width (/ 16 zoom)
            :height (/ 16 zoom)
            :href (dm/str "#icon-plus")
            :fill "white"}]]))

(defn use-drag
  [{:keys [on-drag-start on-drag-end on-drag-delta on-drag-position]}]
  (let [
        dragging-ref    (mf/use-ref false)
        start-pos-ref   (mf/use-ref nil)
        current-pos-ref (mf/use-ref nil)

        handle-pointer-down
        (mf/use-callback
         (mf/deps on-drag-start)
         (fn [event]
           (let [position (dom/get-client-position event)]
             (dom/capture-pointer event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-pos-ref position)
             (mf/set-ref-val! current-pos-ref position)
             (when on-drag-start (on-drag-start position)))))

        handle-lost-pointer-capture
        (mf/use-callback
         (mf/deps on-drag-end)
         (fn [event]
           (let [position (mf/ref-val current-pos-ref)]
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-pos-ref nil)
             (when on-drag-end (on-drag-end position)))))

        handle-pointer-move
        (mf/use-callback
         (fn [event]
           (when (mf/ref-val dragging-ref)
             (let [start (mf/ref-val start-pos-ref)
                   pos   (dom/get-client-position event)]
               (mf/set-ref-val! current-pos-ref pos)
               (when on-drag-delta (on-drag-delta (gpt/to-vec start pos)))
               (when on-drag-position (on-drag-position pos))))))]

    {:handle-pointer-down handle-pointer-down
     :handle-lost-pointer-capture handle-lost-pointer-capture
     :handle-pointer-move handle-pointer-move}))

(mf/defc resize-cell-handler
  {::mf/wrap-props false}
  [props]
  (let [x (unchecked-get props "x")
        y (unchecked-get props "y")
        width (unchecked-get props "width")
        height (unchecked-get props "height")
        direction (unchecked-get props "direction")
        cursor (if (= direction :row) (cur/scale-ns 0) (cur/scale-ew 0))

        handle-drag-delta
        (mf/use-callback
         (fn [delta]
           (prn ">>>" delta)))

        {:keys [handle-pointer-down handle-lost-pointer-capture handle-pointer-move]}
        (use-drag {:on-drag-delta handle-drag-delta})]

    [:rect
     {:x x
      :y y
      :height height
      :width width
      :style {:fill "transparent" :stroke-width 0 :cursor cursor}

      :on-pointer-down handle-pointer-down
      :on-lost-pointer-capture handle-lost-pointer-capture
      :on-pointer-move handle-pointer-move}]))

(mf/defc grid-cell
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        {:keys [origin row-tracks column-tracks layout-bounds]} (unchecked-get props "layout-data")

        zoom    (unchecked-get props "zoom")

        hover?    (unchecked-get props "hover?")
        selected?    (unchecked-get props "selected?")

        row (unchecked-get props "row")
        column   (unchecked-get props "column")

        column-track (nth column-tracks (dec column) nil)
        row-track (nth row-tracks (dec row) nil)

        hv     #(gpo/start-hv layout-bounds %)
        vv     #(gpo/start-vv layout-bounds %)

        start-p (gpt/add origin
                         (gpt/add
                          (gpt/to-vec origin (:start-p column-track))
                          (gpt/to-vec origin (:start-p row-track))))

        end-p (-> start-p
                  (gpt/add (hv (:size column-track)))
                  (gpt/add (vv (:size row-track))))

        cell-width  (- (:x end-p) (:x start-p))
        cell-height (- (:y end-p) (:y start-p))]

    [:g.cell-editor
     [:rect
      {:x (:x start-p)
       :y (:y start-p)
       :width cell-width
       :height cell-height

       :on-pointer-enter #(st/emit! (dwge/hover-grid-cell (:id shape) row column true))
       :on-pointer-leave #(st/emit! (dwge/hover-grid-cell (:id shape) row column false))

       :on-click #(st/emit! (dwge/select-grid-cell (:id shape) row column))

       :style {:fill "transparent"
               :stroke "var(--color-distance)"
               :stroke-dasharray (when-not (or hover? selected?)
                                   (str/join " " (map #(/ % zoom) [0 8]) ))
               :stroke-linecap "round"
               :stroke-width (/ 2 zoom)}}]

     (when selected?
       (let [handlers
             ;; Handlers positions, size and cursor
             [[(:x start-p) (+ (:y start-p) (/ -10 zoom)) cell-width (/ 20 zoom) :row]
              [(+ (:x start-p) cell-width (/ -10 zoom)) (:y start-p) (/ 20 zoom) cell-height :column]
              [(:x start-p) (+ (:y start-p) cell-height (/ -10 zoom)) cell-width (/ 20 zoom) :row]
              [(+ (:x start-p) (/ -10 zoom)) (:y start-p) (/ 20 zoom) cell-height :column]]]
         [:*
          (for [[x y width height dir] handlers]
            [:& resize-cell-handler {:x x
                                     :y y
                                     :width width
                                     :height height
                                     :direction dir}])]))]))

(mf/defc resize-handler
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        {:keys [column-total-size column-total-gap row-total-size row-total-gap]} (unchecked-get props "layout-data")
        start-p (unchecked-get props "start-p")
        type (unchecked-get props "type")
        zoom (unchecked-get props "zoom")

        dragging-ref (mf/use-ref false)
        start-ref (mf/use-ref nil)

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        on-pointer-down
        (mf/use-callback
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! dragging-ref true)
           (mf/set-ref-val! start-ref (dom/get-client-position event))))

        on-lost-pointer-capture
        (mf/use-callback
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)))

        on-pointer-move
        (mf/use-callback
         (fn [event]
           (when (mf/ref-val dragging-ref)
             (let [start (mf/ref-val start-ref)
                   pos  (dom/get-client-position event)
                   _delta (-> (gpt/to-vec start pos)
                             (get (if (= type :column) :x :y)))]

               ;; TODO Implement resize
               #_(prn ">Delta" delta)))))


        [x y width height]
        (if (= type :column)
          [(- (:x start-p) (max layout-gap-col (/ 8 zoom)))
           (- (:y start-p) (/ 40 zoom))
           (max layout-gap-col (/ 16 zoom))
           (+ row-total-size row-total-gap (/ 40 zoom))]

          [(- (:x start-p) (/ 40 zoom))
           (- (:y start-p) (max layout-gap-row (/ 8 zoom)))
           (+ column-total-size column-total-gap (/ 40 zoom))
           (max layout-gap-row (/ 16 zoom))])]

    [:rect.resize-handler
     {:x x
      :y y
      :class (if (= type :column)
               "resize-ew-0"
               "resize-ns-0")
      :height height
      :width width
      :on-pointer-down on-pointer-down
      :on-lost-pointer-capture on-lost-pointer-capture
      :on-pointer-move on-pointer-move 
      :style {:fill "transparent"}}]))

(mf/defc editor
  {::mf/wrap-props false}
  [props]

  (let [shape   (unchecked-get props "shape")
        objects (unchecked-get props "objects")
        zoom    (unchecked-get props "zoom")
        bounds  (:points shape)

        grid-edition-id-ref (mf/use-memo #(refs/workspace-grid-edition-id (:id shape)))
        grid-edition (mf/deref grid-edition-id-ref)

        hover-cells (:hover grid-edition)
        selected-cells (:selected grid-edition)

        children (->> (cph/get-immediate-children objects (:id shape))
                      (remove :hidden)
                      (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))

        hv     #(gpo/start-hv bounds %)
        vv     #(gpo/start-vv bounds %)
        width  (gpo/width-points bounds)
        height (gpo/height-points bounds)
        origin (gpo/origin bounds)

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        {:keys [row-tracks column-tracks] :as layout-data}
        (gsg/calc-layout-data shape children bounds)

        handle-add-column
        (mf/use-callback
         (mf/deps (:id shape))
         (fn []
           (st/emit! (st/emit! (dwsl/add-layout-track [(:id shape)] :column ctl/default-track-value)))))

        handle-add-row
        (mf/use-callback
         (mf/deps (:id shape))
         (fn []
           (st/emit! (st/emit! (dwsl/add-layout-track [(:id shape)] :row ctl/default-track-value)))))]

    (mf/use-effect
       (fn []
         #(st/emit! (dwge/stop-grid-layout-editing (:id shape)))))

    [:g.grid-editor
     [:& grid-editor-frame {:zoom zoom
                            :bounds bounds}]
     (let [start-p (-> origin (gpt/add (hv width)))]
       [:& plus-btn {:start-p start-p
                     :zoom zoom
                     :type :column
                     :on-click handle-add-column}])

     (let [start-p (-> origin (gpt/add (vv height)))]
       [:& plus-btn {:start-p start-p
                     :zoom zoom
                     :type :row
                     :on-click handle-add-row}])



     (for [[idx column-data] (d/enumerate column-tracks)]
       (let [start-p (:start-p column-data)
             marker-p (-> start-p
                          (gpt/subtract (vv (/ 20 zoom)))
                          (cond-> (not= idx 0)
                            (gpt/subtract (hv (/ layout-gap-col 2)))))

             text-p (-> start-p
                        (gpt/subtract (vv (/ 20 zoom)))
                        (gpt/add (hv (/ (:size column-data) 2))))]
         [:*
          [:& track-marker {:center marker-p
                            :value (dm/str (inc idx))
                            :zoom zoom}]

          [:text {:x (:x text-p)
                  :y (:y text-p)
                  :font-size (/ 14 zoom)
                  :font-weight 600
                  :font-family "worksans"
                  :dominant-baseline "central"
                  :text-anchor "middle"
                  :style {:fill "var(--color-distance)"}}
           (format-size column-data)]

          (when (not= idx 0)
            [:& resize-handler {:shape shape
                                :layout-data layout-data
                                :start-p start-p
                                :type :column
                                :zoom zoom}])]))

     (for [[idx row-data] (d/enumerate row-tracks)]
       (let [start-p (:start-p row-data)
             marker-p (-> start-p
                          (gpt/subtract (hv (/ 20 zoom)))
                          (cond-> (not= idx 0)
                           (gpt/subtract (vv (/ layout-gap-row 2)))))

             text-p (-> start-p
                        (gpt/subtract (hv (/ 20 zoom)))
                        (gpt/add (vv (/ (:size row-data) 2))))]
         [:*
          [:g {:transform (dm/fmt "rotate(-90 % %)" (:x marker-p) (:y marker-p))}
           [:& track-marker {:center marker-p
                             :value (dm/str (inc idx))
                             :zoom zoom}]]

          [:g {:transform (dm/fmt "rotate(-90 % %)" (:x text-p) (:y text-p))}
           [:text {:x (:x text-p)
                   :y (:y text-p)
                   :font-size (/ 14 zoom)
                   :font-weight 600
                   :font-family "worksans"
                   :dominant-baseline "central"
                   :text-anchor "middle"
                   :style {:fill "var(--color-distance)"}}
            (format-size row-data)]]

          (when (not= idx 0)
            [:& resize-handler {:shape shape
                                :layout-data layout-data
                                :start-p start-p
                                :type :column
                                :zoom zoom}])]))

     (for [[_ {:keys [column row]}] (:layout-grid-cells shape)]
       [:& grid-cell {:shape shape
                      :layout-data layout-data
                      :row row
                      :column column
                      :zoom zoom
                      :hover? (contains? hover-cells [row column])
                      :selected? (= selected-cells [row column])}])]))
