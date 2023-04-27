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
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
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
  (let [dragging-ref    (mf/use-ref false)
        start-pos-ref   (mf/use-ref nil)
        current-pos-ref (mf/use-ref nil)

        handle-pointer-down
        (mf/use-callback
         (mf/deps on-drag-start)
         (fn [event]
           (let [raw-pt (dom/get-client-position event)
                 position (uwvv/point->viewport raw-pt)]
             (dom/capture-pointer event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-pos-ref raw-pt)
             (mf/set-ref-val! current-pos-ref raw-pt)
             (when on-drag-start (on-drag-start position)))))

        handle-lost-pointer-capture
        (mf/use-callback
         (mf/deps on-drag-end)
         (fn [event]
           (let [raw-pt (mf/ref-val current-pos-ref)
                 position (uwvv/point->viewport raw-pt)]
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-pos-ref nil)
             (when on-drag-end (on-drag-end position)))))

        handle-pointer-move
        (mf/use-callback
         (mf/deps on-drag-delta on-drag-position)
         (fn [event]
           (when (mf/ref-val dragging-ref)
             (let [start (mf/ref-val start-pos-ref)
                   pos   (dom/get-client-position event)
                   pt (uwvv/point->viewport pos)]
               (mf/set-ref-val! current-pos-ref pos)
               (when on-drag-delta (on-drag-delta (gpt/to-vec start pos)))
               (when on-drag-position (on-drag-position pt))))))]

    {:handle-pointer-down handle-pointer-down
     :handle-lost-pointer-capture handle-lost-pointer-capture
     :handle-pointer-move handle-pointer-move}))

