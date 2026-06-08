;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.pixel-overlay
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.config :as cfg]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.main.rasterizer :as thr]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.v2.core :as rx]
   [goog.events :as events]
   [rumext.v2 :as mf]))

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

(def get-offscreen-canvas
  ((fn []
     (let [internal-state #js {:canvas nil}]
       (fn [width height]
         (let [canvas (unchecked-get internal-state "canvas")]
           (if canvas
             (resize-offscreen-canvas canvas width height)
             (let [new-canvas (create-offscreen-canvas width height)]
               (obj/set! internal-state "canvas" new-canvas)
               new-canvas))))))))

(defn process-pointer-move
  [viewport-node canvas canvas-image-data zoom-view-context last-picked-color client-x client-y]
  (when-let [image-data (mf/ref-val canvas-image-data)]
    (when-let [zoom-view-node (dom/get-element "picker-detail")]
      (when-not (mf/ref-val zoom-view-context)
        (mf/set-ref-val! zoom-view-context (.getContext zoom-view-node "2d")))
      (let [canvas-width  260
            canvas-height 140
            {brx :left bry :top} (dom/get-bounding-rect viewport-node)

            x (mth/floor (- client-x brx))
            y (mth/floor (- client-y bry))

            img-width  (unchecked-get image-data "width")
            img-height (unchecked-get image-data "height")

            zoom-context (mf/ref-val zoom-view-context)

            sx (- x 32)
            sy (if (cfg/check-browser? :safari) y (- y 17))
            sw 65
            sh 35
            dx 0
            dy 0
            dw canvas-width
            dh canvas-height]

        (when (obj/get zoom-context "imageSmoothingEnabled")
          (obj/set! zoom-context "imageSmoothingEnabled" false))
        (.clearRect zoom-context 0 0 canvas-width canvas-height)
        (.drawImage zoom-context canvas sx sy sw sh dx dy dw dh)

        ;; Only pick color when cursor is within canvas bounds to avoid garbage pixels
        (when (and (>= x 0) (< x img-width) (>= y 0) (< y img-height))
          (let [offset (* (+ (* y img-width) x) 4)
                rgba   (unchecked-get image-data "data")
                r      (d/check-num (obj/get rgba (+ 0 offset)) 255)
                g      (d/check-num (obj/get rgba (+ 1 offset)) 255)
                b      (d/check-num (obj/get rgba (+ 2 offset)) 255)
                a      (d/check-num (obj/get rgba (+ 3 offset)) 255)
                color  [r g b a]]
            ;; Store latest color synchronously so the click handler always reads
            ;; the correct pixel even before the rAF fires (fixes race condition)
            (mf/set-ref-val! last-picked-color color)
            (timers/raf
             (fn []
               (st/emit! (dwc/pick-color color))))))))))


(mf/defc pixel-overlay
  {::mf/wrap-props false}
  [props]
  (let [vport             (unchecked-get props "vport")

        viewport-ref      (unchecked-get props "viewport-ref")
        viewport-node     (mf/ref-val viewport-ref)

        canvas            (get-offscreen-canvas (:width vport) (:height vport))
        canvas-context    (.getContext canvas "2d" #js {:willReadFrequently true})
        canvas-image-data (mf/use-ref nil)
        zoom-view-context (mf/use-ref nil)
        ;; Holds the last successfully picked [r g b a] synchronously so that
        ;; the pointer-down handler always has the current pixel, regardless of
        ;; whether the rAF-deferred store update has fired yet.
        last-picked-color (mf/use-ref nil)
        ;; Use a ref (not state) so tracking the cursor doesn't cause re-renders.
        ;; Updated by both on-mouse-enter and a document-level pointermove listener
        ;; so that the position is always current when the canvas first becomes ready.
        initial-mouse-pos (mf/use-ref {:x 0 :y 0})
        update-str        (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (dwc/stop-picker))
             (modal/disallow-click-outside!))))

        handle-pointer-down-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           ;; Emit pick-color synchronously with the latest pixel colour before
           ;; pick-color-select, so the colorpicker effect never sees a stale value.
           (let [color (mf/ref-val last-picked-color)]
             (if (some? color)
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color color)
                         (dwc/pick-color-select true (kbd/shift? event)))
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color-select true (kbd/shift? event)))))))

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
                            (assoc result :styles styles)))
                  (rx/mapcat thr/render-node)
                  (rx/subs! (fn [image-bitmap]
                              (.drawImage canvas-context image-bitmap 0 0)
                              (let [width (unchecked-get canvas "width")
                                    height (unchecked-get canvas "height")
                                    image-data (.getImageData canvas-context 0 0 width height)
                                    ;; Read current mouse position from ref so the zoom
                                    ;; is populated immediately even without a mouse-move.
                                    {mx :x my :y} (mf/ref-val initial-mouse-pos)]
                                (mf/set-ref-val! canvas-image-data image-data)
                                (process-pointer-move viewport-node canvas canvas-image-data
                                                      zoom-view-context last-picked-color
                                                      mx my))))))))

        handle-svg-change
        (mf/use-callback
         (fn []
           (rx/push! update-str :update)))

        handle-mouse-enter
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (mf/set-ref-val! initial-mouse-pos
                            {:x (.-clientX event)
                             :y (.-clientY event)})))

        handle-pointer-move-picker
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (process-pointer-move viewport-node canvas canvas-image-data zoom-view-context
                                 last-picked-color (.-clientX event) (.-clientY event))))]

    (when (obj/get canvas-context "imageSmoothingEnabled")
      (obj/set! canvas-context "imageSmoothingEnabled" false))

    ;; Move focus to the overlay div on mount so the eyedropper button loses
    ;; :focus styling immediately.  Without this, prevent-default on pointer-down
    ;; keeps focus on the button and it looks "selected" even after picking.
    (mf/use-effect
     (fn []
       (when-let [node (dom/get-element "pixel-overlay")]
         (.focus node))))

    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "keydown" handle-keydown)]
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

    ;; Track the cursor position at document level so initial-mouse-pos is always
    ;; current when the canvas first becomes ready — even when the picker is opened
    ;; via the "i" shortcut and the cursor hasn't entered/moved over the overlay yet.
    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "pointermove"
                                     (fn [e]
                                       (mf/set-ref-val! initial-mouse-pos
                                                        {:x (.-clientX e)
                                                         :y (.-clientY e)})))]
         #(events/unlistenByKey listener))))

    [:div {:id "pixel-overlay"
           :tab-index 0
           :class (dm/str (cur/get-static "picker") " " (stl/css :pixel-overlay))
           :on-pointer-down handle-pointer-down-picker
           :on-pointer-up handle-pointer-up-picker
           :on-pointer-move handle-pointer-move-picker
           :on-mouse-enter handle-mouse-enter}]))


