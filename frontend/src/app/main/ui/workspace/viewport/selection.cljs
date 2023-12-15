;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.selection
  "Selection handlers component."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor]]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [rumext.v2 :as mf]
   [rumext.v2.util :refer [map->obj]]))

(def rotation-handler-size 20)
(def resize-point-radius 4)
(def resize-point-circle-radius 10)
(def resize-point-rect-size 8)
(def resize-side-height 8)
(def selection-rect-color-normal "var(--color-select)")
(def selection-rect-color-component "var(--color-component-highlight)")
(def selection-rect-width 1)
(def min-selrect-side 10)
(def small-selrect-side 30)

(mf/defc selection-rect
  [{:keys [transform rect zoom color on-move-selected on-context-menu]}]
  (when rect
    (let [{:keys [x y width height]} rect]
      [:rect.main.viewport-selrect
       {:x x
        :y y
        :width width
        :height height
        :transform (str transform)
        :on-pointer-down on-move-selected
        :on-context-menu on-context-menu
        :style {:stroke color
                :stroke-width (/ selection-rect-width zoom)
                :fill "none"}}])))

(defn- handlers-for-selection [{:keys [x y width height]} {:keys [type]} zoom]
  (let [threshold-small (/ 25 zoom)
        threshold-tiny (/ 10 zoom)

        small-width? (<= width threshold-small)
        tiny-width?  (<= width threshold-tiny)

        small-height? (<= height threshold-small)
        tiny-height?  (<= height threshold-tiny)

        vertical-line? (and (= type :path) tiny-width?)
        horizontal-line? (and (= type :path) tiny-height?)

        align (if (or small-width? small-height?)
                :outside
                :inside)]
    (->>
     [ ;; TOP-LEFT
      {:type :rotation
       :position :top-left
       :props {:cx x :cy y}}

      {:type :rotation
       :position :top-right
       :props {:cx (+ x width) :cy y}}

      {:type :rotation
       :position :bottom-right
       :props {:cx (+ x width) :cy (+ y height)}}

      {:type :rotation
       :position :bottom-left
       :props {:cx x :cy (+ y height)}}

      (when-not horizontal-line?
        (let [x (if small-width? (+ x (/ (- width threshold-small) 2)) x)
              length (if small-width? threshold-small width)]
          {:type :resize-side
           :position :top
           :props {:x x
                   :y y
                   :length length
                   :angle 0
                   :align align
                   :show-handler? tiny-width?}}))

      (when-not horizontal-line?
        (let [x (if small-width? (+ x (/ (+ width threshold-small) 2)) (+ x width))
              length (if small-width? threshold-small width)]
          {:type :resize-side
           :position :bottom
           :props {:x x
                   :y (+ y height)
                   :length length
                   :angle 180
                   :align align
                   :show-handler? tiny-width?}}))

      (when-not vertical-line?
        (let [y (if small-height? (+ y (/ (- height threshold-small) 2)) y)
              length (if small-height? threshold-small height)]
          {:type :resize-side
           :position :right
           :props {:x (+ x width)
                   :y y
                   :length length
                   :angle 90
                   :align align
                   :show-handler? tiny-height?}}))

      (when-not vertical-line?
        (let [y (if small-height? (+ y (/ (+ height threshold-small) 2)) (+ y height))
              length (if small-height? threshold-small height)]
          {:type :resize-side
           :position :left
           :props {:x x
                   :y y
                   :length length
                   :angle 270
                   :align align
                   :show-handler? tiny-height?}}))

      (when (and (not tiny-width?) (not tiny-height?))
        {:type :resize-point
         :position :top-left
         :props {:cx x :cy y :align align}})

      (when (and (not tiny-width?) (not tiny-height?))
        {:type :resize-point
         :position :top-right
         :props {:cx (+ x width) :cy y :align align}})

      (when (and (not tiny-width?) (not tiny-height?))
        {:type :resize-point
         :position :bottom-right
         :props {:cx (+ x width) :cy (+ y height) :align align}})

      (when (and (not tiny-width?) (not tiny-height?))
        {:type :resize-point
         :position :bottom-left
         :props {:cx x :cy (+ y height) :align align}})]

     (filterv (comp not nil?)))))

