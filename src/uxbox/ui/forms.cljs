(ns uxbox.ui.forms
  (:require [sablono.core :refer-macros [html]]
            [uxbox.schema :as sc]))

(defn input-error
  [errors field]
  (when-let [errors (get errors field)]
    (html
     [:ul.form-errors
      (for [error errors]
        [:li {:key error} error])])))

(defn error-class
  [errors field]
  (when (get errors field)
    "invalid"))
