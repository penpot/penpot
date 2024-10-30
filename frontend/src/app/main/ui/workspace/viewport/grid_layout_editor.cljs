;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.grid-layout-editor
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.line :as gl]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.grid-layout.editor :as dwge]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.formats :as fmt]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def small-size-limit 60)
(def medium-size-limit 110)

(defn apply-to-point [result next-fn]
  (conj result (next-fn (last result))))

(defn format-size [{:keys [type value]}]
  (case type
    :fixed (dm/str (fmt/format-number value) "PX")
    :percent (dm/str (fmt/format-number value) "%")
    :flex (dm/str (fmt/format-number value) "FR")
    :auto "AUTO"))

(mf/defc grid-edition-actions
  {::mf/wrap-props false}
  [{:keys [shape]}]
  [:div {:class (stl/css :grid-actions)}
   [:div {:class (stl/css :grid-actions-container)}
    [:div {:class (stl/css :grid-actions-title)}
     (tr "workspace.layout_grid.editor.title")  " " [:span {:stl/css :board-name} (:name shape)]]
    [:button {:class (stl/css :locate-btn)
              :on-click #(st/emit! (dwge/locate-board (:id shape)))}
     (tr "workspace.layout_grid.editor.top-bar.locate")]
    [:button {:class (stl/css :done-btn)
              :on-click #(st/emit! (dw/clear-edition-mode))}
     (tr "workspace.layout_grid.editor.top-bar.done")]]])

(mf/defc grid-editor-frame
  {::mf/wrap-props false}
  [props]

  (let [bounds (unchecked-get props "bounds")
        width (unchecked-get props "width")
        height (unchecked-get props "height")
        zoom (unchecked-get props "zoom")
        hv     #(gpo/start-hv bounds %)
        vv     #(gpo/start-vv bounds %)
        origin (gpo/origin bounds)

        frame-points
        (reduce
         apply-to-point
         [origin]
         [#(gpt/add % (hv (+ width (/ 70 zoom))))
          #(gpt/subtract % (vv (/ 40 zoom)))
          #(gpt/subtract % (hv (+ width (/ 110 zoom))))
          #(gpt/add % (vv (+ height (/ 110 zoom))))
          #(gpt/add % (hv (/ 40 zoom)))])]

    [:polygon
     {:class (stl/css :grid-frame)
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
           (+ (:x start-p) (/ 9 zoom))
           (- (:y start-p) (/ 31 zoom))]

          [(- (:x start-p) (/ 40 zoom))
           (:y start-p)
           (- (:x start-p) (/ 31 zoom))
           (+ (:y start-p) (/ 9 zoom))])

        handle-click
        (mf/use-fn
         (mf/deps on-click)
         #(when on-click (on-click)))]

    [:g {:class (stl/css :grid-plus-button)
         :on-click handle-click}

     [:rect {:class (stl/css :grid-plus-shape)
             :x (+ rect-x (/ 6 zoom))
             :y (+ rect-y (/ 6 zoom))
             :width (/ (- 40 12) zoom)
             :height (/ (- 40 12) zoom)
             :rx (/ 4 zoom)
             :ry (/ 4 zoom)}]

     [:use {:class (stl/css :grid-plus-icon)
            :x icon-x
            :y icon-y
            :width (/ 22 zoom)
            :height (/ 22 zoom)
            :href "#icon-add"}]]))

(defn use-drag
  [{:keys [on-drag-start on-drag-end on-drag-delta on-drag-position]}]
  (let [dragging-ref    (mf/use-ref false)
        start-pos-ref   (mf/use-ref nil)
        current-pos-ref (mf/use-ref nil)

        handle-pointer-down
        (mf/use-fn
         (mf/deps on-drag-start)
         (fn [event]
           (let [raw-pt (dom/get-client-position event)
                 position (uwvv/point->viewport raw-pt)]
             (dom/capture-pointer event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-pos-ref raw-pt)
             (mf/set-ref-val! current-pos-ref raw-pt)
             (when on-drag-start (on-drag-start event position)))))

        handle-lost-pointer-capture
        (mf/use-fn
         (mf/deps on-drag-end)
         (fn [event]
           (let [raw-pt (mf/ref-val current-pos-ref)
                 position (uwvv/point->viewport raw-pt)]
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-pos-ref nil)
             (when on-drag-end (on-drag-end event position)))))

        handle-pointer-move
        (mf/use-fn
         (mf/deps on-drag-delta on-drag-position)
         (fn [event]
           (when (mf/ref-val dragging-ref)
             (let [start (mf/ref-val start-pos-ref)
                   pos   (dom/get-client-position event)
                   pt (uwvv/point->viewport pos)]
               (mf/set-ref-val! current-pos-ref pos)
               (when on-drag-delta (on-drag-delta event (gpt/to-vec start pos)))
               (when on-drag-position (on-drag-position event pt))))))]

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

        objects (mf/deref refs/workspace-page-objects)
        {cell-id :id} (unchecked-get props "cell")
        {:keys [row column row-span column-span]} (get-in shape [:layout-grid-cells cell-id])

        direction (unchecked-get props "direction")
        layout-data (unchecked-get props "layout-data")

        handle-drag-position
        (mf/use-fn
         (mf/deps shape row column row-span column-span)
         (fn [_ position]
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
                     (ctl/assign-cells objects))

                 modifiers
                 (-> (ctm/empty)
                     (ctm/change-property :layout-grid-rows (:layout-grid-rows shape))
                     (ctm/change-property :layout-grid-columns (:layout-grid-columns shape))
                     (ctm/change-property :layout-grid-cells (:layout-grid-cells shape)))]
             (st/emit! (dwm/set-modifiers (dwm/create-modif-tree [(:id shape)] modifiers))))))

        handle-drag-end
        (mf/use-fn
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
             :style {:fill "var(--grid-editor-area-background)"
                     :fill-opacity 0.3}}]
     [:text {:x area-text-x
             :y area-text-y
             :style {:fill "var(--grid-editor-area-text)"
                     :font-family "worksans"
                     :font-weight 600
                     :font-size (/ 14 zoom)
                     :alignment-baseline "central"
                     :text-anchor "middle"}}
      text]]))