(defn process-pointer-move-wasm
  [viewport-node canvas canvas-image-data zoom-view-context last-picked-color client-x client-y]
  (when-let [image-data (mf/ref-val canvas-image-data)]
    (when-let [zoom-view-node (dom/get-element "picker-detail")]
      (when-not (mf/ref-val zoom-view-context)
        (mf/set-ref-val! zoom-view-context (.getContext zoom-view-node "2d")))
      (let [zoom-view-width  260
            zoom-view-height 140
            {brx :left bry :top} (dom/get-bounding-rect viewport-node)
            x (mth/floor (- client-x brx))
            y (mth/floor (- client-y bry))

            canvas-x (* x wasm.api/dpr)
            canvas-y (* y wasm.api/dpr)

            img-width  (.-width image-data)
            img-height (.-height image-data)

            zoom-context (mf/ref-val zoom-view-context)

            sx (- canvas-x 32)
            sy (if (cfg/check-browser? :safari) canvas-y (- canvas-y 17))
            sw 65
            sh 35]

        (when (obj/get zoom-context "imageSmoothingEnabled")
          (obj/set! zoom-context "imageSmoothingEnabled" false))
        (.clearRect zoom-context 0 0 zoom-view-width zoom-view-height)
        (.drawImage zoom-context canvas sx sy sw sh 0 0 zoom-view-width zoom-view-height)

        ;; Only pick color when cursor is within canvas bounds to avoid garbage pixels
        (when (and (>= canvas-x 0) (< canvas-x img-width) (>= canvas-y 0) (< canvas-y img-height))
          (let [;; image-data pixels start from the bottom-left corner; invert y accordingly
                inverted-y (- img-height canvas-y 1)
                offset     (* (+ (* inverted-y img-width) canvas-x) 4)
                rgba       (.-data image-data)
                r          (d/check-num (obj/get rgba (+ 0 offset)) 255)
                g          (d/check-num (obj/get rgba (+ 1 offset)) 255)
                b          (d/check-num (obj/get rgba (+ 2 offset)) 255)
                a          (d/check-num (obj/get rgba (+ 3 offset)) 255)
                color      [r g b a]]
            ;; Store latest color synchronously so the click handler always reads
            ;; the correct pixel even before the rAF fires (fixes race condition)
            (mf/set-ref-val! last-picked-color color)
            ;; rAF throttles state updates to avoid an infinite React re-render loop
            (timers/raf
             (fn []
               (st/emit! (dwc/pick-color color))))))))))

