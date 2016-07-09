;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.sitemap
  (:require [sablono.core :refer-macros (html)]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.mixins :as mx]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.ui.icons :as i]
            [uxbox.view.data.viewer :as dv]))


;; --- Refs

(def pages-ref
  (-> (l/key :pages)
      (l/derive st/state)))

(def project-name-ref
  (-> (l/in [:project :name])
      (l/derive st/state)))

(def selected-ref
  (-> (comp (l/in [:route :params :id])
            (l/lens #(parse-int % 0)))
      (l/derive st/state)))

;; --- Component

(defn- sitemap-render
  [own]
  (let [project-name (rum/react project-name-ref)
        pages (rum/react pages-ref)
        selected (rum/react selected-ref)
        on-click #(rs/emit! (dv/select-page %))]
    (html
     [:div.view-sitemap
      [:span.sitemap-title project-name]
      [:ul.sitemap-list
       (for [[i page] (map-indexed vector pages)
             :let [selected? (= i selected)]]
         [:li {:class (when selected? "selected")
               :on-click (partial on-click i)
               :key (str i)}
          [:div.page-icon i/page]
          [:span (:name page)]])]])))

(def sitemap
  (mx/component
   {:render sitemap-render
    :name "sitemap"
    :mixins [mx/static mx/reactive]}))


