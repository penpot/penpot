;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.download
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.constants :as c]
            [uxbox.state :as st]
            [uxbox.rstore :as rs]
            [uxbox.data.pages :as udp]
            [uxbox.data.forms :as udf]
            [uxbox.data.workspace :as udw]
            [uxbox.data.lightbox :as udl]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.forms :as forms]
            [uxbox.ui.lightbox :as lbx]
            [uxbox.ui.colorpicker :as uucp]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]))


(defn- download-dialog-render
  [own]
  (html
   [:div.lightbox-body.settings
    [:h3 "Download Dialog"]
    [:p "Content here"]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (udl/close!))} i/close]]))

(def download-dialog
  (mx/component
   {:render download-dialog-render
    :name "download-dialog"
    :mixins []}))

(defmethod lbx/render-lightbox :download
  [_]
  (download-dialog))