(mf/defc grid-cell
  {::mf/memo #{:shape :cell :layout-data :zoom :hover? :selected?}
   ::mf/props :obj}
  [{:keys [shape cell layout-data zoom hover? selected?]}]
  (let [cell-bounds (gsg/cell-bounds layout-data cell)
        cell-origin (gpo/origin cell-bounds)
        cell-width  (gpo/width-points cell-bounds)
        cell-height (gpo/height-points cell-bounds)
        cell-center (gsh/points->center cell-bounds)
        cell-origin (gpt/transform cell-origin (gmt/transform-in cell-center (:transform-inverse shape)))

        handle-pointer-enter
        (mf/use-fn
         (mf/deps (:id shape) (:id cell))
         (fn []
           (st/emit! (dwge/hover-grid-cell (:id shape) (:id cell) true))))

        handle-pointer-leave
        (mf/use-fn
         (mf/deps (:id shape) (:id cell))
         (fn []
           (st/emit! (dwge/hover-grid-cell (:id shape) (:id cell) false))))

        handle-pointer-down
        (mf/use-fn
         (mf/deps (:id shape) (:id cell) selected?)
         (fn [event]
           (when (dom/left-mouse? event)
             (cond
               (and selected? (or (kbd/mod? event) (kbd/shift? event)))
               (st/emit! (dwge/remove-selection (:id shape) (:id cell)))

               (and (not selected?) (kbd/mod? event))
               (st/emit! (dwge/add-to-selection (:id shape) (:id cell)))

               (and (not selected?) (kbd/shift? event))
               (st/emit! (dwge/add-to-selection (:id shape) (:id cell) true))

               :else
               (st/emit! (dwge/set-selection (:id shape) (:id cell)))))))

        handle-context-menu
        (mf/use-fn
         (mf/deps (:id shape) (:id cell) selected?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (let [position (dom/get-client-position event)]
             (if selected?
               (st/emit! (dw/show-grid-cell-context-menu {:position position :grid-id (:id shape)}))

               ;; If right-click on a non-selected cell we select the cell and then open the menu
               (st/emit! (dwge/set-selection (:id shape) (:id cell))
                         (dw/show-grid-cell-context-menu {:position position :grid-id (:id shape)}))))))]

    [:g.cell-editor
     ;; DEBUG OVERLAY
     (when (dbg/enabled? :grid-cells)
       [:g.debug-cell {:pointer-events "none"
                       :transform (dm/str (gmt/transform-in cell-center (:transform shape)))}

        [:rect
         {:x (:x cell-origin)
          :y (:y cell-origin)
          :width cell-width
          :height cell-height
          :fill (cond
                  (= (:position cell) :auto) "green"
                  (= (:position cell) :manual) "red"
                  (= (:position cell) :area) "yellow"
                  :else "black")
          :fill-opacity 0.2}]

        (when (seq (:shapes cell))
          [:circle
           {:cx (+ (:x cell-origin) cell-width (- (/ 7 zoom)))
            :cy (+ (:y cell-origin) (/ 7 zoom))
            :r (/ 5 zoom)
            :fill "red"}])])
     [:rect
      {:transform (dm/str (gmt/transform-in cell-center (:transform shape)))
       :class (dom/classnames (stl/css :grid-cell-outline) true
                              (stl/css :hover) hover?
                              (stl/css :selected) selected?)
       :x (:x cell-origin)
       :y (:y cell-origin)
       :width cell-width
       :height cell-height

       :on-context-menu handle-context-menu
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
        (mf/use-fn
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
        (mf/use-fn
         (mf/deps shape track-before track-after)
         (fn [_ position]
           (let [[tracks-prop axis]
                 (if (= :column type) [:layout-grid-columns :x] [:layout-grid-rows :y])

                 precision (if snap-pixel? mth/round identity)
                 delta (/ (get position axis) zoom)

                 new-size-before (max 0 (precision (+ @start-size-before delta)))
                 new-size-after  (max 0 (precision (- @start-size-after delta)))

                 shape
                 (-> shape
                     (cond-> (some? track-before)
                       (update-in [tracks-prop (dec index)] merge {:type :fixed :value new-size-before}))
                     (cond-> (some? track-after)
                       (update-in [tracks-prop index] merge {:type :fixed :value new-size-after})))

                 modifiers
                 (-> (ctm/empty)
                     (ctm/change-property tracks-prop (get shape tracks-prop)))]
             (st/emit! (dwm/set-modifiers (dwm/create-modif-tree [(:id shape)] modifiers))))))

        handle-drag-end
        (mf/use-fn
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
        drop? (unchecked-get props "drop?")
        track-before (unchecked-get props "track-before")
        track-after (unchecked-get props "track-after")
        snap-pixel? (unchecked-get props "snap-pixel?")

        {:keys [column-total-size column-total-gap row-total-size row-total-gap] :as layout-data}
        (unchecked-get props "layout-data")

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
          [(max 0 (- layout-gap-col (/ 10 zoom)) (/ 8 zoom))
           (+ row-total-size row-total-gap)]

          [(+ column-total-size column-total-gap)
           (max 0 (- layout-gap-row (/ 10 zoom)) (/ 8 zoom))])

        start-p-resize
        (cond-> start-p
          (and (= type :column) (= index 0))
          (gpt/subtract (hv (/ width 2)))

          (and (= type :row) (= index 0))
          (gpt/subtract (vv (/ height 2)))

          (and (= type :column) (not= index 0) (not last?))
          (-> (gpt/subtract (hv (/ layout-gap-col 2)))
              (gpt/subtract (hv (/ width 2))))

          (and (= type :row) (not= index 0) (not last?))
          (-> (gpt/subtract (vv (/ layout-gap-row 2)))
              (gpt/subtract (vv (/ height 2)))))

        start-p-drop
        (cond-> start-p
          (and (= type :column) (= index 0))
          (gpt/subtract (hv (/ width 2)))

          (and (= type :row) (= index 0))
          (gpt/subtract (vv (/ height 2)))

          (and (= type :column) last?)
          (gpt/add (hv (/ width 2)))

          (and (= type :row) last?)
          (gpt/add (vv (/ height 2)))

          (and (= type :column) (not= index 0) (not last?))
          (-> (gpt/subtract (hv (/ layout-gap-col 2)))
              (gpt/subtract (hv (/ 5 zoom))))

          (and (= type :row) (not= index 0) (not last?))
          (-> (gpt/subtract (vv (/ layout-gap-row 2)))
              (gpt/subtract (vv (/ 5 zoom)))))]
    [:*
     (when drop?
       [:rect.drop
        {:x (:x start-p-drop)
         :y (:y start-p-drop)
         :width (if (= type :column) (/ 10 zoom) width)
         :height (if (= type :row) (/ 10 zoom) height)
         :fill "var(--grid-editor-area-background)"}])

     [:rect.resize-track-handler
      {:x (:x start-p-resize)
       :y (:y start-p-resize)
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
               :stroke-width 0}}]]))

(def marker-width 24)
(def marker-h1 20)
(def marker-h2 10)
(def marker-bradius 2)

(defn marker-shape-d
  [center zoom]
  (let [marker-width (/ marker-width zoom)
        marker-h1 (/ marker-h1 zoom)
        marker-h2 (/ marker-h2 zoom)

        marker-bradius (/ marker-bradius zoom)
        marker-half-width (/ marker-width 2)
        marker-half-height (/ (+ marker-h1 marker-h2) 2)

        start-p
        (gpt/subtract center (gpt/point marker-half-width marker-half-height))

        [a b c d e]
        (reduce
         apply-to-point
         [start-p]
         [#(gpt/add % (gpt/point marker-width 0))
          #(gpt/add % (gpt/point 0 marker-h1))
          #(gpt/add % (gpt/point (- marker-half-width) marker-h2))
          #(gpt/subtract % (gpt/point marker-half-width marker-h2))])

        vea (gpt/to-vec e a)
        vab (gpt/to-vec a b)
        vbc (gpt/to-vec b c)
        vcd (gpt/to-vec c d)
        vde (gpt/to-vec d e)

        lea (gpt/length vea)
        lab (gpt/length vab)
        lbc (gpt/length vbc)
        lcd (gpt/length vcd)
        lde (gpt/length vde)

        a1 (gpt/add e (gpt/resize vea (- lea marker-bradius)))
        a2 (gpt/add a (gpt/resize vab marker-bradius))

        b1 (gpt/add a (gpt/resize vab (- lab marker-bradius)))
        b2 (gpt/add b (gpt/resize vbc marker-bradius))

        c1 (gpt/add b (gpt/resize vbc (- lbc marker-bradius)))
        c2 (gpt/add c (gpt/resize vcd marker-bradius))

        d1 (gpt/add c (gpt/resize vcd (- lcd marker-bradius)))
        d2 (gpt/add d (gpt/resize vde marker-bradius))

        e1 (gpt/add d (gpt/resize vde (- lde marker-bradius)))
        e2 (gpt/add e (gpt/resize vea marker-bradius))]
    (dm/str
     (dm/fmt "M%,%" (:x a1) (:y a1))
     (dm/fmt "Q%,%,%,%" (:x a) (:y a) (:x a2) (:y a2))

     (dm/fmt "L%,%" (:x b1) (:y b1))
     (dm/fmt "Q%,%,%,%" (:x b) (:y b) (:x b2) (:y b2))

     (dm/fmt "L%,%" (:x c1) (:y c1))
     (dm/fmt "Q%,%,%,%" (:x c) (:y c) (:x c2) (:y c2))

     (dm/fmt "L%,%" (:x d1) (:y d1))
     (dm/fmt "Q%,%,%,%" (:x d) (:y d) (:x d2) (:y d2))

     (dm/fmt "L%,%" (:x e1) (:y e1))
     (dm/fmt "Q%,%,%,%" (:x e) (:y e) (:x e2) (:y e2))

     (dm/fmt "L%,%" (:x a1) (:y a1))
     "Z")))

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

        text-x (:x center)
        text-y (:y center)

        {:keys [handle-pointer-down handle-lost-pointer-capture handle-pointer-move]}
        (use-resize-track type shape index track-before track-after zoom snap-pixel?)]

    [:g {:on-pointer-down handle-pointer-down
         :on-lost-pointer-capture handle-lost-pointer-capture
         :on-pointer-move handle-pointer-move
         :class (dom/classnames (stl/css :grid-track-marker) true
                                (cur/get-dynamic "resize-ew" (:rotation shape)) (= type :column)
                                (cur/get-dynamic "resize-ns" (:rotation shape)) (= type :row))
         :transform (dm/str (gmt/transform-in center (:transform shape)))}

     [:path {:class (stl/css :marker-shape)
             :d (marker-shape-d center zoom)}]
     [:text {:class (stl/css :marker-text)
             :x text-x
             :y text-y
             :width (/ 26.26 zoom)
             :height (/ 36 zoom)
             :text-anchor "middle"
             :dominant-baseline "middle"}
      (dm/str value)]]))

(mf/defc track
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [shape       (unchecked-get props "shape")
        zoom        (unchecked-get props "zoom")
        type        (unchecked-get props "type")
        index       (unchecked-get props "index")
        snap-pixel? (unchecked-get props "snap-pixel?")
        track-data  (unchecked-get props "track-data")
        layout-data (unchecked-get props "layout-data")
        hovering?   (unchecked-get props "hovering?")
        drop?       (unchecked-get props "drop?")

        on-start-reorder-track   (unchecked-get props "on-start-reorder-track")
        on-move-reorder-track   (unchecked-get props "on-move-reorder-track")
        on-end-reorder-track   (unchecked-get props "on-end-reorder-track")

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
        (mf/use-fn
         (mf/deps (:id shape))
         (fn [event]
           (let [target (-> event dom/get-target)
                 value  (-> target dom/get-input-value str/upper)
                 value-int (d/parse-integer value)
                 value-int (when value-int (max 0 value-int))

                 [track-type value]
                 (cond
                   (str/ends-with? value "%")
                   [:percent (d/nilv value-int 50)]

                   (str/ends-with? value "FR")
                   [:flex (d/nilv value-int 1)]

                   (some? value-int)
                   [:fixed (d/nilv value-int 100)]

                   :else
                   [:auto nil])

                 track-data (when (some? track-type) {:type track-type :value value})]

             (dom/set-value! (mf/ref-val track-input-ref) (format-size track-data))
             (if (some? track-type)
               (do (st/emit! (dwsl/change-layout-track [(:id shape)] type index track-data))
                   (dom/set-data! target "default-value" (format-size track-data)))
               (obj/set! target "value" (dom/get-attribute target "data-default-value"))))))

        handle-keydown-track-input
        (mf/use-fn
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)]
             (when enter?
               (dom/blur! (dom/get-target event)))
             (when esc?
               (dom/blur! (dom/get-target event))))))

        handle-pointer-enter
        (mf/use-fn
         (mf/deps (:id shape) type index)
         (fn []
           (st/emit! (dwsl/hover-layout-track [(:id shape)] type index true))))

        handle-pointer-leave
        (mf/use-fn
         (mf/deps (:id shape) type index)
         (fn []
           (st/emit! (dwsl/hover-layout-track [(:id shape)] type index false))))

        track-list-prop (if (= type :column) :column-tracks :row-tracks)
        [text-x text-y text-width text-height]
        (if (= type :column)
          [(:x text-p) (- (:y text-p) (/ 36 zoom)) (max 0 (:size track-data)) (/ 36 zoom)]
          [(- (:x text-p) (max 0 (:size track-data))) (- (:y text-p) (/ 36 zoom)) (max 0 (:size track-data)) (/ 36 zoom)])

        handle-drag-start
        (mf/use-fn
         (mf/deps on-start-reorder-track type index)
         (fn []
           (on-start-reorder-track type index)))

        handle-drag-end
        (mf/use-fn
         (mf/deps on-end-reorder-track type index)
         (fn [event position]
           (on-end-reorder-track type index position (not (kbd/mod? event)))))

        handle-drag-position
        (mf/use-fn
         (mf/deps on-move-reorder-track type index)
         (fn [_ position]
           (on-move-reorder-track type index position)))

        handle-show-track-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (let [position (cond-> (dom/get-client-position event)
                            (= type :column) (update :y + 40)
                            (= type :row)    (update :x + 30))]
             (st/emit! (dw/show-track-context-menu {:position position
                                                    :grid-id (:id shape)
                                                    :type type
                                                    :index index})))))

        trackwidth (* text-width zoom)
        medium?    (and (>= trackwidth small-size-limit) (< trackwidth medium-size-limit))
        small?     (< trackwidth small-size-limit)

        track-before (get-in layout-data [track-list-prop (dec index)])

        {:keys [handle-pointer-down handle-lost-pointer-capture handle-pointer-move]}
        (use-drag {:on-drag-start handle-drag-start
                   :on-drag-end handle-drag-end
                   :on-drag-position handle-drag-position})]

    (mf/use-effect
     (mf/deps track-data)
     (fn []
       (dom/set-value! (mf/ref-val track-input-ref) (format-size track-data))))


    [:g.track
     [:g {:on-pointer-enter handle-pointer-enter
          :on-pointer-leave handle-pointer-leave
          :transform (if (= type :column)
                       (dm/str (gmt/transform-in text-p (:transform shape)))
                       (dm/str (gmt/transform-in text-p (gmt/rotate (:transform shape) -90))))}

      [:rect {:class (stl/css :grid-editor-header-hover)
              :x (+ text-x (/ 18 zoom))
              :y text-y
              :width (- text-width (/ 36 zoom))
              :height (- text-height (/ 5 zoom))
              :rx (/ 3 zoom)
              :style {:cursor "pointer"}
              :opacity (if (and hovering? (not small?)) 0.2 0)}]
      (when (not small?)
        [:foreignObject {:x text-x :y text-y :width text-width :height text-height}
         [:div {:class (stl/css :grid-editor-wrapper)
                :on-context-menu handle-show-track-menu
                :on-pointer-down handle-pointer-down
                :on-lost-pointer-capture handle-lost-pointer-capture
                :on-pointer-move handle-pointer-move}
          [:input
           {:ref track-input-ref
            :style {}
            :class (stl/css :grid-editor-label)
            :type "text"
            :default-value (format-size track-data)
            :data-default-value (format-size track-data)
            :on-key-down handle-keydown-track-input
            :on-blur handle-blur-track-input}]
          (when (and hovering? (not medium?) (not small?))
            [:button {:class (stl/css :grid-editor-button)
                      :on-click handle-show-track-menu} i/menu])]])]

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
       :drop? drop?
       :start-p start-p
       :track-after track-data
       :track-before track-before
       :type type
       :zoom zoom}]]))

