(ns uxbox.ui.colorpicker
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [goog.events :as events]
            [uxbox.schema :as sc]
            [uxbox.util.color :as color]
            [uxbox.util.math :as mth]
            [uxbox.ui.mixins :as mx])
  (:import goog.events.EventType))

(def ^:static ^:private +types+
  {:library
   {:picker {:width 205 :height 205}
    :bar {:width 15 :height 205 :img "/images/color-bar-library.png"}}
   :options
   {:picker {:width 165 :height 165}
    :bar {:width 15 :height 165 :img "/images/color-bar-options.png"}}})

(defn- get-mouse-pos
  [own ref event]
  (let [canvas (mx/get-ref-dom own ref)
        brect (.getBoundingClientRect canvas)
        x (- (.-clientX event) (.-left brect))
        y (- (.-clientY event) (.-top brect))]
    [x y]))

(defn- draw-color-gradient
  [context type color]
  (let [width (get-in +types+ [type :picker :width])
        halfwidth (/ width 2)
        gradient1 (.createLinearGradient context 0 halfwidth width halfwidth)
        gradient2 (.createLinearGradient context halfwidth width halfwidth 0)]

    ;; Draw plain color
    (set! (.-fillStyle context) color)
    (.fillRect context 0 0 width width)

    ;;    Transparency gradient
    (.addColorStop gradient2 0 "rgba(255,255,255,1)")
    (.addColorStop gradient2 1 "rgba(0,0,0,0)")

    (set! (.-fillStyle context) gradient2)
    (.fillRect context 0 0 width width)

    ;; Color gradient
    (.addColorStop gradient1 0 "rgba(0,0,0,1)")
    (.addColorStop gradient1 0.8 "rgba(0,0,0,0)")

    (set! (.-fillStyle context) gradient1)
    (.fillRect context 0 0 width width)))

(defn- get-selection-border-color
  [color]
  (let [[r g b] (color/hex->rgb color)
        x1 (+ (* 0.299 r) (* 0.587 g) (* 0.114 b))
        darkness (- 1 (/ x1 255))]
    (if (> darkness 0.5)
      "#FFFFFF"
      "#000000")))

(defn- draw-current-selection
  [own color type event]
  (let [canvas (mx/get-ref-dom own "colorpicker")
        context (.getContext canvas "2d")
        local (:rum/local own)
        [x y :as pos] (get-mouse-pos own "colorpicker" event)
        border-color (get-selection-border-color color)]

    (.clearRect context 0 0 (.-width canvas) (.-height canvas))
    (draw-color-gradient context type (:color @local))

    (.beginPath context)
    (.arc context x y 5 0 (* js/Math.PI 2) false)
    (set! (.-fillStyle context) color)
    (.fill context)
    (set! (.-lineWidth context) 1)
    (set! (.-strokeStyle context) border-color)
    (.stroke context)))

(defn- initialize
  [own type]
  (let [canvas1 (mx/get-ref-dom own "colorpicker")
        context1 (.getContext canvas1 "2d")
        canvas2 (mx/get-ref-dom own "colorbar")
        context2 (.getContext canvas2 "2d")
        img (js/Image.)
        img-path (get-in +types+ [type :bar :img])
        local (:rum/local own)]

    (add-watch local ::key
               (fn [_ _ o v]
                 (when (not= (:color o) (:color v))
                   (println "KAKAKA" v)
                   (draw-color-gradient context1 type (:color v)))))
    (reset! local {:color "#FF0000"})
    ;; (draw-color-gradient context1 type "#FF0000")

    (set! (.-src img) img-path)
    (let [key1 (events/listen img EventType.LOAD #(.drawImage context2 img 0 0))]
      {::key key})))

(defn- get-color
  [own ref [x y]]
  (let [canvas (mx/get-ref-dom own ref)
        context (.getContext canvas "2d")
        image (.getImageData context x y 1 1)
        r (aget (.-data image) 0)
        g (aget (.-data image) 1)
        b (aget (.-data image) 2)]
    (color/rgb->hex [r g b])))

(defn- colorpicker-render
  [own type callback]
  (let [local (:rum/local own)
        cp-width (get-in +types+ [type :picker :width])
        cp-height (get-in +types+ [type :picker :height])
        cb-width (get-in +types+ [type :bar :width])
        cb-height (get-in +types+ [type :bar :height])
        bar-pos (:pos @local 0)]
    (letfn [(on-bar-mouse-down [event])
            (on-bar-mouse-up [event])
            (on-picker-click [event]
              (let [[x y :as pos] (get-mouse-pos own "colorpicker" event)
                    color (get-color own "colorpicker" pos)]
                (draw-current-selection own color type event)
                (callback {:hex color
                           :rgb (color/hex->rgb color)})))
            (on-bar-click [event]
              (let [[x y :as pos] (get-mouse-pos own "colorbar" event)
                    color (get-color own "colorbar" pos)
                    pos (/ (* 100 y) cb-height)]
                (swap! local assoc :pos pos :color color)))]
      (html
       [:div.element-color-picker
        [:div.color-picker-body
         [:canvas {:ref "colorpicker"
                   :on-click on-picker-click
                   :style {:border "1px solid #AAA"}
                   :width cp-width
                   :height cp-height
                   :id "colorpicker"}]]
        [:div.color-picker-bar
         [:div.color-bar-select {:style {:top (str bar-pos "%")}
                                 :on-mouse-down on-bar-mouse-down
                                 :on-mouse-up on-bar-mouse-up}]
         [:canvas {:ref "colorbar"
                   :on-click on-bar-click
                   :width cb-width
                   :height cb-height}]]]))))

(defn colorpicker-did-mount
  [own]
  (let [type (first (:rum/props own))]
    (->> (initialize own type)
         (merge own))))

(defn colorpicker-will-unmout
  [own]
  (let [key (::key own)
        local (:rum/local own)]
    (remove-watch local ::key)
    (events/unlistenByKey key)))

(defn- colorpicker-transfer-state
  [old-own own]
  (let [data (select-keys old-own [::key])]
    (merge own data)))

(def ^:static colorpicker
  (mx/component
   {:render colorpicker-render
    :did-mount colorpicker-did-mount
    :will-unmout colorpicker-will-unmout
    :transfer-state colorpicker-transfer-state
    :name "colorpicker"
    :mixins [mx/static (mx/local)]}))
