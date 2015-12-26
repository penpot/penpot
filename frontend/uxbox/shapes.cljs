(ns uxbox.shapes
  (:require [sablono.core :refer-macros [html]]
            [uxbox.util.data :refer (remove-nil-vals)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti -render
  (fn [shape attrs]
    (:type shape)))

(defn render
  ([shape] (-render shape nil))
  ([shape attrs] (-render shape attrs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn transform-attrs
  [{:keys [view-box] :as data}]
  (if view-box
    (assoc data :view-box (apply str (interpose " " view-box)))
    data))

(defmethod -render :builtin/icon
  [{:keys [data width height view-box] :as shape} attrs]
  (let [attrs (as-> shape $
                (select-keys $ [:width :height :view-box])
                (remove-nil-vals $)
                (merge $ attrs)
                (transform-attrs $))]
    (html
     [:svg attrs data])))
