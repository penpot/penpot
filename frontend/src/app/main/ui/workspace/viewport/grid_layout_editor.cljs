;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.grid-layout-editor
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.cursors :as cur]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn apply-to-point [result next-fn]
  (conj result (next-fn (last result))))

(defn format-size [{:keys [type value]}]
  (case type
    :fixed (dm/str (fmt/format-number value) "PX")
    :percent (dm/str (fmt/format-number value) "%")
    :flex (dm/str (fmt/format-number value) "FR")
    :auto "AUTO"))

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

    [:polygon
     {:class (css :grid-frame)
      :points (->> frame-points
                   (map #(dm/fmt "%,%" (:x %) (:y %)))
                   (str/join " "))}]))

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

    [:g {:class (css :grid-plus-button)
         :on-click handle-click}

     [:rect {:class (css :grid-plus-shape)
             :x rect-x
             :y rect-y
             :width (/ 40 zoom)
             :height (/ 40 zoom)}]

     [:use {:class (css :grid-plus-icon)
            :x icon-x
            :y icon-y
            :width (/ 16 zoom)
            :height (/ 16 zoom)
            :href (dm/str "#icon-plus")}]]))

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
                   :on-drag-end handle-drag-end})]
    [:rect
     {:x x
      :y y
      :height height
      :width width
      :class (if (= direction :row)
               (cur/get-dynamic "scale-ns" (:rotation shape))
               (cur/get-dynamic "scale-ew" (:rotation shape)))
      :style {:fill "transparent"
              :stroke-width 0}
      :on-pointer-down handle-pointer-down
      :on-lost-pointer-capture handle-lost-pointer-capture
      :on-pointer-move handle-pointer-move}]))

(mf/defc grid-cell-area-label
  {::mf/wrap-props false}
  [props]

  (let [cell-origin (unchecked-get props "origin")
        cell-width  (unchecked-get props "width")
        zoom  (unchecked-get props "zoom")
        text  (unchecked-get props "text")

        area-width (/ (* 10 (count text)) zoom)
        area-height (/ 25 zoom)
        area-x (- (+ (:x cell-origin) cell-width) area-width)
        area-y (:y cell-origin)

        area-text-x (+ area-x (/ area-width 2))
        area-text-y (+ area-y (/ area-height 2))]

    [:g {:pointer-events "none"}
     [:rect {:x area-x
             :y area-y
             :width area-width
             :height area-height
             :style {:fill "var(--color-distance)"
                     :fill-opacity 0.3}}]
     [:text {:x area-text-x
             :y area-text-y
             :style {:fill "var(--color-distance)"
                     :font-family "worksans"
                     :font-weight 600
                     :font-size (/ 14 zoom)
                     :alignment-baseline "central"
                     :text-anchor "middle"}}
      text]]))

