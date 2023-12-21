;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.pixel-overlay
  (:require
   [app.common.math :as mth]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.main.rasterizer :as thr]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.css-cursors :as cur]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn create-offscreen-canvas
  [width height]
  (js/OffscreenCanvas. width height))

(defn resize-offscreen-canvas
  [canvas width height]
  (let [resized (volatile! false)]
    (when-not (= (unchecked-get canvas "width") width)
      (obj/set! canvas "width" width)
      (vreset! resized true))
    (when-not (= (unchecked-get canvas "height") height)
      (obj/set! canvas "height" height)
      (vreset! resized true))
    canvas))

(def get-offscreen-canvas ((fn []
  (let [internal-state #js { :canvas nil }]
    (fn [width height]
      (let [canvas (unchecked-get internal-state "canvas")]
        (if canvas
          (resize-offscreen-canvas canvas width height)
          (let [new-canvas (create-offscreen-canvas width height)]
            (obj/set! internal-state "canvas" new-canvas)
            new-canvas))))))))

(mf/defc pixel-overlay
  {::mf/wrap-props false}
  [props]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        vport          (unchecked-get props "vport")

        viewport-ref   (unchecked-get props "viewport-ref")
        viewport-node  (mf/ref-val viewport-ref)

        canvas            (get-offscreen-canvas (:width vport) (:height vport))
        canvas-context    (.getContext canvas "2d" #js {:willReadFrequently true})
        canvas-image-data (mf/use-ref nil)
        zoom-view-context (mf/use-ref nil)

        update-str        (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (dwc/stop-picker))
             (modal/disallow-click-outside!))))

        handle-pointer-move-picker
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (when-let [image-data (mf/ref-val canvas-image-data)]
             (when-let [zoom-view-node (dom/get-element "picker-detail")]
               (when-not (mf/ref-val zoom-view-context)
                 (mf/set-ref-val! zoom-view-context (.getContext zoom-view-node "2d")))
               (let [canvas-width (if new-css-system 260 200)
                     canvas-height (if new-css-system 140 160)
                     {brx :left bry :top} (dom/get-bounding-rect viewport-node)

                     x (mth/floor (- (.-clientX event) brx))
                     y (mth/floor (- (.-clientY event) bry))

                     zoom-context (mf/ref-val zoom-view-context)

                     offset (* (+ (* y (unchecked-get image-data "width")) x) 4)
                     rgba (unchecked-get image-data "data")

                     r (obj/get rgba (+ 0 offset))
                     g (obj/get rgba (+ 1 offset))
                     b (obj/get rgba (+ 2 offset))
                     a (obj/get rgba (+ 3 offset))

                     ;; I don't know why, but the zoom view is offset by 24px
                     ;; instead of 25.
                     sx (- x (if new-css-system 32 24))
                     sy (- y (if new-css-system 17 20))
                     sw (if new-css-system 65 50)
                     sh (if new-css-system 35 40)
                     dx 0
                     dy 0
                     dw canvas-width
                     dh canvas-height]
                 (when (obj/get zoom-context "imageSmoothingEnabled")
                   (obj/set! zoom-context "imageSmoothingEnabled" false))
                 (.clearRect zoom-context 0 0 canvas-width canvas-height)
                 (.drawImage zoom-context canvas sx sy sw sh dx dy dw dh)
                 (st/emit! (dwc/pick-color [r g b a])))))))

        handle-pointer-down-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                     (dwc/pick-color-select true (kbd/shift? event)))))

        handle-pointer-up-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwu/commit-undo-transaction :mouse-down-picker)
                     (dwc/stop-picker))
           (modal/disallow-click-outside!)))

        handle-draw-picker-canvas
        (mf/use-callback
         (fn []
           (let [svg-node (dom/get-element "render")
                 fonts    (fonts/get-node-fonts svg-node)
                 result {:node svg-node
                         :width (:width vport)
                         :result "image-bitmap"}]
             (->> (fonts/render-font-styles-cached fonts)
                  (rx/map (fn [styles]
                            (assoc result
                                   :styles styles)))
                  (rx/mapcat thr/render-node)
                  (rx/subs! (fn [image-bitmap]
                             (.drawImage canvas-context image-bitmap 0 0)
                             (let [width (unchecked-get canvas "width")
                                   height (unchecked-get canvas "height")
                                   image-data (.getImageData canvas-context 0 0 width height)]
                               (mf/set-ref-val! canvas-image-data image-data))))))))

        handle-svg-change
        (mf/use-callback
         (fn []
           (rx/push! update-str :update)))]

    (when (obj/get canvas-context "imageSmoothingEnabled")
      (obj/set! canvas-context "imageSmoothingEnabled" false))

    (mf/use-effect
     (fn []
       (let [listener (events/listen js/document EventType.KEYDOWN  handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs! handle-draw-picker-canvas))]
         #(rx/dispose! sub))))

    (mf/use-effect
     (fn []
       (let [config #js {:attributes true
                         :childList true
                         :subtree true
                         :characterData true}
             svg-node (dom/get-element "render")
             observer (js/MutationObserver. handle-svg-change)]
         (.observe observer svg-node config)
         (handle-svg-change)

         ;; Disconnect on unmount
         #(.disconnect observer))))

    [:*
     [:div.pixel-overlay
      {:id "pixel-overlay"
       :tab-index 0
       :class (cur/get-static "picker")
       :on-pointer-down handle-pointer-down-picker
       :on-pointer-up handle-pointer-up-picker
       :on-pointer-move handle-pointer-move-picker}]]))
