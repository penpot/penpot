;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

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
