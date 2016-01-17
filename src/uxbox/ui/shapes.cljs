(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.util.svg :as svg]
            [uxbox.util.data :refer (remove-nil-vals)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attribute transformations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (select-keys shape [:rotation :width :height
                      :x :y :opacity :fill]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod shapes/-render :builtin/icon
  [{:keys [data id] :as shape} attrs]
  (let [key (str "use-" id)
        transform (-> (merge shape attrs)
                      (svg/calculate-transform))
        attrs {:id key
               :key key
               :transform transform}]
    (html
     [:g attrs data])))

(defmethod shapes/-render-svg :builtin/icon
  [{:keys [data id view-box] :as shape} attrs]
  (let [key (str "icon-svg-" id)
        view-box (apply str (interpose " " view-box))
        props {:view-box view-box :id key :key key}
        attrs (-> shape
                  (extract-attrs)
                  (remove-nil-vals)
                  (merge attrs props))]
    (html
     [:svg attrs data])))

(defmethod shapes/-render :builtin/group
  [{:keys [items id] :as shape} attrs]
  (let [key (str "group-" id)
        tfm (-> (merge shape attrs)
                (svg/calculate-transform))
        attrs {:id key :key key :transform tfm}
        shapes-by-id (get @st/state :shapes-by-id)]
    (html
     [:g attrs
      (for [item (map #(get shapes-by-id %) items)]
        (shapes/-render item nil))])))
