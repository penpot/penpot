(ns uxbox.ui.form
  (:require [sablono.core :refer-macros [html]]
            [uxbox.schema :as sc]))

(defn validate!
  [local schema]
  (let [[errors data] (sc/validate (:form @local) schema)]
    (if errors
      (do
        (swap! local assoc :errors errors)
        nil)
      (do
        (swap! local assoc :errors nil)
        data))))

(defn input-error
  [local name]
  (when-let [errors (get-in @local [:errors name])]
    [:ul.form-errors
     (for [error errors]
       [:li {:key error} error])]))

(defn error-class
  [local name]
  (when (get-in @local [:errors name])
    "invalid"))
