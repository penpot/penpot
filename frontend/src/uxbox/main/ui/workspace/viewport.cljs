;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.viewport
  (:require
   [clojure.set :as set]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [goog.events :as events]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.cursors :as cur]
   [uxbox.common.data :as d]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.workspace.drawing :as dd]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.hooks :as hooks]
   [uxbox.main.ui.workspace.shapes :refer [shape-wrapper frame-wrapper]]
   [uxbox.main.ui.workspace.shapes.interactions :refer [interactions]]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.selection :refer [selection-handlers]]
   [uxbox.main.ui.workspace.presence :as presence]
   [uxbox.main.ui.workspace.snap-points :refer [snap-points]]
   [uxbox.main.ui.workspace.snap-distances :refer [snap-distances]]
   [uxbox.main.ui.workspace.frame-grid :refer [frame-grid]]
   [uxbox.main.ui.workspace.shapes.outline :refer [outline]]
   [uxbox.common.math :as mth]
   [uxbox.util.dom :as dom]
   [uxbox.util.dom.dnd :as dnd]
   [uxbox.util.object :as obj]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.common.geom.point :as gpt]
   [uxbox.util.perf :as perf]
   [uxbox.common.uuid :as uuid])
  (:import goog.events.EventType))

;; --- Coordinates Widget

(mf/defc coordinates
  []
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/round))]
    [:ul.coordinates
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

(mf/defc cursor-tooltip
  [{:keys [zoom tooltip] :as props}]
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom)))
        pos-x (- (:x coords) 100)
        pos-y (+ (:y coords) 30)]
    [:g {:transform (str "translate(" pos-x "," pos-y ")")}
     [:foreignObject {:width 200 :height 100 :style {:text-align "center"}}
      [:span tooltip]]]))

;; --- Cursor tooltip

(defn- get-shape-tooltip
  "Return the shape tooltip text"
  [shape]
  (case (:type shape)
    :icon "Click to place the Icon"
    :image "Click to place the Image"
    :rect "Drag to draw a Box"
    :text "Drag to draw a Text Box"
    :path "Click to draw a Path"
    :circle "Drag to draw a Circle"
    nil))

;; --- Selection Rect

(mf/defc selection-rect
  {:wrap [mf/memo]}
  [{:keys [data] :as props}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :width (:width data)
      :height (:height data)}]))

;; --- Viewport Positioning

