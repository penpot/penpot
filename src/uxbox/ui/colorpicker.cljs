(ns uxbox.ui.colorpicker
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [goog.events :as events]
            [uxbox.util.color :as color]
            [uxbox.util.math :as mth]
            [uxbox.ui.mixins :as mx])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Color Picker
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-mouse-pos
  [own ref event]
  (let [canvas (mx/get-ref-dom own ref)
        brect (.getBoundingClientRect canvas)
        x (- (.-clientX event) (.-left brect))
        y (- (.-clientY event) (.-top brect))]
    [x y]))

(defn- get-color
  [own ref [x y]]
  (let [canvas (mx/get-ref-dom own ref)
        context (.getContext canvas "2d")
        image (.getImageData context x y 1 1)
        r (aget (.-data image) 0)
        g (aget (.-data image) 1)
        b (aget (.-data image) 2)]
    (println [r g b])
    (color/rgb->hex [r g b])))

(defn- colorpicker-render
  [own callback]
  (let [local (:rum/local own)
        bar-pos (:pos @local 0)]
    (letfn [(on-bar-mouse-down [event])
            (on-bar-mouse-up [event])
            (on-picker-click [event]
              (let [[x y :as pos] (get-mouse-pos own "colorpicker" event)
                    color (get-color own "colorpicker" pos)]
                (callback {:hex color
                           :rgb (color/hex->rgb color)})))
            (on-bar-click [event]
              (let [[x y :as pos] (get-mouse-pos own "colorbar" event)
                    color (get-color own "colorbar" pos)
                    pos (/ (* 100 y) 205)]
                (swap! local assoc :pos pos :color color)))]
      (html
       [:div.element-color-picker
        [:div.color-picker-body
         [:canvas {:ref "colorpicker"
                   :on-click on-picker-click
                   :style {:border "1px solid #AAA"}
                   :width "205"
                   :height "205"
                   :id "colorpicker"}]]
        [:div.color-picker-bar
         [:div.color-bar-select {:style {:top (str bar-pos "%")}
                                 :on-mouse-down on-bar-mouse-down
                                 :on-mouse-up on-bar-mouse-up}]
         [:canvas {:ref "colorbar"
                   :on-click on-bar-click
                   :width "15"
                   :height "205"}]]]))))

(defn- draw-color-gradient
  [context color]
  (let [gradient1 (.createLinearGradient context 0, 102.5, 205, 102.5)
        gradient2 (.createLinearGradient context 102.5, 205, 105, 0)]

    ;; Draw plain color
    (set! (.-fillStyle context) color)
    (.fillRect context 0 0 205 205)

    ;;    Transparency gradient
    (.addColorStop gradient2 0 "rgba(255,255,255,1)")
    (.addColorStop gradient2 1 "rgba(0,0,0,0)")

    (set! (.-fillStyle context) gradient2)
    (.fillRect context 0 0 205 205)

    ;; Color gradient
    (.addColorStop gradient1 0 "rgba(0,0,0,1)")
    (.addColorStop gradient1 0.8 "rgba(0,0,0,0)")

    (set! (.-fillStyle context) gradient1)
    (.fillRect context 0 0 205 205)))

(defn colorpicker-did-mount
  [own]
  (let [canvas1 (mx/get-ref-dom own "colorpicker")
        context1 (.getContext canvas1 "2d")
        canvas2 (mx/get-ref-dom own "colorbar")
        context2 (.getContext canvas2 "2d")
        img (js/Image.)
        local (:rum/local own)]

    (add-watch local ::key
               (fn [_ _ o v]
                 (println "add-watch" o v)
                 (when (not= (:color o) (:color v))
                   (draw-color-gradient context1 (:color v)))))

    (draw-color-gradient context1 "#FF0000")

    (set! (.-src img) "/images/color-bar.png")
    (let [key (events/listen img EventType.LOAD #(.drawImage context2 img 0 0))]
      (assoc own ::key key))))

(defn colorpicker-will-unmout
  [own]
  (let [key (::key own)
        local (:rum/local own)]
    (remove-watch local ::key)
    (events/unlistenByKey key)))

(defn- colorpicker-transfer-state
  [old-own own]
  (let [key (::key old-own)]
    (assoc own ::key key)))

(def ^:static colorpicker
  (mx/component
   {:render colorpicker-render
    :did-mount colorpicker-did-mount
    :will-unmout colorpicker-will-unmout
    :transfer-state colorpicker-transfer-state
    :name "colorpicker"
    :mixins [mx/static (mx/local)]}))
