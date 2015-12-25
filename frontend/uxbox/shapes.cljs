(ns uxbox.shapes
  (:require [sablono.core :refer-macros [html]]))

(defmulti render
  (fn [shape & params]
    (:type shape)))

(defmethod render :builtin/icon
  [shape & [{:keys [width height] :or {width "500" height "500"}}]]
  (let [content (:svg shape)]
    (html
     [:svg {:width width :height height} content])))
