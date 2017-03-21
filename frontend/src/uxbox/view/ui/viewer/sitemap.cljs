;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.sitemap
  (:require [sablono.core :refer-macros (html)]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.data :refer [parse-int]]
            [uxbox.view.data.viewer :as dv]
            [uxbox.view.store :as st]))

;; --- Refs

(def project-name-ref
  (-> (l/in [:project :name])
      (l/derive st/state)))

;; --- Component

(mx/defc sitemap
  {:mixins [mx/static mx/reactive]}
  [pages selected]
  (let [project-name (mx/react project-name-ref)
        on-click #(st/emit! (dv/select-page %))]
    [:div.view-sitemap
     [:span.sitemap-title project-name]
     [:ul.sitemap-list
      (for [[i page] (map-indexed vector pages)
            :let [selected? (= i selected)]]
        [:li {:class (when selected? "selected")
              :on-click (partial on-click i)
              :key (str i)}
         [:div.page-icon i/page]
         [:span (:name page)]])]]))
