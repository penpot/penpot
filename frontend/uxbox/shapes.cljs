(ns uxbox.shapes
  (:require [sablono.core :refer-macros [html]]
            [uxbox.util.data :refer (remove-nil-vals)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private +hierarchy+
  (as-> (make-hierarchy) $
    (derive $ :builtin/icon ::shape)
    (derive $ :builtin/icon-svg ::shape)
    (derive $ :builtin/icon-group ::shape)))

(defn shape?
  [type]
  {:pre [(keyword? type)]}
  (isa? +hierarchy+ type ::shape))

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

(defn extract-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (select-keys shape [:width :height :view-box :x :y :cx :cy]))

(defmethod -render :builtin/icon
  [{:keys [data] :as shape} attrs]
  (let [attrs (as-> shape $
                (extract-attrs $)
                (remove-nil-vals $)
                (merge $ attrs)
                (transform-attrs $))]
    (html
     [:svg attrs data])))

(defmethod -render :builtin/icon-svg
  [{:keys [image] :as shape} attrs]
  (let [attrs (as-> shape $
                (extract-attrs $)
                (remove-nil-vals $)
                (merge $ attrs)
                (transform-attrs $))]
    (html
     [:svg attrs
      [:image image]])))

