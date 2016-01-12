(ns uxbox.ui.colorpicker
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [goog.events :as events]
            [uxbox.ui.mixins :as mx])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Color Picker
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-click-handler
  [e own callback]
  (let [canvas (mx/get-ref-dom own "colorpicker")
        context (.getContext canvas "2d")
        brect (.getBoundingClientRect canvas)
        x (- (.-pageX e) (.-left brect))
        y (- (.-pageY e) (.-top brect))
        image (.getImageData context x y 1 1)
        r (aget (.-data image) 0)
        g (aget (.-data image) 1)
        b (aget (.-data image) 2)]
    (callback {:hex (mx/rgb->hex [r g b])
               :rgb [r g b]})))

(defn colorpicker-render
  [own callback]
  (html
   [:section.colorpicker
    [:canvas
     {:width "400"
      :height "300"
      :on-click #(on-click-handler % own callback)
      :id "colorpicker"
      :ref "colorpicker"}]]))

(defn colorpicker-did-mount
  [own]
  (let [canvas (mx/get-ref-dom own "colorpicker")
        context (.getContext canvas "2d")
        img (js/Image.)]
    (set! (.-src img) "/images/colorspecrum-400x300.png")
    (let [key (events/listen img EventType.LOAD #(.drawImage context img 0 0))]
      (assoc own ::key key))))

(defn colorpicker-will-unmout
  [own]
  (let [key (::key own)]
    (events/unlistenByKey key)))

(def ^:static colorpicker
  (mx/component
   {:render colorpicker-render
    :did-mount colorpicker-did-mount
    :will-unmout colorpicker-will-unmout
    :name "colorpicker"
    :mixins [mx/static]}))
