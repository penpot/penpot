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
  (select-keys shape [:fill :opacity :stroke :stroke-opacity :stroke-width]))

(defn- make-debug-attrs
  [shape]
  (let [attrs (select-keys shape [:rotation :width :height :x :y])
        xf (map (fn [[x v]]
                    [(keyword (str "data-" (name x))) v]))]
      (into {} xf attrs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod shapes/-render :builtin/icon
  [{:keys [data id] :as shape} attrs]
  (let [key (str "use-" id)
        transform (-> (merge shape attrs)
                      (svg/calculate-transform))
        attrs (merge {:id key :key key :transform transform}
                     (extract-attrs shape)
                     (make-debug-attrs shape))]
    (html
     [:g attrs data])))

(defmethod shapes/-render :builtin/group
  [{:keys [items id] :as shape} attrs]
  (let [key (str "group-" id)
        tfm (-> (merge shape attrs)
                (svg/calculate-transform))
        attrs (merge {:id key :key key :transform tfm}
                     (make-debug-attrs shape))
        shapes-by-id (get @st/state :shapes-by-id)]
    (html
     [:g attrs
      (for [item (map #(get shapes-by-id %) items)]
        (shapes/-render item nil))])))

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

