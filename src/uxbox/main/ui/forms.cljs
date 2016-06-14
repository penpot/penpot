(ns uxbox.main.ui.forms
  (:require [sablono.core :refer-macros [html]]
            [uxbox.common.i18n :refer (tr)]
            [uxbox.common.schema :as sc]))

(defn input-error
  [errors field]
  (when-let [error (get errors field)]
    (html
     [:ul.form-errors
      [:li {:key error} (tr error)]])))

(defn error-class
  [errors field]
  (when (get errors field)
    "invalid"))
