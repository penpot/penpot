;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.canvas
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.main.constants :as c]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.geom.point :as gpt]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.shapes :as uus]
            ;; [uxbox.main.ui.shapes.path :as spath]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.rlocks :as rlocks]
            [uxbox.main.ui.workspace.drawarea :refer (draw-area)]
            [uxbox.main.ui.workspace.ruler :refer (ruler)]
            [uxbox.main.ui.workspace.selection :refer (selection-handlers)]
            [uxbox.main.ui.workspace.selrect :refer (selrect)]
            [uxbox.main.ui.workspace.grid :refer (grid)])
  (:import goog.events.EventType))

;; --- Background

(mx/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill "white"}])

;; --- Canvas

;; (def ^:private test-path-shape
;;   {:type :path
;;    :id #uuid "042951a0-804a-4cf1-b606-3e97157f55b5"
;;    :stroke-type :solid
;;    :stroke "#000000"
;;    :stroke-width 2
;;    :fill "transparent"
;;    :close? true
;;    :points [(gpt/point 100 100)
;;             (gpt/point 300 100)
;;             (gpt/point 200 300)
;;             ]})

(mx/defc canvas
  {:mixins [mx/reactive]}
  [{:keys [width height id] :as page}]
  (let [workspace (mx/react wb/workspace-ref)
        flags (:flags workspace)]
    [:svg.page-canvas {:x c/canvas-start-x
                       :y c/canvas-start-y
                       :ref (str "canvas" id)
                       :width width
                       :height height}
     (background)
     [:svg.page-layout {}
      [:g.main {}
       (for [item (reverse (:shapes page))]
         (-> (uus/shape item)
             (mx/with-key (str item))))
       ;; (spath/path-component test-path-shape)
       (selection-handlers)
       (draw-area)]]]))

;; --- Viewport

(defn- viewport-did-mount
  [own]
  (letfn [(translate-point-to-viewport [pt]
            (let [viewport (mx/ref-node own "viewport")
                  brect (.getBoundingClientRect viewport)
                  brect (gpt/point (parse-int (.-left brect))
                                   (parse-int (.-top brect)))]
              (gpt/subtract pt brect)))

          (translate-point-to-canvas [pt]
            (let [viewport (mx/ref-node own "viewport")]
              (when-let [canvas (dom/get-element-by-class "page-canvas" viewport)]
                (let [brect (.getBoundingClientRect canvas)
                      bbox (.getBBox canvas)
                      brect (gpt/point (parse-int (.-left brect))
                                       (parse-int (.-top brect)))
                      bbox (gpt/point (.-x bbox) (.-y bbox))]
                  (-> (gpt/add pt bbox)
                      (gpt/subtract brect))))))

          (on-key-down [event]
            (rx/push! wb/keyboard-events-b {:type :keyboard/down
                                            :key (.-keyCode event)
                                            :shift? (kbd/shift? event)
                                            :ctrl? (kbd/ctrl? event)})
            (when (kbd/space? event)
              (rlocks/acquire! :workspace/scroll)))

          (on-key-up [event]
            (rx/push! wb/keyboard-events-b {:type :keyboard/up
                                            :key (.-keyCode event)
                                            :shift? (kbd/shift? event)
                                            :ctrl? (kbd/ctrl? event)}))

          (on-mousemove [event]
            (let [wpt (gpt/point (.-clientX event)
                                 (.-clientY event))
                  vppt (translate-point-to-viewport wpt)
                  cvpt (translate-point-to-canvas wpt)
                  event {:ctrl (kbd/ctrl? event)
                         :shift (kbd/shift? event)
                         :window-coords wpt
                         :viewport-coords vppt
                         :canvas-coords cvpt}]
              (rx/push! wb/mouse-b event)))]

    (let [key1 (events/listen js/document EventType.MOUSEMOVE on-mousemove)
          key2 (events/listen js/document EventType.KEYDOWN on-key-down)
          key3 (events/listen js/document EventType.KEYUP on-key-up)]
      (assoc own
             ::key1 key1
             ::key2 key2
             ::key3 key3))))

(defn- viewport-will-unmount
  [own]
  (events/unlistenByKey (::key1 own))
  (events/unlistenByKey (::key2 own))
  (events/unlistenByKey (::key3 own))
  (dissoc own ::key1 ::key2 ::key3))

(mx/defc viewport
  {:did-mount viewport-did-mount
   :will-unmount viewport-will-unmount
   :mixins [mx/reactive]}
  []
  (let [workspace (mx/react wb/workspace-ref)
        page (mx/react wb/page-ref)
        flags (:flags workspace)
        drawing? (:drawing workspace)
        zoom (or (:zoom workspace) 1)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (rx/push! wb/mouse-events-b :mouse/down)
              (if (:drawing workspace)
                (rlocks/acquire! :ui/draw)
                (rlocks/acquire! :ui/selrect)))
            (on-mouse-up [event]
              (rx/push! wb/mouse-events-b :mouse/up)
              (dom/stop-propagation event))]
      [:svg.viewport {:width (* c/viewport-width zoom)
                      :height (* c/viewport-height zoom)
                      :ref "viewport"
                      :class (when drawing? "drawing")
                      :on-mouse-down on-mouse-down
                      :on-mouse-up on-mouse-up}
       [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
        (if page
          (canvas page))
        (if (contains? flags :grid)
          (grid))]
       (ruler)
       (selrect)])))