(defn- handle-viewport-positioning
  [viewport-ref]
  (let [node   (mf/ref-val viewport-ref)
        stoper (rx/filter #(= ::finish-positioning %) st/stream)

        stream (->> ms/mouse-position-delta
                    (rx/take-until stoper))]
    (st/emit! dw/start-pan)
    (rx/subscribe stream
                  (fn [delta]
                    (let [vbox (.. ^js node -viewBox -baseVal)
                          zoom (gpt/point @refs/selected-zoom)
                          delta (gpt/divide delta zoom)]
                      (st/emit! (dw/update-viewport-position
                                 {:x #(- % (:x delta))
                                  :y #(- % (:y delta))})))))))

;; --- Viewport

(declare remote-user-cursors)

(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [objects   (unchecked-get props "objects")
        selected  (or (unchecked-get props "selected") #{})
        hover     (or (unchecked-get props "hover") #{})
        outline?  (set/union selected hover)
        shapes    (->> (vals objects) (filter (comp outline? :id)))
        transform (mf/deref refs/current-transform)]
    (when (nil? transform)
      [:g.outlines
       (for [shape shapes]
         [:& outline {:key (str "outline-" (:id shape))
                      :shape (gsh/transform-shape shape)}])])))

(mf/defc frames
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [data     (mf/deref refs/workspace-data)
        hover    (unchecked-get props "hover")
        selected (unchecked-get props "selected")
        objects (:objects data)
        root    (get objects uuid/zero)
        shapes  (->> (:shapes root)
                     (map #(get objects %)))]
    [:*
     [:g.shapes
      (for [item shapes]
        (if (= (:type item) :frame)
          [:& frame-wrapper {:shape item
                             :key (:id item)
                             :objects objects}]
          [:& shape-wrapper {:shape item
                             :key (:id item)}]))]

     [:& shape-outlines {:objects objects
                         :selected selected
                         :hover hover}]]))

(mf/defc viewport
  [{:keys [page local layout] :as props}]
  (let [{:keys [drawing-tool
                options-mode
                zoom
                flags
                vport
                vbox
                edition
                tooltip
                selected
                panning]} local

        viewport-ref (mf/use-ref nil)
        last-position (mf/use-var nil)

        zoom (or zoom 1)

        on-mouse-down
        (mf/use-callback
         (mf/deps drawing-tool edition)
         (fn [event]
           (dom/stop-propagation event)
           (let [event (.-nativeEvent event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :down ctrl? shift? alt?))

             (cond
               (and (not edition) (= 1 (.-which event)))
               (if drawing-tool
                 (st/emit! (dd/start-drawing drawing-tool))
                 (st/emit! dw/handle-selection))

               (and (not edition)
                    (= 2 (.-which event)))
               (handle-viewport-positioning viewport-ref)))))

        on-context-menu
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (let [position (dom/get-client-position event)]
             (st/emit! (dw/show-context-menu {:position position})))))

        on-mouse-up
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (let [event (.-nativeEvent event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :up ctrl? shift? alt?))

             (when (= 2 (.-which event))
               (st/emit! dw/finish-pan
                         ::finish-positioning)))))

        on-pointer-down
        (mf/use-callback
          (fn [event]
           (let [target (dom/get-target event)]
             ; Capture mouse pointer to detect the movements even if cursor
             ; leaves the viewport or the browser itself
             ; https://developer.mozilla.org/en-US/docs/Web/API/Element/setPointerCapture
             (.setPointerCapture target (.-pointerId event)))))

        on-pointer-up
        (mf/use-callback
          (fn [event]
           (let [target (dom/get-target event)]
             ; Release pointer on mouse up
             (.releasePointerCapture target (.-pointerId event)))))

        on-click
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (let [ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :click ctrl? shift? alt?)))))

        on-double-click
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (let [ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :double-click ctrl? shift? alt?)))))

        on-key-down
        (mf/use-callback
         (fn [event]
           (let [bevent (.getBrowserEvent event)
                 key (.-keyCode event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)
                 target (dom/get-target event)]

             (when-not (.-repeat bevent)
               (st/emit! (ms/->KeyboardEvent :down key ctrl? shift? alt?))
               (when (and (kbd/space? event)
                          (not= "rich-text" (obj/get target "className")))
                 (handle-viewport-positioning viewport-ref))))))

        on-key-up
        (mf/use-callback
         (fn [event]
           (let [key (.-keyCode event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (kbd/space? event)
               (st/emit! dw/finish-pan ::finish-positioning))
             (st/emit! (ms/->KeyboardEvent :up key ctrl? shift? alt?)))))

        translate-point-to-viewport
        (fn [pt]
          (let [viewport (mf/ref-val viewport-ref)
                vbox  (.. ^js viewport -viewBox -baseVal)
                brect (.getBoundingClientRect viewport)
                brect (gpt/point (d/parse-integer (.-left brect))
                                 (d/parse-integer (.-top brect)))
                box   (gpt/point (.-x vbox)
                                 (.-y vbox))
                ]
            (-> (gpt/subtract pt brect)
                (gpt/divide (gpt/point @refs/selected-zoom))
                (gpt/add box)
                (gpt/round 0))))

        on-mouse-move
        (fn [event]
          (let [event (.getBrowserEvent event)
                pt (dom/get-client-position event)
                pt (translate-point-to-viewport pt)
                delta (gpt/point (.-movementX event)
                                 (.-movementY event))]
            (st/emit! (ms/->PointerEvent :delta delta
                                         (kbd/ctrl? event)
                                         (kbd/shift? event)
                                         (kbd/alt? event)))
            (st/emit! (ms/->PointerEvent :viewport pt
                                         (kbd/ctrl? event)
                                         (kbd/shift? event)
                                         (kbd/alt? event)))))

        on-mouse-wheel
        (mf/use-callback
         (fn [event]
           (let [node (mf/ref-val viewport-ref)
                 target (dom/get-target event)]
             (cond
               (kbd/ctrl? event)
               (let [event (.getBrowserEvent event)
                     pos   @ms/mouse-position]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (if (pos? (.-deltaY event))
                   (st/emit! (dw/decrease-zoom pos))
                   (st/emit! (dw/increase-zoom pos))))

               (.contains ^js node target)
               (let [event (.getBrowserEvent event)
                     delta (.-deltaY ^js event)
                     delta (/ delta @refs/selected-zoom)]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (if (kbd/shift? event)
                   (st/emit! (dw/update-viewport-position {:x #(+ % delta)}))
                   (st/emit! (dw/update-viewport-position {:y #(+ % delta)}))))))))

        on-drag-enter
        (fn [e]
          (when (or (dnd/has-type? e "uxbox/shape")
                    (dnd/has-type? e "Files"))
            (dom/prevent-default e)))

        on-drag-over
        (fn [e]
          (when (or (dnd/has-type? e "uxbox/shape")
                    (dnd/has-type? e "Files"))
            (dom/prevent-default e)))

        on-uploaded
        (fn [{:keys [id name] :as image}]
          (let [shape {:name name
                       :metadata {:width (:width image)
                                  :height (:height image)
                                  :uri (:uri image)
                                  :thumb-width (:thumb-width image)
                                  :thumb-height (:thumb-height image)
                                  :thumb-uri (:thumb-uri image)}}
                aspect-ratio (/ (:width image) (:height image))]
            (st/emit! (dw/create-and-add-shape :image shape aspect-ratio))))

        on-drop
        (fn [event]
          (dom/prevent-default event)
          (if (dnd/has-type? event "uxbox/shape")

            (let [shape (dnd/get-data event "uxbox/shape")
                  point (gpt/point (.-clientX event) (.-clientY event))
                  viewport-coord (translate-point-to-viewport point)
                  final-x (- (:x viewport-coord) (/ (:width shape) 2))
                  final-y (- (:y viewport-coord) (/ (:height shape) 2))]
              (st/emit! (dw/add-shape (-> shape
                                          (assoc :x final-x)
                                          (assoc :y final-y)))))

            (let [files (dnd/get-files event)]
              (run! #(st/emit! (dw/upload-image % on-uploaded)) files))))

        on-resize
        (fn [event]
          (let [node (mf/ref-val viewport-ref)
                prnt (dom/get-parent node)]
            (st/emit! (dw/update-viewport-size (dom/get-client-size prnt)))))

        options (mf/deref refs/workspace-page-options)]

    (mf/use-layout-effect
     (fn []
       (let [node (mf/ref-val viewport-ref)
             prnt (dom/get-parent node)

             key1 (events/listen js/document EventType.KEYDOWN on-key-down)
             key2 (events/listen js/document EventType.KEYUP on-key-up)
             key3 (events/listen node EventType.MOUSEMOVE on-mouse-move)
             ;; bind with passive=false to allow the event to be cancelled
             ;; https://stackoverflow.com/a/57582286/3219895
             key4 (events/listen js/window EventType.WHEEL on-mouse-wheel #js {:passive false})
             key5 (events/listen js/window EventType.RESIZE on-resize)]
         (st/emit! (dw/initialize-viewport (dom/get-client-size prnt)))
         (fn []
           (events/unlistenByKey key1)
           (events/unlistenByKey key2)
           (events/unlistenByKey key3)
           (events/unlistenByKey key4)
           (events/unlistenByKey key5)))))

    (mf/use-layout-effect (mf/deps layout) on-resize)

    [:svg.viewport
     {:preserveAspectRatio "xMidYMid meet"
      :width (:width vport 0)
      :height (:height vport 0)
      :view-box (str/join " " [(+ (:x vbox 0) (:left-offset vbox 0))
                               (:y vbox 0)
                               (:width vbox 0)
                               (:height vbox 0)])
      :ref viewport-ref
      :class (when drawing-tool "drawing")
      :style {:cursor (cond
                        panning cur/hand
                        (= drawing-tool :frame) cur/create-artboard
                        (= drawing-tool :rect) cur/create-rectangle
                        (= drawing-tool :circle) cur/create-ellipse
                        (= drawing-tool :path) cur/pen
                        (= drawing-tool :curve)cur/pencil
                        drawing-tool cur/create-shape
                        :else cur/pointer-inner)
              :background-color (get options :background "#E8E9EA")}
      :on-context-menu on-context-menu
      :on-click on-click
      :on-double-click on-double-click
      :on-mouse-down on-mouse-down
      :on-mouse-up on-mouse-up
      :on-pointer-down on-pointer-down
      :on-pointer-up on-pointer-up
      :on-drag-enter on-drag-enter
      :on-drag-over on-drag-over
      :on-drop on-drop}

     [:g
      [:& frames {:key (:id page)
                  :hover (:hover local)
                  :selected (:selected selected)}]

      (when (seq selected)
        [:& selection-handlers {:selected selected
                                :zoom zoom
                                :edition edition}])

      (when-let [drawing-shape (:drawing local)]
        [:& draw-area {:shape drawing-shape
                       :zoom zoom
                       :modifiers (:modifiers local)}])

      (when (contains? layout :display-grid)
        [:& frame-grid {:zoom zoom}])

      [:& snap-points {:layout layout
                       :transform (:transform local)
                       :drawing (:drawing local)
                       :zoom zoom
                       :page-id (:id page)
                       :selected selected}]

      [:& snap-distances {:layout layout}]

      (when tooltip
        [:& cursor-tooltip {:zoom zoom :tooltip tooltip}])]

     [:& presence/active-cursors {:page page}]
     [:& selection-rect {:data (:selrect local)}]
     (when (= options-mode :prototype)
       [:& interactions {:selected selected}])]))

