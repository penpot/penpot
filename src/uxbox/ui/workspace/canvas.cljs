(ns uxbox.ui.workspace.canvas
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [goog.events :as events]
            [uxbox.rstore :as rs]
            [uxbox.shapes :as sh]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes :as uus]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.ui.workspace.canvas.movement]
            [uxbox.ui.workspace.canvas.resize]
            [uxbox.ui.workspace.canvas.draw :refer (draw-area)]
            [uxbox.ui.workspace.canvas.ruler :refer (ruler)]
            [uxbox.ui.workspace.canvas.selection :refer (shapes-selection)]
            [uxbox.ui.workspace.canvas.selrect :refer (selrect)]
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
  (let [workspace (rum/react uuwb/workspace-l)]
    (html
     [:svg.page-canvas {:x uuwb/canvas-start-x
                        :y uuwb/canvas-start-y
                        :ref (str "canvas" id)
                        :width width
                        :height height}
      (background)
      (grid 1)
      [:svg.page-layout {}
       (shapes-selection)
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
        drawing? (:drawing workspace)
        zoom 1]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (when-not (empty? (:selected workspace))
                (rs/emit! (dw/deselect-all)))
              (if-let [shape (:drawing workspace)]
                (uuc/acquire-action! :draw/shape)
                (uuc/acquire-action! :draw/selrect)))
            (on-mouse-up [event]
              (dom/stop-propagation event)
              (uuc/release-all-actions!))]
      (html
       [:svg.viewport {:width uuwb/viewport-width
                       :height uuwb/viewport-height
                       :ref "viewport"
                       :class (when drawing? "drawing")
                       :on-mouse-down on-mouse-down
                       :on-mouse-up on-mouse-up}
        [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
         (if page
           (canvas page))
         (ruler)
         (selrect)]]))))

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
              (uuc/acquire-action! :scroll/viewport)))

          (on-key-up [event]
            (when (kbd/space? event)
              (uuc/release-action! :scroll/viewport)))

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
          key3 (events/listen js/document EventType.KEYUP on-key-up)]
      (assoc own ::key1 key1 ::key2 key2 ::key3 key3))))

(defn- viewport-will-unmount
  [own]
  (let [key1 (::key1 own)
        key2 (::key2 own)
        key3 (::key3 own)]
    (events/unlistenByKey key1)
    (events/unlistenByKey key2)
    (events/unlistenByKey key3)
    (dissoc own ::key1 ::key2 ::key3)))

(defn- viewport-transfer-state
  [old-own own]
  (let [data (select-keys old-own [::key1 ::key2 ::key3])]
    (merge own data)))

(def viewport
  (mx/component
   {:render viewport-render
    :name "viewport"
    :did-mount viewport-did-mount
    :will-unmount viewport-will-unmount
    :transfer-state viewport-transfer-state
    :mixins [rum/reactive]}))
