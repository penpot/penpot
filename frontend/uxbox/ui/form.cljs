(ns uxbox.ui.form
  (:require [sablono.core :refer-macros [html]]
            [uxbox.schema :as sc]))

(defn validate!
  [local schema]
  (if-let [errors (sc/validate schema @local)]
    (swap! local assoc :errors errors)
    (swap! local assoc :errors nil)))

(defn input-error
  [local name]
  (when-let [errors (get-in @local [:errors name])]
     [:div.errors
      [:ul {}
       (for [error errors]
         [:li error])]]))

(defn error-class
  [local name]
  (when (get-in @local [:errors name])
    "invalid"))