(mf/defc rotation-handler [{:keys [cx cy transform position rotation zoom on-rotate]}]
  (let [size (/ rotation-handler-size zoom)
        x (- cx (if (#{:top-left :bottom-left} position) size 0))
        y (- cy (if (#{:top-left :top-right} position) size 0))
        angle (case position
                :top-left 0
                :top-right 90
                :bottom-right 180
                :bottom-left 270)]
    [:rect {:x x
            :y y
            :class (cur/get-dynamic "rotate" (+ rotation angle))
            :width size
            :height size
            :fill (if (dbg/enabled? :handlers) "blue" "none")
            :stroke-width 0
            :transform (dm/str transform)
            :on-pointer-down on-rotate}]))

(mf/defc resize-point-handler
  [{:keys [cx cy zoom position on-resize transform rotation color align]}]
  (let [layout (mf/deref refs/workspace-layout)
        scale-text (:scale-text layout)
        cursor (if (#{:top-left :bottom-right} position)
                 (if scale-text (cur/get-dynamic "scale-nesw" rotation) (cur/get-dynamic "resize-nesw" rotation))
                 (if scale-text (cur/get-dynamic "scale-nwse" rotation) (cur/get-dynamic "resize-nwse" rotation)))
        {cx' :x cy' :y} (gpt/transform (gpt/point cx cy) transform)]

    [:g.resize-handler
     [:circle {:r (/ resize-point-radius zoom)
               :style {:fillOpacity "1"
                       :strokeWidth "1px"
                       :vectorEffect "non-scaling-stroke"}
               :fill "var(--color-white)"
               :stroke color
               :cx cx'
               :cy cy'}]

     (if (= align :outside)
       (let [resize-point-circle-radius (/ resize-point-circle-radius zoom)
             offset-x (if (#{:top-right :bottom-right} position) 0 (- resize-point-circle-radius))
             offset-y (if (#{:bottom-left :bottom-right} position) 0 (- resize-point-circle-radius))
             cx (+ cx offset-x)
             cy (+ cy offset-y)
             {cx' :x cy' :y} (gpt/transform (gpt/point cx cy) transform)]
         [:rect {:x cx'
                 :y cy'
                 :class cursor
                 :width resize-point-circle-radius
                 :height resize-point-circle-radius
                 :transform (when rotation (dm/fmt "rotate(%, %, %)" rotation cx' cy'))
                 :style {:fill (if (dbg/enabled? :handlers) "red" "none")
                         :stroke-width 0}
                 :on-pointer-down #(on-resize {:x cx' :y cy'} %)}])

       [:circle {:on-pointer-down #(on-resize {:x cx' :y cy'} %)
                 :r (/ resize-point-circle-radius zoom)
                 :cx cx'
                 :cy cy'
                 :class cursor
                 :style {:fill (if (dbg/enabled? :handlers) "red" "none")
                         :stroke-width 0}}])]))

(mf/defc resize-side-handler
  "The side handler is always rendered horizontally and then rotated"
  [{:keys [x y length align angle zoom position rotation transform on-resize color show-handler?]}]
  (let [res-point (if (#{:top :bottom} position)
                    {:y y}
                    {:x x})
        layout (mf/deref refs/workspace-layout)
        scale-text (:scale-text layout)
        height (/ resize-side-height zoom)
        offset-y (if (= align :outside) (- height) (- (/ height 2)))
        target-y (+ y offset-y)
        transform-str (dm/str (gmt/multiply transform (gmt/rotate-matrix angle (gpt/point x y))))]
    [:g.resize-handler
     (when show-handler?
       [:circle {:r (/ resize-point-radius zoom)
                 :style {:fillOpacity 1
                         :stroke color
                         :strokeWidth "1px"
                         :fill "var(--color-white)"
                         :vectorEffect "non-scaling-stroke"}
                 :cx (+ x (/ length 2))
                 :cy y
                 :transform transform-str}])
     [:rect {:x x
             :y target-y
             :width length
             :height height
             :class (if (#{:left :right} position)
                      (if scale-text (cur/get-dynamic "scale-ew" rotation) (cur/get-dynamic "resize-ew" rotation))
                      (if scale-text (cur/get-dynamic "scale-ns" rotation) (cur/get-dynamic "resize-ns" rotation)))
             :transform transform-str
             :on-pointer-down #(on-resize res-point %)
             :style {:fill (if (dbg/enabled? :handlers) "yellow" "none")
                     :stroke-width 0}}]]))

(defn minimum-selrect [{:keys [x y width height] :as selrect}]
  (let [final-width (max width min-selrect-side)
        final-height (max height min-selrect-side)
        offset-x (/ (- final-width width) 2)
        offset-y (/ (- final-height height) 2)]
    {:x (- x offset-x)
     :y (- y offset-y)
     :width final-width
     :height final-height}))

(mf/defc controls-selection
  {::mf/wrap-props false}
  [props]
  (let [shape             (obj/get props "shape")
        zoom              (obj/get props "zoom")
        color             (obj/get props "color")
        on-move-selected  (obj/get props "on-move-selected")
        on-context-menu   (obj/get props "on-context-menu")
        disable-handlers  (obj/get props "disable-handlers")

        current-transform (mf/deref refs/current-transform)

        selrect (:selrect shape)
        transform (gsh/transform-str shape)]

    (when (and (not (:transforming shape))
               (not (#{:move :rotate} current-transform)))
      [:g.controls {:pointer-events (if disable-handlers "none" "visible")}
       ;; Selection rect
       [:& selection-rect {:rect selrect
                           :transform transform
                           :zoom zoom
                           :color color
                           :on-move-selected on-move-selected
                           :on-context-menu on-context-menu}]])))

(mf/defc controls-handlers
  {::mf/wrap-props false}
  [props]
  (let [shape                (obj/get props "shape")
        zoom                 (obj/get props "zoom")
        color                (obj/get props "color")
        on-resize            (obj/get props "on-resize")
        on-rotate            (obj/get props "on-rotate")
        disable-handlers     (obj/get props "disable-handlers")
        current-transform    (mf/deref refs/current-transform)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        selrect (:selrect shape)
        transform (gsh/transform-matrix shape)

        rotation (-> (gpt/point 1 0)
                     (gpt/transform (:transform shape))
                     (gpt/angle)
                     (mod 360))]

    (when (and (not (#{:move :rotate} current-transform))
               (not workspace-read-only?)
               (not (:transforming shape)))
      [:g.controls {:pointer-events (if disable-handlers "none" "visible")}
       ;; Handlers
       (for [{:keys [type position props]} (handlers-for-selection selrect shape zoom)]
         (let [rotation
               (cond
                 (and (#{:top-left :bottom-right} position)
                      (or (and (:flip-x shape) (not (:flip-y shape)))
                          (and (:flip-y shape) (not (:flip-x shape)))))
                 (- rotation 90)

                 (and (#{:top-right :bottom-left} position)
                      (or (and (:flip-x shape) (not (:flip-y shape)))
                          (and (:flip-y shape) (not (:flip-x shape)))))
                 (+ rotation 90)

                 :else
                 rotation)

               common-props {:key (dm/str (name type) "-" (name position))
                             :zoom zoom
                             :position position
                             :on-rotate on-rotate
                             :on-resize (partial on-resize position)
                             :transform transform
                             :rotation rotation
                             :color color}
               props (map->obj (merge common-props props))]
           (case type
             :rotation [:> rotation-handler props]
             :resize-point [:> resize-point-handler props]
             :resize-side [:> resize-side-handler props])))])))

;; --- Selection Handlers (Component)

(mf/defc text-edition-selection
  [{:keys [shape color zoom] :as props}]
  (let [{:keys [x y width height]} shape]
    [:g.controls
     [:rect.main {:x x :y y
                  :transform (gsh/transform-str shape)
                  :width width
                  :height height
                  :pointer-events "visible"
                  :style {:stroke color
                          :stroke-width (/ 0.5 zoom)
                          :stroke-opacity 1
                          :fill "none"}}]]))

(mf/defc multiple-handlers
  [{:keys [shapes selected zoom color disable-handlers] :as props}]
  (let [shape (mf/with-memo [shapes]
                (-> shapes
                    (gsh/shapes->rect)
                    (assoc :type :multiple)
                    (cts/setup-shape)))
        on-resize
        (fn [current-position _initial-position event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-resize current-position selected shape))))

        on-rotate
        (fn [event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-rotate shapes))))]

    [:& controls-handlers
     {:shape shape
      :zoom zoom
      :color color
      :disable-handlers disable-handlers
      :on-resize on-resize
      :on-rotate on-rotate}]))

(mf/defc multiple-selection
  [{:keys [shapes zoom color disable-handlers on-move-selected on-context-menu] :as props}]
  (let [shape (mf/with-memo [shapes]
                (-> shapes
                    (gsh/shapes->rect)
                    (assoc :type :multiple)
                    (cts/setup-shape)))]

    [:& controls-selection
     {:shape shape
      :zoom zoom
      :color color
      :disable-handlers disable-handlers
      :on-move-selected on-move-selected
      :on-context-menu on-context-menu}]))

(mf/defc single-handlers
  [{:keys [shape zoom color disable-handlers] :as props}]
  (let [shape-id (:id shape)

        on-resize
        (fn [current-position _initial-position event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-resize current-position #{shape-id} shape))))

        on-rotate
        (fn [event]
          (when (dom/left-mouse? event)
            (dom/stop-propagation event)
            (st/emit! (dw/start-rotate [shape]))))]

    [:& controls-handlers
     {:shape shape
      :zoom zoom
      :color color
      :disable-handlers disable-handlers
      :on-rotate on-rotate
      :on-resize on-resize}]))

(mf/defc single-selection
  [{:keys [shape zoom color disable-handlers on-move-selected on-context-menu] :as props}]
  [:& controls-selection
   {:shape shape
    :zoom zoom
    :color color
    :disable-handlers disable-handlers
    :on-move-selected on-move-selected
    :on-context-menu on-context-menu}])

(mf/defc selection-area
  {::mf/wrap [mf/memo]}
  [{:keys [shapes edition zoom disable-handlers on-move-selected on-context-menu] :as props}]
  (let [num (count shapes)
        {:keys [type] :as shape} (first shapes)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color (if (and (= num 1)
                       (ctn/in-any-component? objects shape))
                selection-rect-color-component
                selection-rect-color-normal)]
    (cond
      (zero? num)
      nil

      (> num 1)
      [:& multiple-selection
       {:shapes shapes
        :zoom zoom
        :color color
        :disable-handlers disable-handlers
        :on-move-selected on-move-selected
        :on-context-menu on-context-menu}]

      (and (= type :text) (= edition (:id shape)))
      [:& text-edition-selection
       {:shape shape
        :zoom zoom
        :color color}]

      (= edition (:id shape))
      nil

      :else
      [:& single-selection
       {:shape shape
        :zoom zoom
        :color color
        :disable-handlers disable-handlers
        :on-move-selected on-move-selected
        :on-context-menu on-context-menu}])))

(mf/defc selection-handlers
  {::mf/wrap [mf/memo]}
  [{:keys [shapes selected edition zoom disable-handlers] :as props}]
  (let [num (count shapes)
        {:keys [type] :as shape} (first shapes)

        ;; Note that we don't use mf/deref to avoid a repaint dependency here
        objects (deref refs/workspace-page-objects)

        color (if (and (= num 1)
                       (ctn/in-any-component? objects shape))
                selection-rect-color-component
                selection-rect-color-normal)]
    (cond
      (zero? num)
      nil

      (> num 1)
      [:& multiple-handlers
       {:shapes shapes
        :selected selected
        :zoom zoom
        :color color
        :disable-handlers disable-handlers}]

      (and (= type :text) (= edition (:id shape)))
      nil

      (= edition (:id shape))
      [:& path-editor
       {:zoom zoom
        :shape shape}]

      :else
      [:& single-handlers
       {:shape shape
        :zoom zoom
        :color color
        :disable-handlers disable-handlers}])))