(mf/defc editor
  {::mf/memo true
   ::mf/props :obj}
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

        hover-columns
        (->> (:hover-track grid-edition)
             (filter (fn [[t _]] (= t :column)))
             (map (fn [[_ idx]] idx))
             (into #{}))

        hover-rows
        (->> (:hover-track grid-edition)
             (filter (fn [[t _]] (= t :row)))
             (map (fn [[_ idx]] idx))
             (into #{}))

        bounds (:points shape)
        hv     #(gpo/start-hv bounds %)
        vv     #(gpo/start-vv bounds %)
        origin (gpo/origin bounds)

        layout-data
        (mf/use-memo
         (mf/deps shape modifiers)
         (fn []
           (let [objects (gsh/apply-objects-modifiers objects modifiers)
                 ids     (cfh/get-children-ids objects (:id shape))
                 objects (gsh/update-shapes-geometry objects (reverse ids))

                 children
                 (->> (cfh/get-immediate-children objects (:id shape) {:remove-hidden true})
                      (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))

                 children-bounds (d/lazy-map ids #(gsh/shape->points (get objects %)))]
             (gsg/calc-layout-data shape bounds children children-bounds objects))))

        {:keys [row-tracks column-tracks column-total-size column-total-gap row-total-size row-total-gap]} layout-data

        width  (max (gpo/width-points bounds) (+ column-total-size column-total-gap (ctl/h-padding shape)))
        height (max (gpo/height-points bounds) (+ row-total-size row-total-gap (ctl/v-padding shape)))

        handle-pointer-down
        (mf/use-fn
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event))))

        handle-add-column
        (mf/use-fn
         (mf/deps (:id shape))
         (fn []
           (st/emit! (dwsl/add-layout-track [(:id shape)] :column ctl/default-track-value))))

        handle-add-row
        (mf/use-fn
         (mf/deps (:id shape))
         (fn []
           (st/emit! (dwsl/add-layout-track [(:id shape)] :row ctl/default-track-value))))

        target-tracks* (mf/use-ref nil)
        drop-track-type* (mf/use-state nil)
        drop-track-target* (mf/use-state nil)

        handle-start-reorder-track
        (mf/use-fn
         (mf/deps layout-data)
         (fn [type _from-idx]
           ;; Initialize target-tracks
           (let [line-vec (if (= type :column) (vv 1) (hv 1))

                 first-point origin
                 last-point (if (= type :column) (nth bounds 1) (nth bounds 3))
                 mid-points
                 (if (= type :column)
                   (->> (:column-tracks layout-data)
                        (mapv #(gpt/add (:start-p %) (hv (/ (:size %) 2)))))

                   (->> (:row-tracks layout-data)
                        (mapv #(gpt/add (:start-p %) (vv (/ (:size %) 2))))))

                 tracks
                 (->> (d/with-prev (d/concat-vec [first-point] mid-points [last-point]))
                      (d/enumerate)
                      (keep
                       (fn [[index [current prev]]]
                         (when (some? prev)
                           [[prev current line-vec] (dec index)]))))]

             (mf/set-ref-val! target-tracks* tracks)
             (reset! drop-track-type* type))))

        handle-move-reorder-track
        (mf/use-fn
         (fn [_type _from-idx position]
           (let [index
                 (->> (mf/ref-val target-tracks*)
                      (d/seek (fn [[[p1 p2 v] _]]
                                (gl/is-inside-lines? [p1 v] [p2 v] position)))
                      (second))]
             (when (some? index)
               (reset! drop-track-target* index)))))

        handle-end-reorder-track
        (mf/use-fn
         (mf/deps base-shape @drop-track-target*)
         (fn [type from-index _position move-content?]
           (when-let [to-index @drop-track-target*]
             (let [ids [(:id base-shape)]]
               (cond
                 (< from-index to-index)
                 (st/emit! (dwsl/reorder-layout-track ids type from-index (dec to-index) move-content?))

                 (> from-index to-index)
                 (st/emit! (dwsl/reorder-layout-track ids type from-index (dec to-index) move-content?)))))

           (mf/set-ref-val! target-tracks* nil)
           (reset! drop-track-type* nil)
           (reset! drop-track-target* nil)))]

    (mf/with-effect []
      #(st/emit! (dwge/stop-grid-layout-editing (:id shape))))

    (when (and (not (:hidden shape)) (not (:blocked shape)))
      [:g.grid-editor {:pointer-events (when view-only "none")
                       :on-pointer-down handle-pointer-down}
       [:g.cells
        (for [cell (ctl/get-cells shape {:sort? true})]
          [:& grid-cell {:key (dm/str "cell-" (:id cell))
                         :shape base-shape
                         :layout-data layout-data
                         :cell cell
                         :zoom zoom
                         :hover? (contains? hover-cells (:id cell))
                         :selected? (contains? selected-cells (:id cell))}])]

       (when-not ^boolean view-only
         [:*
          [:& grid-editor-frame {:zoom zoom
                                 :bounds bounds
                                 :width width
                                 :height height}]
          (let [start-p (-> origin (gpt/add (hv (+ width (/ 30 zoom)))))]
            [:g {:transform (dm/str (gmt/transform-in start-p (:transform shape)))}
             [:& plus-btn {:start-p start-p
                           :zoom zoom
                           :type :column
                           :on-click handle-add-column}]])

          (let [start-p (-> origin (gpt/add (vv (+ height (/ 30 zoom)))))]
            [:g {:transform (dm/str (gmt/transform-in start-p (:transform shape)))}
             [:& plus-btn {:start-p start-p
                           :zoom zoom
                           :type :row
                           :on-click handle-add-row}]])

          (for [[idx column-data] (d/enumerate column-tracks)]
            (let [drop? (and (= :column @drop-track-type*)
                             (= idx @drop-track-target*))]
              [:& track {:key (dm/str "column-track-" idx)
                         :shape shape
                         :zoom zoom
                         :type :column
                         :index idx
                         :layout-data layout-data
                         :snap-pixel? snap-pixel?
                         :drop? drop?
                         :track-data column-data
                         :hovering? (contains? hover-columns idx)
                         :on-start-reorder-track handle-start-reorder-track
                         :on-move-reorder-track handle-move-reorder-track
                         :on-end-reorder-track handle-end-reorder-track}]))

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
               (let [drop? (and (= :column @drop-track-type*)
                                (= (count column-tracks) @drop-track-target*))]
                 [:& resize-track-handler
                  {:index (count column-tracks)
                   :last? true
                   :drop? drop?
                   :shape shape
                   :layout-data layout-data
                   :snap-pixel? snap-pixel?
                   :start-p end-p
                   :type :column
                   :track-before (last column-tracks)
                   :zoom zoom}])]))

          (for [[idx row-data] (d/enumerate row-tracks)]
            (let [drop? (and (= :row @drop-track-type*)
                             (= idx @drop-track-target*))]
              [:& track {:index idx
                         :key (dm/str "row-track-" idx)
                         :layout-data layout-data
                         :shape shape
                         :snap-pixel? snap-pixel?
                         :drop? drop?
                         :track-data row-data
                         :type :row
                         :zoom zoom
                         :hovering? (contains? hover-rows idx)
                         :on-start-reorder-track handle-start-reorder-track
                         :on-move-reorder-track handle-move-reorder-track
                         :on-end-reorder-track handle-end-reorder-track}]))
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
               (let [drop? (and (= :row @drop-track-type*)
                                (= (count row-tracks) @drop-track-target*))]
                 [:& resize-track-handler
                  {:index (count row-tracks)
                   :last? true
                   :drop? drop?
                   :shape shape
                   :layout-data layout-data
                   :start-p end-p
                   :type :row
                   :track-before (last row-tracks)
                   :snap-pixel? snap-pixel?
                   :zoom zoom}])]))])])))
