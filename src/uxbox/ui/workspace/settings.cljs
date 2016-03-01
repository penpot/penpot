(ns uxbox.ui.workspace.settings
  (:require [sablono.core :as html :refer-macros [html]]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lightbox]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- settings-dialog-render
  [own]
  (html
   [:div.lightbox-body.settings
    [:div.settings-list
     (for [i (range 5)]
       [:div {:key i}
        [:span (str "shape " i)]])]]))

(def settings-dialog
  (mx/component
   {:render settings-dialog-render
    :name "settings-dialog"
    :mixins []}))

(defmethod lightbox/render-lightbox :settings
  [_]
  (settings-dialog))
