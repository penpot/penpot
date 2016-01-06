(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [uxbox.shapes :as shapes]
            [uxbox.util.data :refer (remove-nil-vals)]))

(defn- transform-attr
  [data acc key value]
  (case key
    :view-box
    (assoc! acc key (apply str (interpose " " value)))

    :lock
    (assoc! acc :preserveAspectRatio (if value "xMidYMid" "none"))

    :rotation
    (let [center-x (+ (:x data) (/ (:width data) 2))
          center-y (+ (:y data) (/ (:height data) 2))]
      (assoc! acc :transform (str/format "rotate(%s %s %s)"
                                         value center-x center-y)))

    (assoc! acc key value)))

(defn- transform-attrs
  [data]
  (persistent!
   (reduce-kv (partial transform-attr data)
              (transient {})
              data)))

(defn- extract-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (select-keys shape [:rotation :lock :width :height
                      :view-box :x :y :cx :cy :fill]))

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