(mf/defc resize-cell-handler
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        x (unchecked-get props "x")
        y (unchecked-get props "y")
        width (unchecked-get props "width")
        height (unchecked-get props "height")
        handler (unchecked-get props "handler")

        {cell-id :id} (unchecked-get props "cell")
        {:keys [row column row-span column-span]} (get-in shape [:layout-grid-cells cell-id])

        direction (unchecked-get props "direction")
        layout-data (unchecked-get props "layout-data")
        cursor (if (= direction :row) (cur/scale-ns 0) (cur/scale-ew 0))

        handle-drag-position
        (mf/use-callback
         (mf/deps shape row column row-span column-span)
         (fn [position]
           (let [[drag-row  drag-column] (gsg/get-position-grid-coord layout-data position)

                 [new-row new-column new-row-span new-column-span]
                 (case handler
                   :top
                   (let [new-row      (min (+ row (dec row-span)) drag-row)
                         new-row-span (+ (- row new-row) row-span)]
                     [new-row column new-row-span column-span])

                   :left
                   (let [new-column      (min (+ column (dec column-span)) drag-column)
                         new-column-span (+ (- column new-column) column-span)]
                     [row new-column row-span new-column-span])

                   :bottom
                   (let [new-row-span (max 1 (inc (- drag-row row)))]
                     [row column new-row-span column-span])

                   :right
                   (let [new-column-span (max 1 (inc (- drag-column column)))]
                     [row column row-span new-column-span]))

                 shape
                 (-> (ctl/resize-cell-area shape row column new-row new-column new-row-span new-column-span)
                     (ctl/assign-cells))

                 modifiers
                 (-> (ctm/empty)
                     (ctm/change-property :layout-grid-rows (:layout-grid-rows shape))
                     (ctm/change-property :layout-grid-columns (:layout-grid-columns shape))
                     (ctm/change-property :layout-grid-cells (:layout-grid-cells shape)))]
             (st/emit! (dwm/set-modifiers (dwm/create-modif-tree [(:id shape)] modifiers))))))

        handle-drag-end
        (mf/use-callback
         (fn []
           (st/emit! (dwm/apply-modifiers))))

        {:keys [handle-pointer-down handle-lost-pointer-capture handle-pointer-move]}
        (use-drag {:on-drag-position handle-drag-position
                   ;;:on-drag-start handle-drag-start
                   :on-drag-end handle-drag-end})]
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

        {:keys [row column row-span column-span] :as cell}  (unchecked-get props "cell")
        {:keys [origin row-tracks column-tracks layout-bounds column-gap row-gap] :as layout-data}
        (unchecked-get props "layout-data")

        zoom    (unchecked-get props "zoom")

        hover?    (unchecked-get props "hover?")
        selected?    (unchecked-get props "selected?")

        span-column-tracks (subvec column-tracks (dec column) (+ (dec column) column-span))
        span-row-tracks (subvec row-tracks (dec row) (+ (dec row) row-span))

        hv     #(gpo/start-hv layout-bounds %)
        vv     #(gpo/start-vv layout-bounds %)

        start-p (gpt/add origin
                         (gpt/add
                          (gpt/to-vec origin (dm/get-in span-column-tracks [0 :start-p]))
                          (gpt/to-vec origin (dm/get-in span-row-tracks [0 :start-p]))))

        end-p
        (as-> start-p  $
          (reduce (fn [p track] (gpt/add p (hv (:size track)))) $ span-column-tracks)
          (reduce (fn [p track] (gpt/add p (vv (:size track)))) $ span-row-tracks)
          (gpt/add $ (hv (* column-gap (dec (count span-column-tracks)))))
          (gpt/add $ (vv (* row-gap (dec (count span-row-tracks))))))

        cell-width  (- (:x end-p) (:x start-p))
        cell-height (- (:y end-p) (:y start-p))]

    [:g.cell-editor
     [:rect
      {:x (:x start-p)
       :y (:y start-p)
       :width cell-width
       :height cell-height

       :on-pointer-enter #(st/emit! (dwge/hover-grid-cell (:id shape) (:id cell) true))
       :on-pointer-leave #(st/emit! (dwge/hover-grid-cell (:id shape) (:id cell) false))
       :on-click #(st/emit! (dwge/select-grid-cell (:id shape) (:id cell)))

       :style {:fill "transparent"
               :stroke "var(--color-distance)"
               :stroke-dasharray (when-not (or hover? selected?)
                                   (str/join " " (map #(/ % zoom) [0 8]) ))
               :stroke-linecap "round"
               :stroke-width (/ 2 zoom)}}]

     (when selected?
       (let [handlers
             ;; Handlers positions, size and cursor
             [[:top (:x start-p) (+ (:y start-p) (/ -10 zoom)) cell-width (/ 20 zoom) :row]
              [:right (+ (:x start-p) cell-width (/ -10 zoom)) (:y start-p) (/ 20 zoom) cell-height :column]
              [:bottom (:x start-p) (+ (:y start-p) cell-height (/ -10 zoom)) cell-width (/ 20 zoom) :row]
              [:left (+ (:x start-p) (/ -10 zoom)) (:y start-p) (/ 20 zoom) cell-height :column]]]
         [:*
          (for [[handler x y width height dir] handlers]
            [:& resize-cell-handler {:key (dm/str "resize-" (d/name handler) "-" (:id cell))
                                     :shape shape
                                     :handler handler
                                     :x x
                                     :y y
                                     :cell cell
                                     :width width
                                     :height height
                                     :direction dir
                                     :layout-data layout-data}])]))]))

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
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]

  (let [shape     (unchecked-get props "shape")
        objects   (unchecked-get props "objects")
        zoom      (unchecked-get props "zoom")
        view-only (unchecked-get props "view-only")
        bounds  (:points shape)

        ;; We need to know the state unmodified so we can create the modifiers
        shape-ref (mf/use-memo (mf/deps (:id shape)) #(refs/object-by-id (:id shape)))
        base-shape (mf/deref shape-ref)

        grid-edition-id-ref (mf/use-memo #(refs/workspace-grid-edition-id (:id shape)))
        grid-edition (mf/deref grid-edition-id-ref)

        hover-cells (:hover grid-edition)
        selected-cells (:selected grid-edition)

        children (->> (:shapes shape)
                      (map (d/getf objects))
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

    [:g.grid-editor {:pointer-events (when view-only "none")}
     (when-not view-only
       [:*
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
                        :on-click handle-add-row}])])

     (for [[idx column-data] (d/enumerate column-tracks)]
       (let [start-p (:start-p column-data)
             marker-p (-> start-p
                          (gpt/subtract (vv (/ 20 zoom)))
                          (cond-> (not= idx 0)
                            (gpt/subtract (hv (/ layout-gap-col 2)))))

             text-p (-> start-p
                        (gpt/subtract (vv (/ 20 zoom)))
                        (gpt/add (hv (/ (:size column-data) 2))))]
         [:* {:key (dm/str "column-" idx)}
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
         [:* {:key (dm/str "row-" idx)}
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

     (for [[_ cell] (:layout-grid-cells shape)]
       [:& grid-cell {:key (dm/str "cell-" (:id cell))
                      :shape base-shape
                      :layout-data layout-data
                      :cell cell
                      :zoom zoom
                      :hover? (contains? hover-cells (:id cell))
                      :selected? (= selected-cells (:id cell))}])]))
