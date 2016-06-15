;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.download
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.main.constants :as c]
            [uxbox.main.state :as st]
            [uxbox.common.rstore :as rs]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.forms :as udf]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.forms :as forms]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :as uucp]
            [uxbox.main.ui.workspace.base :as wb]
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
