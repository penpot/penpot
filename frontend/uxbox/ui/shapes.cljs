(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [uxbox.shapes :as shapes]
            [uxbox.util.data :refer (remove-nil-vals)]))

(defn- transform-attr
  [data [key value :as pair]]
  (case key
    :view-box
    [key (apply str (interpose " " value))]

    :lock
    [:preserveAspectRatio (if value "xMidYMid" "none")]

    :rotation
    (let [width (nth (:view-box data) 3)
          center-x (+ (:x data) (/ (:width data) 2))
          center-y (+ (:y data) (/ (:height data) 2))]
      [:transform (str/format "rotate(%s %s %s)" value center-x center-y)])

    pair))

(defn- transform-attrs
  [data]
  (let [xf (map (partial transform-attr data))]
    (into {} xf data)))

(defn- extract-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (select-keys shape [:rotation :lock :width :height
                      :view-box :x :y :cx :cy]))

(defmethod shapes/-render :builtin/icon
  [{:keys [data id] :as shape} attrs]
  (let [attrs (as-> shape $
                (extract-attrs $)
                (remove-nil-vals $)
                (merge $ attrs {:lock false})
                (transform-attrs $)
                (merge $ {:key (str id)}))]
    (html
     [:g attrs
      [:svg attrs data]])))

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


