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
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.forms :as udf]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.forms :as forms]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :as uucp]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int)]))


(defn- download-dialog-render
  [own]
  (html
   [:div.lightbox-body.export-dialog
    [:h3 "Export options"]
    [:div.row-flex
     [:div.content-col
      [:span.icon i/trash]
      [:span.title "Export page"]
      [:p.info "Get a single page of your project in SVG."]
      [:select.input-select
       [:option "Choose a page"]
       [:option "Page 001"]
       [:option "Page 002"]
       [:option "Page 003"]]
      [:a.btn-primary {:href "#"} "Export page"]]
     [:div.content-col
      [:span.icon i/trash]
      [:span.title "Export project"]
      [:p.info "Get the whole project as a ZIP file."]
      [:a.btn-primary {:href "#"} "Expor project"]]
     [:div.content-col
      [:span.icon i/trash]
      [:span.title "Export as HTML"]
      [:p.info "Get your project as HTML."]
      [:a.btn-primary {:href "#"} "Export HTML"]]]
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