(mf/defc grid-cell
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "cell" "layout-data" "zoom" "hover?" "selected?"]))]
   ::mf/wrap-props false}
  [props]
  (let [shape       (unchecked-get props "shape")
        cell        (unchecked-get props "cell")
        layout-data (unchecked-get props "layout-data")
        zoom        (unchecked-get props "zoom")
        hover?      (unchecked-get props "hover?")
        selected?   (unchecked-get props "selected?")


        cell-bounds (gsg/cell-bounds layout-data cell)
        cell-origin (gpo/origin cell-bounds)
        cell-width  (gpo/width-points cell-bounds)
        cell-height (gpo/height-points cell-bounds)
        cell-center (gsh/center-points cell-bounds)
        cell-origin (gpt/transform cell-origin (gmt/transform-in cell-center (:transform-inverse shape)))

        handle-pointer-enter
        (mf/use-callback
         (mf/deps (:id shape) (:id cell))
         (fn []
           (st/emit! (dwge/hover-grid-cell (:id shape) (:id cell) true))))

        handle-pointer-leave
        (mf/use-callback
         (mf/deps (:id shape) (:id cell))
         (fn []
           (st/emit! (dwge/hover-grid-cell (:id shape) (:id cell) false))))

        handle-pointer-down
        (mf/use-callback
         (mf/deps (:id shape) (:id cell) selected?)
         (fn []
           (if selected?
             (st/emit! (dwge/remove-selection (:id shape)))
             (st/emit! (dwge/select-grid-cell (:id shape) (:id cell))))))]

    [:g.cell-editor
     [:rect
      {:transform (dm/str (gmt/transform-in cell-center (:transform shape)))
       :class (dom/classnames (css :grid-cell-outline) true
                              (css :hover) hover?
                              (css :selected) selected?)
       :x (:x cell-origin)
       :y (:y cell-origin)
       :width cell-width
       :height cell-height

       :on-pointer-enter handle-pointer-enter
       :on-pointer-leave handle-pointer-leave
       :on-pointer-down handle-pointer-down}]

     (when (:area-name cell)
       [:& grid-cell-area-label {:origin cell-origin
                                 :width cell-width
                                 :zoom zoom
                                 :text (:area-name cell)}])

     (when selected?
       (let [handlers
             ;; Handlers positions, size and cursor
             [[:top (:x cell-origin) (+ (:y cell-origin) (/ -10 zoom)) cell-width (/ 20 zoom) :row]
              [:right (+ (:x cell-origin) cell-width (/ -10 zoom)) (:y cell-origin) (/ 20 zoom) cell-height :column]
              [:bottom (:x cell-origin) (+ (:y cell-origin) cell-height (/ -10 zoom)) cell-width (/ 20 zoom) :row]
              [:left (+ (:x cell-origin) (/ -10 zoom)) (:y cell-origin) (/ 20 zoom) cell-height :column]]]
         [:g {:transform (dm/str (gmt/transform-in cell-center (:transform shape)))}
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

(defn use-resize-track
  [type shape index track-before track-after zoom snap-pixel?]

  (let [start-size-before (mf/use-var nil)
        start-size-after (mf/use-var nil)

        handle-drag-start
        (mf/use-callback
         (mf/deps shape track-before track-after)
         (fn []
           (reset! start-size-before (:size track-before))
           (reset! start-size-after (:size track-after))
           (let [tracks-prop
                 (if (= :column type) :layout-grid-columns :layout-grid-rows)
                 shape
                 (-> shape
                     (cond-> (some? track-before)
                       (update-in [tracks-prop (dec index)] merge {:type :fixed :value (:size track-before)}))
                     (cond-> (some? track-after)
                       (update-in [tracks-prop index] merge {:type :fixed :value (:size track-after)})))

                 modifiers
                 (-> (ctm/empty)
                     (ctm/change-property tracks-prop (get shape tracks-prop)))]
             (st/emit! (dwm/set-modifiers (dwm/create-modif-tree [(:id shape)] modifiers))))))

        handle-drag-position
        (mf/use-callback
         (mf/deps shape track-before track-after)
         (fn [position]
           (let [[tracks-prop axis]
                 (if (= :column type) [:layout-grid-columns :x] [:layout-grid-rows :y])

                 precision (if snap-pixel? mth/round identity)
                 delta (/ (get position axis) zoom)

                 shape
                 (-> shape
                     (cond-> (some? track-before)
                       (update-in [tracks-prop (dec index)] merge {:type :fixed :value (precision (+ @start-size-before delta))}))
                     (cond-> (some? track-after)
                       (update-in [tracks-prop index] merge {:type :fixed :value (precision (- @start-size-after delta))})))

                 modifiers
                 (-> (ctm/empty)
                     (ctm/change-property tracks-prop (get shape tracks-prop)))]
             (st/emit! (dwm/set-modifiers (dwm/create-modif-tree [(:id shape)] modifiers))))))

        handle-drag-end
        (mf/use-callback
         (mf/deps track-before track-after)
         (fn []
           (reset! start-size-before nil)
           (reset! start-size-after nil)
           (st/emit! (dwm/apply-modifiers))))]

    (use-drag {:on-drag-start handle-drag-start
               :on-drag-delta handle-drag-position
               :on-drag-end handle-drag-end})))

(mf/defc resize-track-handler
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        index (unchecked-get props "index")
        last? (unchecked-get props "last?")
        track-before (unchecked-get props "track-before")
        track-after (unchecked-get props "track-after")
        snap-pixel? (unchecked-get props "snap-pixel?")

        {:keys [column-total-size column-total-gap row-total-size row-total-gap]} (unchecked-get props "layout-data")
        start-p (unchecked-get props "start-p")
        type (unchecked-get props "type")
        zoom (unchecked-get props "zoom")

        bounds (:points shape)
        hv #(gpo/start-hv bounds %)
        vv #(gpo/start-vv bounds %)

        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        {:keys [handle-pointer-down handle-lost-pointer-capture handle-pointer-move]}
        (use-resize-track type shape index track-before track-after zoom snap-pixel?)

        [width height]
        (if (= type :column)
          [(max layout-gap-col (/ 16 zoom))
           (+ row-total-size row-total-gap)]

          [(+ column-total-size column-total-gap)
           (max layout-gap-row (/ 16 zoom))])

        start-p
        (cond-> start-p
          (and (= type :column) (= index 0))
          (gpt/subtract (hv width))

          (and (= type :row) (= index 0))
          (gpt/subtract (vv height))

          (and (= type :column) (not= index 0) (not last?))
          (-> (gpt/subtract (hv (/ layout-gap-col 2)))
              (gpt/subtract (hv (/ width 2))))

          (and (= type :row) (not= index 0) (not last?))
          (-> (gpt/subtract (vv (/ layout-gap-row 2)))
              (gpt/subtract (vv (/ height 2)))))]

    [:rect.resize-track-handler
     {:x (:x start-p)
      :y (:y start-p)
      :height height
      :width width
      :on-pointer-down handle-pointer-down
      :on-lost-pointer-capture handle-lost-pointer-capture
      :on-pointer-move handle-pointer-move
      :transform (dm/str (gmt/transform-in start-p (:transform shape)))
      :class (if (= type :column)
               (cur/get-dynamic "resize-ew" (:rotation shape))
               (cur/get-dynamic "resize-ns" (:rotation shape)))
      :style {:fill "transparent"
              :opacity 0.5
              :stroke-width 0}}]))

(mf/defc track-marker
  {::mf/wrap-props false}
  [props]

  (let [center (unchecked-get props "center")
        value (unchecked-get props "value")
        zoom (unchecked-get props "zoom")
        shape (unchecked-get props "shape")
        index (unchecked-get props "index")
        type (unchecked-get props "type")
        track-before (unchecked-get props "track-before")
        track-after (unchecked-get props "track-after")
        snap-pixel? (unchecked-get props "snap-pixel?")

        marker-width (/ 24 zoom)
        marker-h1 (/ 22 zoom)
        marker-h2 (/ 8 zoom)

        marker-half-width (/ marker-width 2)
        marker-half-height (/ (+ marker-h1 marker-h2) 2)

        marker-points
        (reduce
         apply-to-point
         [(gpt/subtract center
                        (gpt/point marker-half-width marker-half-height))]
         [#(gpt/add % (gpt/point marker-width 0))
          #(gpt/add % (gpt/point 0 marker-h1))
          #(gpt/add % (gpt/point (- marker-half-width) marker-h2))
          #(gpt/subtract % (gpt/point marker-half-width marker-h2))])

        text-x (:x center)
        text-y (:y center)

        {:keys [handle-pointer-down handle-lost-pointer-capture handle-pointer-move]}
        (use-resize-track type shape index track-before track-after zoom snap-pixel?)]

    [:g {:on-pointer-down handle-pointer-down
         :on-lost-pointer-capture handle-lost-pointer-capture
         :on-pointer-move handle-pointer-move
         :class (dom/classnames (css :grid-track-marker) true
                                (cur/get-dynamic "resize-ew" (:rotation shape)) (= type :column)
                                (cur/get-dynamic "resize-ns" (:rotation shape)) (= type :row))
         :transform (dm/str (gmt/transform-in center (:transform shape)))}

     [:polygon {:class (css :marker-shape)
                :points (->> marker-points
                             (map #(dm/fmt "%,%" (:x %) (:y %)))
                             (str/join " "))}]
     [:text {:class (css :marker-text)
             :x text-x
             :y text-y
             :width (/ 26.26 zoom)
             :height (/ 36 zoom)
             :text-anchor "middle"
             :dominant-baseline "middle"}
      (dm/str value)]]))

(mf/defc track
  {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "zoom" "index" "type" "track-data" "layout-data"]))]
   ::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        zoom (unchecked-get props "zoom")
        type (unchecked-get props "type")
        index (unchecked-get props "index")
        snap-pixel? (unchecked-get props "snap-pixel?")
        track-data (unchecked-get props "track-data")
        layout-data (unchecked-get props "layout-data")

        track-input-ref (mf/use-ref)
        [layout-gap-row layout-gap-col] (ctl/gaps shape)

        bounds (:points shape)
        vv     #(gpo/start-vv bounds %)
        hv     #(gpo/start-hv bounds %)

        start-p (:start-p track-data)

        hpt (gpo/project-point bounds :h start-p)
        vpt (gpo/project-point bounds :v start-p)

        marker-p
        (if (= type :column)
          (-> hpt
              (gpt/subtract (vv (/ 20 zoom)))
              (cond-> (not= index 0)
                (gpt/subtract (hv (/ layout-gap-col 2)))))
          (-> vpt
              (gpt/subtract (hv (/ 20 zoom)))
              (cond-> (not= index 0)
                (gpt/subtract (vv (/ layout-gap-row 2))))))

        text-p (if (= type :column) hpt vpt)

        handle-blur-track-input
        (mf/use-callback
         (mf/deps (:id shape))
         (fn [event]
           (let [target (-> event dom/get-target)
                 value  (-> target dom/get-input-value str/upper)
                 value-int (d/parse-integer value)

                 [track-type value]
                 (cond
                   (str/ends-with? value "%")
                   [:percent value-int]

                   (str/ends-with? value "FR")
                   [:flex value-int]

                   (some? value-int)
                   [:fixed value-int]

                   (or (= value "AUTO") (= "" value))
                   [:auto nil])]

             (if (some? track-type)
               (do (st/emit! (dwsl/change-layout-track [(:id shape)] type index {:type track-type :value value}))
                   (dom/set-data! target "default-value" (format-size {:type track-type :value value})))
               (obj/set! target "value" (dom/get-attribute target "data-default-value"))))))

        handle-keydown-track-input
        (mf/use-callback
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)]
             (when enter?
               (dom/blur! (dom/get-target event)))
             (when esc?
               (dom/blur! (dom/get-target event))))))

        track-list-prop (if (= type :column) :column-tracks :row-tracks)
        [text-x text-y text-width text-height]
        (if (= type :column)
          [(:x text-p) (- (:y text-p) (/ 36 zoom)) (max 0 (:size track-data)) (/ 36 zoom)]
          [(- (:x text-p) (max 0 (:size track-data))) (- (:y text-p) (/ 36 zoom)) (max 0 (:size track-data)) (/ 36 zoom)])

        track-before (get-in layout-data [track-list-prop (dec index)])]

    (mf/use-effect
     (mf/deps track-data)
     (fn []
       (dom/set-value! (mf/ref-val track-input-ref) (format-size track-data))))

    [:g.track
     [:g {:transform (if (= type :column)
                       (dm/str (gmt/transform-in text-p (:transform shape)))
                       (dm/str (gmt/transform-in text-p (gmt/rotate (:transform shape) -90))))}
      [:foreignObject {:x text-x :y text-y :width text-width :height text-height}
       [:input
        {:ref track-input-ref
         :class (css :grid-editor-label)
         :type "text"
         :default-value (format-size track-data)
         :data-default-value (format-size track-data)
         :on-key-down handle-keydown-track-input
         :on-blur handle-blur-track-input}]]]

     [:g {:transform (when (= type :row) (dm/fmt "rotate(-90 % %)" (:x marker-p) (:y marker-p)))}
      [:& track-marker
       {:center marker-p
        :index index
        :shape shape
        :snap-pixel? snap-pixel?
        :track-after track-data
        :track-before track-before
        :type type
        :value (dm/str (inc index))
        :zoom zoom}]]

     [:& resize-track-handler
      {:index index
       :layout-data layout-data
       :shape shape
       :snap-pixel? snap-pixel?
       :start-p start-p
       :track-after track-data
       :track-before track-before
       :type type
       :zoom zoom}]]))

