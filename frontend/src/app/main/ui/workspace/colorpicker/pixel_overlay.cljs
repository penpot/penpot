;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker.pixel-overlay
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [promesa.core :as p]
   [beicon.core :as rx]
   [goog.events :as events]
   [app.common.uuid :as uuid]
   [app.util.timers :as timers]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.main.data.colors :as dwc]
   [app.main.data.fetch :as mdf]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.cursors :as cur]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.workspace.shapes :refer [shape-wrapper frame-wrapper]])
  (:import goog.events.EventType))

(defn format-viewbox [vbox]
  (str/join " " [(+ (:x vbox 0) (:left-offset vbox 0))
                 (:y vbox 0)
                 (:width vbox 0)
                 (:height vbox 0)]))

(mf/defc overlay-frames
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  []
  (let [data     (mf/deref refs/workspace-page)
        objects  (:objects data)
        root     (get objects uuid/zero)
        shapes   (->> (:shapes root) (map #(get objects %)))]
    [:*
     [:g.shapes
      (for [item shapes]
        (if (= (:type item) :frame)
          [:& frame-wrapper {:shape item
                             :key (:id item)
                             :objects objects}]
          [:& shape-wrapper {:shape item
                             :key (:id item)}]))]]))

(defn draw-picker-canvas [svg-node canvas-node]
  (let [canvas-context (.getContext canvas-node "2d")
        xml (.serializeToString (js/XMLSerializer.) svg-node)
        img-src (str "data:image/svg+xml;base64,"
                     (-> xml js/encodeURIComponent js/unescape js/btoa))
        img (js/Image.)

        on-error (fn [err] (.error js/console "ERROR" err))
        on-load (fn [] (.drawImage canvas-context img 0 0))]
    (.addEventListener img "error" on-error)
    (.addEventListener img "load" on-load)
    (obj/set! img "src" img-src)))

(mf/defc pixel-overlay
  {::mf/wrap-props false}
  [props]
  (let [vport (unchecked-get props "vport")
        vbox (unchecked-get props "vbox")
        viewport-ref (unchecked-get props "viewport-ref")
        options (unchecked-get props "options")
        svg-ref       (mf/use-ref nil)
        canvas-ref    (mf/use-ref nil)
        fetch-pending (mf/deref (mdf/pending-ref))

        update-canvas-stream (rx/subject)

        handle-keydown
        (fn [event]
          (when (and (kbd/esc? event))
            (do (dom/stop-propagation event)
                (dom/prevent-default event)
                (st/emit! (dwc/stop-picker))
                (modal/disallow-click-outside!))))

        on-mouse-move-picker
        (fn [event]
          (when-let [zoom-view-node (.getElementById js/document "picker-detail")]
            (let [{brx :left bry :top} (dom/get-bounding-rect (mf/ref-val viewport-ref))
                  x (- (.-clientX event) brx)
                  y (- (.-clientY event) bry)

                  zoom-context (.getContext zoom-view-node "2d")
                  canvas-node (mf/ref-val canvas-ref)
                  canvas-context (.getContext canvas-node "2d")
                  pixel-data (.getImageData canvas-context x y 1 1)
                  rgba (.-data pixel-data)
                  r (obj/get rgba 0)
                  g (obj/get rgba 1)
                  b (obj/get rgba 2)
                  a (obj/get rgba 3)

                  area-data (.getImageData canvas-context (- x 25) (- y 20) 50 40)]

              (-> (js/createImageBitmap area-data)
                  (p/then (fn [image]
                            ;; Draw area
                            (obj/set! zoom-context "imageSmoothingEnabled" false)
                            (.drawImage zoom-context image 0 0 200 160))))
              (st/emit! (dwc/pick-color [r g b a])))))

        on-mouse-down-picker
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (st/emit! (dwc/pick-color-select true (kbd/shift? event))))

        on-mouse-up-picker
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (st/emit! (dwc/stop-picker))
          (modal/disallow-click-outside!))]

    (mf/use-effect
     (fn []
       (let [listener (events/listen js/document EventType.KEYDOWN  handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-canvas-stream
                      (rx/debounce 10)
                      (rx/subs #(draw-picker-canvas (mf/ref-val svg-ref)
                                                    (mf/ref-val canvas-ref))))]

         #(rx/dispose! sub))))

    (mf/use-effect
     (mf/deps svg-ref canvas-ref)
     (fn []
       (when (and svg-ref canvas-ref)

         (let [config (clj->js {:attributes true
                                :childList true
                                :subtree true
                                :characterData true})
               on-svg-change (fn [mutation-list] (rx/push! update-canvas-stream :update))
               observer (js/MutationObserver. on-svg-change)]

           (.observe observer (mf/ref-val svg-ref) config)

           ;; Disconnect on unmount
           #(.disconnect observer)))))

    [:*
     [:div.overlay
      {:tab-index 0
       :style {:position "absolute"
               :top 0
               :left 0
               :width "100%"
               :height "100%"
               :cursor cur/picker}
       :on-mouse-down on-mouse-down-picker
       :on-mouse-up on-mouse-up-picker
       :on-mouse-move on-mouse-move-picker}]
     [:canvas {:ref canvas-ref
               :width (:width vport 0)
               :height (:height vport 0)
               :style {:display "none"}}]

     [:& (mf/provider muc/embed-ctx) {:value true}
      [:svg.viewport
       {:ref svg-ref
        :preserveAspectRatio "xMidYMid meet"
        :width (:width vport 0)
        :height (:height vport 0)
        :view-box (format-viewbox vbox)
        :style {:display "none"
                :background-color (get options :background "#E8E9EA")}}
       [:& overlay-frames]]]]))
