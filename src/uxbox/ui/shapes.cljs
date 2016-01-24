(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
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
  [{:keys [data id] :as shape} _]
  (let [key (str id)
        rfm (svg/calculate-transform shape)
        attrs (merge {:id key :key key :transform rfm}
                     (extract-attrs shape)
                     (make-debug-attrs shape))]
    (html
     [:g attrs data])))

(defmethod shapes/-render :builtin/rect
  [{:keys [id view-box] :as shape} _]
  (let [key (str id)
        rfm (svg/calculate-transform shape)
        attrs (merge {:width (nth view-box 2)
                      :height (nth view-box 3)
                      :x 0 :y 0}
                     (extract-attrs shape)
                     (make-debug-attrs shape))]
    (html
     [:g {:id key :key key :transform rfm}
      [:rect attrs]])))

(defmethod shapes/-render :builtin/group
  [{:keys [items id] :as shape} factory]
  (let [key (str "group-" id)
        tfm (svg/calculate-transform shape)
        attrs (merge {:id key :key key :transform tfm}
                     (make-debug-attrs shape))
        shapes-by-id (get @st/state :shapes-by-id)]
    (html
     [:g attrs
      (for [item (->> items
                      (map #(get shapes-by-id %))
                      (remove :hidden))]
        (-> (factory item)
            (rum/with-key (str (:id item)))))])))

(defmethod shapes/-render-svg :builtin/icon
  [{:keys [data id view-box] :as shape}]
  (let [key (str "icon-svg-" id)
        view-box (apply str (interpose " " view-box))
        props {:view-box view-box :id key :key key}
        attrs (-> shape
                  (extract-attrs)
                  (remove-nil-vals)
                  (merge props))]
    (html
     [:svg attrs data])))


(defmethod shapes/-render-svg :builtin/rect
  [{:keys [id view-box] :as shape}]
  (let [key (str "icon-svg-" id)
        view (apply str (interpose " " view-box))
        props {:view-box view :id key :key key}
        attrs (merge {:width (nth view-box 2)
                      :height (nth view-box 3)
                      :x 0 :y 0}
                     (extract-attrs shape)
                     (make-debug-attrs shape))]
    (html
     [:svg props
      [:rect attrs]])))

