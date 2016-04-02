;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.confirm
  (:require [sablono.core :as html :refer-macros [html]]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.ui.lightbox :as lightbox]))

(defn- confirm-dialog-render
  [own]
  (html
   [:div.lightbox-body.confirm-dialog
    [:span "HERE"]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (lightbox/close!))} i/close]]))

(def confirm-dialog
  (mx/component
   {:render confirm-dialog-render
    :name "confirm-dialog"
    :mixins []}))

(defmethod lightbox/render-lightbox :confirm
  [_]
  (confirm-dialog))
