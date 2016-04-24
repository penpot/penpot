;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.canvas
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.constants :as c]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.data.shapes :as uds]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes :as uus]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.ui.workspace.drawarea :refer (draw-area)]
            [uxbox.ui.workspace.movement :as cmov]
            [uxbox.ui.workspace.resize :as cres]
            [uxbox.ui.workspace.ruler :refer (ruler)]
            [uxbox.ui.workspace.selection :refer (selection-handlers)]
            [uxbox.ui.workspace.selrect :refer (selrect)]
            [uxbox.ui.workspace.grid :refer (grid)])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Background
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn background-render
  []
  (html
   [:rect
    {:x 0 :y 0 :width "100%" :height "100%" :fill "white"}]))

(def background
  (mx/component
   {:render background-render
    :name "background"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canvas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- canvas-render
  [own {:keys [width height id] :as page}]
  (let [workspace (rum/react uuwb/workspace-l)
        flags (:flags workspace)]
    (html
     [:svg.page-canvas {:x c/canvas-start-x
                        :y c/canvas-start-y
                        :ref (str "canvas" id)
                        :width width
                        :height height}
      (background)
      [:svg.page-layout {}
       (selection-handlers)
       [:g.main {}
        (for [item (reverse (:shapes page))]
          (-> (uus/shape item)
              (rum/with-key (str item))))
        (draw-area)]]])))

(def canvas
  (mx/component
   {:render canvas-render
    :name "canvas"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Viewport Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn viewport-render
  [own]
  (let [workspace (rum/react uuwb/workspace-l)
        page (rum/react uuwb/page-l)
        flags (:flags workspace)
        drawing? (:drawing workspace)
        zoom (or (:zoom workspace) 1)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (if-let [shape (:drawing workspace)]
                (uuc/acquire-action! "ui.shape.draw")
                (do
                  (when-not (empty? (:selected workspace))
                    (rs/emit! (uds/deselect-all)))
                  (uuc/acquire-action! "ui.selrect"))))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (uuc/release-action! "ui.shape"
                                   "ui.selrect"))]
      (html
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
        (selrect)]))))

(defn- viewport-did-mount
  [own]
  (letfn [(translate-point-to-viewport [pt]
            (let [viewport (mx/get-ref-dom own "viewport")
                  brect (.getBoundingClientRect viewport)
                  brect (gpt/point (parse-int (.-left brect))
                                   (parse-int (.-top brect)))]
              (gpt/subtract pt brect)))

          (translate-point-to-canvas [pt]
            (let [viewport (mx/get-ref-dom own "viewport")]
              (when-let [canvas (dom/get-element-by-class "page-canvas" viewport)]
                (let [brect (.getBoundingClientRect canvas)
                      bbox (.getBBox canvas)
                      brect (gpt/point (parse-int (.-left brect))
                                       (parse-int (.-top brect)))
                      bbox (gpt/point (.-x bbox) (.-y bbox))]
                  (-> (gpt/add pt bbox)
                      (gpt/subtract brect))))))

          (on-key-down [event]
            (when (kbd/space? event)
              (uuc/acquire-action! "ui.workspace.scroll")))

          (on-key-up [event]
            (when (kbd/space? event)
              (uuc/release-action! "ui.workspace.scroll")))

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
              (rx/push! uuwb/mouse-b event)))]

    (let [key1 (events/listen js/document EventType.MOUSEMOVE on-mousemove)
          key2 (events/listen js/document EventType.KEYDOWN on-key-down)
          key3 (events/listen js/document EventType.KEYUP on-key-up)
          sub1 (cmov/watch-move-actions)
          sub2 (cres/watch-resize-actions)]
      (assoc own
             ::sub1 sub1
             ::sub2 sub2
             ::key1 key1
             ::key2 key2
             ::key3 key3))))

(defn- viewport-transfer-state
  [old-own own]
  (->> [::key1 ::key2 ::key3
        ::sub1 ::sub2]
       (select-keys old-own)
       (merge own)))

(defn- viewport-will-unmount
  [own]
  (events/unlistenByKey (::key1 own))
  (events/unlistenByKey (::key2 own))
  (events/unlistenByKey (::key3 own))
  (.close (::sub1 own))
  (.close (::sub2 own))
  (dissoc own ::key1 ::key2 ::key3 ::sub1 ::sub2))

(def viewport
  (mx/component
   {:render viewport-render
    :name "viewport"
    :did-mount viewport-did-mount
    :will-unmount viewport-will-unmount
    :transfer-state viewport-transfer-state
    :mixins [rum/reactive]}))