(mf/defc pixel-overlay-wasm*
  {::mf/wrap-props false}
  [{:keys [viewport-ref canvas-ref]}]
  (let [viewport-node     (mf/ref-val viewport-ref)
        canvas            (mf/ref-val canvas-ref)
        canvas-context    (mf/use-ref nil)
        canvas-image-data (mf/use-ref nil)
        zoom-view-context (mf/use-ref nil)
        ;; Holds the last successfully picked [r g b a] synchronously so that
        ;; the pointer-down handler always has the current pixel, regardless of
        ;; whether the rAF-deferred store update has fired yet.
        last-picked-color (mf/use-ref nil)
        ;; Use a ref (not state) so tracking the cursor doesn't cause re-renders.
        ;; Updated by both on-mouse-enter and a document-level pointermove listener
        ;; so that the position is always current when the canvas first becomes ready.
        initial-mouse-pos (mf/use-ref {:x 0 :y 0})
        update-str        (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (dwc/stop-picker))
             (modal/disallow-click-outside!))))

        handle-pointer-down-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           ;; Emit pick-color synchronously with the latest pixel colour before
           ;; pick-color-select, so the colorpicker effect never sees a stale value.
           (let [color (mf/ref-val last-picked-color)]
             (if (some? color)
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color color)
                         (dwc/pick-color-select true (kbd/shift? event)))
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color-select true (kbd/shift? event)))))))

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
         (mf/deps canvas-context)
         (fn []
           (when-let [canvas-context (mf/ref-val canvas-context)]
             (let [width  (.-width canvas)
                   height (.-height canvas)
                   buffer (js/Uint8ClampedArray. (* width height 4))
                   _      (.readPixels canvas-context 0 0 width height (.-RGBA canvas-context) (.-UNSIGNED_BYTE canvas-context) buffer)
                   image-data (js/ImageData. buffer width height)
                   ;; Read current mouse position from ref so the zoom
                   ;; is populated immediately even without a mouse-move.
                   {mx :x my :y} (mf/ref-val initial-mouse-pos)]
               (mf/set-ref-val! canvas-image-data image-data)
               (process-pointer-move-wasm viewport-node canvas canvas-image-data
                                          zoom-view-context last-picked-color
                                          mx my)))))

        handle-canvas-changed
        (mf/use-callback
         (fn [_]
           (rx/push! update-str :update)))

        handle-mouse-enter
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (mf/set-ref-val! initial-mouse-pos
                            {:x (.-clientX event)
                             :y (.-clientY event)})))

        handle-pointer-move-picker
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (process-pointer-move-wasm viewport-node canvas canvas-image-data zoom-view-context last-picked-color (.-clientX event) (.-clientY event))))]

    (mf/use-effect
     (mf/deps canvas)
     (fn []
       (let [context (.getContext canvas "webgl2" #js {:willReadFrequently true :preserveDrawingBuffer true})]
         (mf/set-ref-val! canvas-context context))))

    ;; Move focus to the overlay div on mount so the eyedropper button loses
    ;; :focus styling immediately.  Without this, prevent-default on pointer-down
    ;; keeps focus on the button and it looks "selected" even after picking.
    (mf/use-effect
     (fn []
       (when-let [node (dom/get-element "pixel-overlay")]
         (.focus node))))

    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "keydown" handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs! handle-draw-picker-canvas))]
         #(rx/dispose! sub))))

    (mf/with-effect []
      (handle-canvas-changed)
      (.addEventListener ug/document "penpot:wasm:render" handle-canvas-changed)
      (fn []
        (.removeEventListener ug/document "penpot:wasm:render" handle-canvas-changed)))

    ;; Track the cursor position at document level so initial-mouse-pos is always
    ;; current when the canvas first becomes ready — even when the picker is opened
    ;; via the "i" shortcut and the cursor hasn't entered/moved over the overlay yet.
    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "pointermove"
                                     (fn [e]
                                       (mf/set-ref-val! initial-mouse-pos
                                                        {:x (.-clientX e)
                                                         :y (.-clientY e)})))]
         #(events/unlistenByKey listener))))

    [:div {:id "pixel-overlay"
           :tab-index 0
           :class (dm/str (cur/get-static "picker") " " (stl/css :pixel-overlay))
           :on-pointer-down handle-pointer-down-picker
           :on-pointer-up handle-pointer-up-picker
           :on-pointer-move handle-pointer-move-picker
           :on-mouse-enter handle-mouse-enter}]))