(mf/defc editor
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]

  (let [base-shape (unchecked-get props "shape")
        objects    (unchecked-get props "objects")
        modifiers  (unchecked-get props "modifiers")
        zoom       (unchecked-get props "zoom")
        view-only  (unchecked-get props "view-only")

        shape
        (mf/use-memo
         (mf/deps modifiers base-shape)
         #(gsh/transform-shape
           base-shape
           (dm/get-in modifiers [(:id base-shape) :modifiers])))

        snap-pixel? (mf/deref refs/snap-pixel?)

        grid-edition-id-ref
        (mf/use-memo
         (mf/deps (:id shape))
         #(refs/workspace-grid-edition-id (:id shape)))

        grid-edition (mf/deref grid-edition-id-ref)

        hover-cells (:hover grid-edition)
        selected-cells (:selected grid-edition)

        children (->> (:shapes shape)
                      (map (d/getf objects))
                      (map #(gsh/transform-shape % (dm/get-in modifiers [(:id %) :modifiers])))
                      (remove :hidden)
                      (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))

        children (hooks/use-equal-memo children)

        bounds (:points shape)
        hv     #(gpo/start-hv bounds %)
        vv     #(gpo/start-vv bounds %)
        width  (gpo/width-points bounds)
        height (gpo/height-points bounds)
        origin (gpo/origin bounds)

        {:keys [row-tracks column-tracks] :as layout-data}
        (mf/use-memo
         (mf/deps shape children)
         #(gsg/calc-layout-data shape children bounds))

        handle-pointer-down
        (mf/use-callback
         (fn [event]
           (let [left-click? (= 1 (.-which (.-nativeEvent event)))]
             (when left-click?
               (dom/stop-propagation event)))))

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

    [:g.grid-editor {:pointer-events (when view-only "none")
                     :on-pointer-down handle-pointer-down}
     (when-not view-only
       [:*
        [:& grid-editor-frame {:zoom zoom
                               :bounds bounds}]
        (let [start-p (-> origin (gpt/add (hv width)))]
          [:g {:transform (dm/str (gmt/transform-in start-p (:transform shape)))}
           [:& plus-btn {:start-p start-p
                         :zoom zoom
                         :type :column
                         :on-click handle-add-column}]])

        (let [start-p (-> origin (gpt/add (vv height)))]
          [:g {:transform (dm/str (gmt/transform-in start-p (:transform shape)))}
           [:& plus-btn {:start-p start-p
                         :zoom zoom
                         :type :row
                         :on-click handle-add-row}]])

        (for [[idx column-data] (d/enumerate column-tracks)]
          [:& track {:key (dm/str "column-track-" idx)
                     :shape shape
                     :zoom zoom
                     :type :column
                     :index idx
                     :layout-data layout-data
                     :snap-pixel? snap-pixel?
                     :track-data column-data}])

        ;; Last track resize handler
        (when-not (empty? column-tracks)
          (let [last-track (last column-tracks)
                start-p (:start-p last-track)
                end-p (gpt/add start-p (hv (:size last-track)))
                marker-p (-> (gpo/project-point bounds :h end-p)
                             (gpt/subtract (vv (/ 20 zoom))))]
            [:g.track
             [:& track-marker {:center marker-p
                               :index (count column-tracks)
                               :shape shape
                               :snap-pixel? snap-pixel?
                               :track-before (last column-tracks)
                               :type :column
                               :value (dm/str (inc (count column-tracks)))
                               :zoom zoom}]
             [:& resize-track-handler
              {:index (count column-tracks)
               :last? true
               :shape shape
               :layout-data layout-data
               :snap-pixel? snap-pixel?
               :start-p end-p
               :type :column
               :track-before (last column-tracks)
               :zoom zoom}]]))

        (for [[idx row-data] (d/enumerate row-tracks)]
          [:& track {:index idx
                     :key (dm/str "row-track-" idx)
                     :layout-data layout-data
                     :shape shape
                     :snap-pixel? snap-pixel?
                     :track-data row-data
                     :type :row
                     :zoom zoom}])

        (when-not (empty? row-tracks)
          (let [last-track (last row-tracks)
                start-p (:start-p last-track)
                end-p (gpt/add start-p (vv (:size last-track)))
                marker-p (-> (gpo/project-point bounds :v end-p)
                             (gpt/subtract (hv (/ 20 zoom))))]
            [:g.track
             [:g {:transform (dm/fmt "rotate(-90 % %)" (:x marker-p) (:y marker-p))}
              [:& track-marker {:center marker-p
                                :index (count row-tracks)
                                :shape shape
                                :snap-pixel? snap-pixel?
                                :track-before (last row-tracks)
                                :type :row
                                :value (dm/str (inc (count row-tracks)))
                                :zoom zoom}]]
             [:& resize-track-handler
              {:index (count row-tracks)
               :last? true
               :shape shape
               :layout-data layout-data
               :start-p end-p
               :type :row
               :track-before (last row-tracks)
               :snap-pixel? snap-pixel?
               :zoom zoom}]]))])

     [:g.cells
      (for [cell (ctl/get-cells shape {:sort? true})]
        [:& grid-cell {:key (dm/str "cell-" (:id cell))
                       :shape base-shape
                       :layout-data layout-data
                       :cell cell
                       :zoom zoom
                       :hover? (contains? hover-cells (:id cell))
                       :selected? (= selected-cells (:id cell))}])]]))
