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

(mf/defc pixel-overlay
  {::mf/wrap-props false}
  [props]
  (let [vport        (unchecked-get props "vport")
        vbox         (unchecked-get props "vbox")
        viewport-ref (unchecked-get props "viewport-ref")
        options      (unchecked-get props "options")
        svg-ref      (mf/use-ref nil)
        canvas-ref   (mf/use-ref nil)
        img-ref      (mf/use-ref nil)

        update-str (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (and (kbd/esc? event))
             (do (dom/stop-propagation event)
                 (dom/prevent-default event)
                 (st/emit! (dwc/stop-picker))
                 (modal/disallow-click-outside!)))))

        handle-mouse-move-picker
        (mf/use-callback
         (mf/deps viewport-ref)
         (fn [event]
           (when-let [zoom-view-node (.getElementById js/document "picker-detail")]
             (let [viewport-node (mf/ref-val viewport-ref)
                   canvas-node   (mf/ref-val canvas-ref)

                   {brx :left bry :top} (dom/get-bounding-rect viewport-node)
                   x (- (.-clientX event) brx)
                   y (- (.-clientY event) bry)

                   zoom-context (.getContext zoom-view-node "2d")
                   canvas-context (.getContext canvas-node "2d")
                   pixel-data (.getImageData canvas-context x y 1 1)
                   rgba (.-data pixel-data)
                   r (obj/get rgba 0)
                   g (obj/get rgba 1)
                   b (obj/get rgba 2)
                   a (obj/get rgba 3)
                   area-data (.getImageData canvas-context (- x 25) (- y 20) 50 40)]
               (-> (js/createImageBitmap area-data)
                   (p/then
                    (fn [image]
                      ;; Draw area
                      (obj/set! zoom-context "imageSmoothingEnabled" false)
                      (.drawImage zoom-context image 0 0 200 160))))
               (st/emit! (dwc/pick-color [r g b a]))))))

        handle-mouse-down-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwc/pick-color-select true (kbd/shift? event)))))

        handle-mouse-up-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwc/stop-picker))
           (modal/disallow-click-outside!)))

        handle-image-load
        (mf/use-callback
         (mf/deps img-ref)
         (fn []
           (let [canvas-node (mf/ref-val canvas-ref)
                 img-node (mf/ref-val img-ref)
                 canvas-context (.getContext canvas-node "2d")]
             (.drawImage canvas-context img-node 0 0))))

        handle-draw-picker-canvas
        (mf/use-callback
         (mf/deps img-ref)
         (fn []
           (let [img-node (mf/ref-val img-ref)
                 svg-node (mf/ref-val svg-ref)
                 xml  (-> (js/XMLSerializer.)
                          (.serializeToString svg-node)
                          js/encodeURIComponent
                          js/unescape
                          js/btoa)
                 img-src (str "data:image/svg+xml;base64," xml)]
             (obj/set! img-node "src" img-src))))

        handle-svg-change
        (mf/use-callback
         (fn []
           (rx/push! update-str :update)))]

    (mf/use-effect
     (fn []
       (let [listener (events/listen js/document EventType.KEYDOWN  handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs handle-draw-picker-canvas))]
         #(rx/dispose! sub))))

    (mf/use-effect
     (mf/deps svg-ref)
     (fn []
       (when svg-ref
         (let [config #js {:attributes true
                           :childList true
                           :subtree true
                           :characterData true}
               svg-node (mf/ref-val svg-ref)
               observer (js/MutationObserver. handle-svg-change)]
           (.observe observer svg-node config)
           (handle-svg-change)

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
       :on-mouse-down handle-mouse-down-picker
       :on-mouse-up handle-mouse-up-picker
       :on-mouse-move handle-mouse-move-picker}
      [:div {:style {:display "none"}}
       [:img {:ref img-ref
              :on-load handle-image-load
              :style {:position "absolute"
                      :width "100%"
                      :height "100%"}}]
       [:canvas {:ref canvas-ref
                 :width (:width vport 0)
                 :height (:height vport 0)
                 :style {:position "absolute"
                         :width "100%"
                         :height "100%"}}]

       [:& (mf/provider muc/embed-ctx) {:value true}
        [:svg.viewport
         {:ref svg-ref
          :preserveAspectRatio "xMidYMid meet"
          :width (:width vport 0)
          :height (:height vport 0)
          :view-box (format-viewbox vbox)
          :style {:position "absolute"
                  :width "100%"
                  :height "100%"
                  :background-color (get options :background "#E8E9EA")}}
         [:& overlay-frames]]]]]]))
