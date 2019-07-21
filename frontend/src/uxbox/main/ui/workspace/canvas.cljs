;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.canvas
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as streams]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes :as uus]
   [uxbox.main.ui.shapes.selection :refer [selection-handlers]]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.grid :refer [grid]]
   [uxbox.main.ui.workspace.ruler :refer [ruler]]
   [uxbox.main.ui.workspace.scroll :as scroll]
   [uxbox.main.user-events :as uev]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt])
  (:import goog.events.EventType))

;; --- Background

(mf/def background
  :mixins [mf/memo]
  :render
  (fn [own {:keys [background] :as metadata}]
    [:rect
     {:x 0 :y 0
      :width "100%"
      :height "100%"
      :fill (or background "#ffffff")}]))

;; --- Coordinates Widget

(mf/def coordinates
  :mixins [mf/reactive mf/memo]
  :render
  (fn [own props]
    (let [zoom (mf/react refs/selected-zoom)
          coords (some-> (mf/react refs/canvas-mouse-position)
                         (gpt/divide zoom)
                         (gpt/round 0))]
      [:ul.coordinates {}
       [:span {:alt "x"}
        (str "X: " (:x coords "-"))]
       [:span {:alt "y"}
        (str "Y: " (:y coords "-"))]])))

;; --- Selection Rect

(def selrect-ref
  (-> (l/key :selrect)
      (l/derive refs/workspace)))

(mf/def selrect
  :mixins [mf/memo mf/reactive]
  :render
  (fn [own props]
    (when-let [rect (mf/react selrect-ref)]
      (let [{:keys [x1 y1 width height]} (geom/size rect)]
        [:rect.selection-rect
         {:x x1
          :y y1
          :width width
          :height height}]))))

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

(mf/def cursor-tooltip
  :mixins [mf/reactive mf/memo]
  :render
  (fn [own tooltip]
    (let [coords (mf/react refs/window-mouse-position)]
      [:span.cursor-tooltip
       {:style
        {:position "fixed"
         :left (str (+ (:x coords) 5) "px")
         :top (str (- (:y coords) 25) "px")}}
       tooltip])))

;; --- Canvas

(mf/def canvas
  :mixins [mf/memo mf/reactive]
  :render
  (fn [own {:keys [page zoom] :as props}]
    (let [{:keys [metadata id]} page
          width (:width metadata)
          height (:height metadata)]
      [:svg.page-canvas {:x c/canvas-start-x
                         :y c/canvas-start-y
                         :ref (str "canvas" id)
                         :width width
                         :height height}
       (background metadata)
       [:svg.page-layout
        [:g.main
         (for [item (reverse (:shapes page))]
           (-> (uus/shape item)
               (mf/with-key (str item))))
         (selection-handlers)
         (draw-area zoom)]]])))

;; --- Viewport

(mf/def viewport
  :mixins [mf/reactive]

  :init
  (fn [own props]
    (assoc own ::viewport-ref (mf/create-ref)))

  :did-mount
  (fn [own]
    (letfn [(translate-point-to-viewport [pt]
              (let [viewport (mf/ref-node (::viewport-ref own))
                    brect (.getBoundingClientRect viewport)
                    brect (gpt/point (parse-int (.-left brect))
                                     (parse-int (.-top brect)))]
                (gpt/subtract pt brect)))

            (translate-point-to-canvas [pt]
              (let [viewport (mf/ref-node (::viewport-ref own))]
                (when-let [canvas (dom/get-element-by-class "page-canvas" viewport)]
                  (let [brect (.getBoundingClientRect canvas)
                        bbox (.getBBox canvas)
                        brect (gpt/point (parse-int (.-left brect))
                                         (parse-int (.-top brect)))
                        bbox (gpt/point (.-x bbox) (.-y bbox))]
                    (-> (gpt/add pt bbox)
                        (gpt/subtract brect))))))

            (on-key-down [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uev/keyboard-event :down key ctrl? shift?))
                (when (kbd/space? event)
                  (st/emit! (udw/start-viewport-positioning)))))

            (on-key-up [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when (kbd/space? event)
                  (st/emit! (udw/stop-viewport-positioning)))
                (st/emit! (uev/keyboard-event :up key ctrl? shift?))))

            (on-mousemove [event]
              (let [wpt (gpt/point (.-clientX event)
                                   (.-clientY event))
                    vpt (translate-point-to-viewport wpt)
                    cpt (translate-point-to-canvas wpt)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    event {:ctrl ctrl?
                           :shift shift?
                           :window-coords wpt
                           :viewport-coords vpt
                           :canvas-coords cpt}]
                (st/emit! (uev/pointer-event wpt vpt cpt ctrl? shift?))))]

      (let [key1 (events/listen js/document EventType.MOUSEMOVE on-mousemove)
            key2 (events/listen js/document EventType.KEYDOWN on-key-down)
            key3 (events/listen js/document EventType.KEYUP on-key-up)]
        (assoc own
               ::key1 key1
               ::key2 key2
               ::key3 key3))))

  :will-unmount
  (fn [own]
    (events/unlistenByKey (::key1 own))
    (events/unlistenByKey (::key2 own))
    (events/unlistenByKey (::key3 own))
    (dissoc own ::key1 ::key2 ::key3))

  :render
  (fn [own props]
    (let [page (mf/react refs/selected-page)
          flags (mf/react refs/flags)
          drawing (mf/react refs/selected-drawing-tool)
          tooltip (or (mf/react refs/selected-tooltip)
                      (get-shape-tooltip drawing))
          zoom (or (mf/react refs/selected-zoom) 1)]
      (letfn [(on-mouse-down [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :down ctrl? shift?)))
                (if drawing
                  (st/emit! (udw/start-drawing drawing))
                  (st/emit! ::uev/interrupt (udw/start-selrect))))
              (on-context-menu [event]
                (dom/prevent-default event)
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :context-menu ctrl? shift?))))
              (on-mouse-up [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :up ctrl? shift?))))
              (on-click [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :click ctrl? shift?))))
              (on-double-click [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uev/mouse-event :double-click ctrl? shift?))))]
        [:*
         (coordinates)
         [:div.tooltip-container
          (when tooltip
            (cursor-tooltip tooltip))]
         [:svg.viewport {:width (* c/viewport-width zoom)
                         :height (* c/viewport-height zoom)
                         :ref (::viewport-ref own)
                         :class (when drawing "drawing")
                         :on-context-menu on-context-menu
                         :on-click on-click
                         :on-double-click on-double-click
                         :on-mouse-down on-mouse-down
                         :on-mouse-up on-mouse-up}
          [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
           (when page
             (canvas {:page page :zoom zoom}))
           (if (contains? flags :grid)
             (grid))]
          (when (contains? flags :ruler)
            (ruler zoom))
          (selrect)]]))))
