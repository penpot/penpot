(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [sablono.core :refer-macros [html]]
            [uxbox.shapes :as shapes]
            [uxbox.util.data :refer (remove-nil-vals)]))

(defn- transform-attr
  [[key value :as pair]]
  (println "transform-attr" pair)
  (case key
    :view-box [key (apply str (interpose " " value))]
    :lock [:preserveAspectRatio
           (if value "xMidYMid" "none")]
    pair))

(defn- transform-attrs
  [{:keys [view-box lock] :as data}]
  (let [xf (map transform-attr)]
    (into {} xf data)))

(defn- extract-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (select-keys shape [:lock :width :height :view-box :x :y :cx :cy]))

(defmethod shapes/-render :builtin/icon
  [{:keys [data id] :as shape} attrs]
  (let [attrs (as-> shape $
                (extract-attrs $)
                (remove-nil-vals $)
                (merge $ attrs {:lock false})
                (transform-attrs $))]
    (html
     [:svg (merge attrs {:key (str id)}) data])))

(defmethod shapes/-render :builtin/icon-svg
  [{:keys [image id] :as shape} attrs]
  (let [attrs (as-> shape $
                (extract-attrs $)
                (remove-nil-vals $)
                (merge $ attrs)
                (transform-attrs $))]
    (html
     [:svg (merge attrs {:key (str id)})
      [:image image]])))


