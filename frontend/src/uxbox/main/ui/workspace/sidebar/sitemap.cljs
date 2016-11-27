;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.sitemap
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.sidebar.sitemap-pageform]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.dom :as dom]))

;; --- Refs

(defn- resolve-pages
  [state]
  (let [project (get-in state [:workspace :project])]
    (->> (vals (:pages state))
         (filter #(= project (:project %)))
         (sort-by :created-at))))

(def pages-ref
  (-> (l/lens resolve-pages)
      (l/derive st/state)))

;; --- Component

(mx/defc page-item
  {:mixins [(mx/local) mx/static mx/reactive]}
  [page total active?]
  (letfn [(on-edit [event]
            (udl/open! :page-form {:page page}))

          (on-navigate [event]
            (st/emit! (dp/go-to (:project page) (:id page))))

          (delete []
            (let [next #(st/emit! (dp/go-to (:project page)))]
              (st/emit! (udp/delete-page (:id page) next))))

          (on-delete [event]
            (dom/prevent-default event)
            (dom/stop-propagation event)
            (udl/open! :confirm {:on-accept delete}))]
    [:li {:class (when active? "selected")
          :on-click on-navigate}
     [:div.page-icon i/page]
     [:span (:name page)]
     [:div.page-actions
      [:a {:on-click on-edit} i/pencil]
      (if (> total 1)
        [:a {:on-click on-delete} i/trash])]]))

(mx/defc sitemap-toolbox
  {:mixins [mx/static mx/reactive]}
  []
  (let [project (mx/react wb/project-ref)
        pages (mx/react pages-ref)
        current (mx/react wb/page-ref)
        create #(udl/open! :page-form {:page {:project (:id project)}})
        close #(st/emit! (dw/toggle-flag :sitemap))]
    [:div.sitemap.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/project-tree]
      [:span (tr "ds.sitemap")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:div.project-title
       [:span (:name project)]
       [:div.add-page {:on-click create} i/close]]
      [:ul.element-list
       (for [page pages
             :let [active? (= (:id page) (:id current))]]
         (-> (page-item page (count pages) active?)
             (mx/with-key (:id page))))]]]))
