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
            [potok.core :as ptk]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.main.constants :as c]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.shapes :as uus]
            [uxbox.main.ui.shapes.selection :refer (selection-handlers)]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.drawarea :refer (draw-area)]
            [uxbox.main.ui.workspace.ruler :refer (ruler)]
            [uxbox.main.ui.workspace.selrect :refer (selrect)]
            [uxbox.main.ui.workspace.grid :refer (grid)])
  (:import goog.events.EventType))

;; --- Background

(mx/defc background
  {:mixins [mx/static]}
  [{:keys [background] :as metadata}]
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill (or background "#ffffff")}])

;; --- Coordinates Widget

(mx/defc coordinates
  {:mixins [mx/reactive mx/static]}
  []
  (let [zoom (mx/react wb/zoom-ref)
        coords (some-> (mx/react wb/mouse-canvas-a)
                       (gpt/divide zoom)
                       (gpt/round 0))]
   [:ul.coordinates
    [:span {:alt "x"}
     (str "X: " (:x coords "-"))]
    [:span {:alt "y"}
     (str "Y: " (:y coords "-"))]]))

(mx/defc cursor-tooltip
  {:mixins [mx/reactive mx/static]}
  [tooltip]
  (let [coords (mx/react wb/mouse-absolute-a)]
   [:span.cursor-tooltip
    {:style
     {:position "fixed"
      :left (str (+ (:x coords) 5) "px")
      :top (str (- (:y coords) 25) "px")}}
    tooltip]))

;; --- Canvas

(mx/defc canvas
  {:mixins [mx/reactive]}
  [{:keys [metadata id] :as page}]
  (let [workspace (mx/react wb/workspace-ref)
        flags (:flags workspace)
        width (:width metadata)
        height (:height metadata)]
    [:svg.page-canvas {:x c/canvas-start-x
                       :y c/canvas-start-y
                       :ref (str "canvas" id)
                       :width width
                       :height height}
     (background metadata)
     [:svg.page-layout
      [:g.main {}
       (for [item (reverse (:shapes page))]
         (-> (uus/shape item)
             (mx/with-key (str item))))
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
            (let [opts {:key (.-keyCode event)
                        :shift? (kbd/shift? event)
                        :ctrl? (kbd/ctrl? event)}]
              (rx/push! wb/events-b [:key/down opts])
              (when (kbd/space? event)
                (rlocks/acquire! :workspace/scroll))))

          (on-key-up [event]
            (let [opts {:key (.-keyCode event)
                        :shift? (kbd/shift? event)
                        :ctrl? (kbd/ctrl? event)}]
              (rx/push! wb/events-b [:key/up opts])))

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
              ;; FIXME: refactor streams in order to use the wb/events-b
              ;;        for all keyboard and mouse events and then derive
              ;;        all the other from it.
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
        tooltip (:tooltip workspace)
        zoom (or (:zoom workspace) 1)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (let [opts {:shift? (kbd/shift? event)
                          :ctrl? (kbd/ctrl? event)}]
                (rx/push! wb/events-b [:mouse/down opts]))
              (if (:drawing workspace)
                (rlocks/acquire! :ui/draw)
                (do
                  (when (seq (:selected workspace))
                    (rlocks/release! :shape/edition))
                  (rlocks/acquire! :ui/selrect))))
            (on-context-menu [event]
              (dom/prevent-default event)
              (dom/stop-propagation event)
              (let [opts {:shift? (kbd/shift? event)
                          :ctrl? (kbd/ctrl? event)}]
                (rx/push! wb/events-b [:mouse/right-click opts])))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (let [opts {:shift? (kbd/shift? event)
                          :ctrl? (kbd/ctrl? event)}]
                (rx/push! wb/events-b [:mouse/up])))
            (on-click [event]
              (dom/stop-propagation event)
              (let [opts {:shift? (kbd/shift? event)
                          :ctrl? (kbd/ctrl? event)}]
                (rx/push! wb/events-b [:mouse/click opts])))
            (on-double-click [event]
              (dom/stop-propagation event)
              (let [opts {:shift? (kbd/shift? event)
                          :ctrl? (kbd/ctrl? event)}]
                (rx/push! wb/events-b [:mouse/double-click opts])))]
      [:div
        (coordinates)
        (when tooltip
          (cursor-tooltip tooltip))
        [:svg.viewport {:width (* c/viewport-width zoom)
                        :height (* c/viewport-height zoom)
                        :ref "viewport"
                        :class (when drawing? "drawing")
                        :on-context-menu on-context-menu
                        :on-click on-click
                        :on-double-click on-double-click
                        :on-mouse-down on-mouse-down
                        :on-mouse-up on-mouse-up}
         [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
          (if page
            (canvas page))
          (if (contains? flags :grid)
            (grid))]
         (ruler)
         (selrect)]])))


