;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.viewer.sitemap
  (:require
   [lentes.core :as l]
   [rumext.core :as mx :include-macros true]
   [uxbox.builtins.icons :as i]
   [uxbox.view.data.viewer :as dv]
   [uxbox.view.store :as st]))

(mx/defc sitemap
  {:mixins [mx/static mx/reactive]}
  [project pages selected]
  (let [project-name (:name project)
        on-click #(st/emit! (dv/select-page %))]
    [:div.view-sitemap
     [:span.sitemap-title project-name]
     [:ul.sitemap-list
      (for [[i page] (map-indexed vector pages)
            :let [selected? (= i selected)
                  id (:id page)]]
        [:li {:class (when selected? "selected")
              :on-click (partial on-click i)
              :id (str "page-" id)
              :key (str i)}
         [:div.page-icon i/page]
         [:span (:name page)]])]]))
