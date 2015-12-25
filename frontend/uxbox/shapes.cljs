(ns uxbox.shapes
  (:require [sablono.core :refer-macros [html]]))

(defmulti render
  (fn [shape & params]
    (:type shape)))

(defmethod render :builtin/icon
  [{:keys [data width height view-box]} & [attrs]]
  (let [attrs (merge
               (when width {:width width})
               (when height {:height height})
               (when view-box {:viewBox (apply str (interpose " " view-box))})
               attrs)]

    (html
     [:svg attrs data])))
