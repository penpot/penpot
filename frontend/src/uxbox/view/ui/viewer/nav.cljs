;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.nav
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.view.data.viewer :as dv]
   [uxbox.view.store :as st]))

(mf/defc nav
  [{:keys [flags] :as props}]
  (let [toggle-sitemap #(st/emit! (dv/toggle-flag :sitemap))
        toggle-interactions #(st/emit! (dv/toggle-flag :interactions))
        sitemap? (contains? flags :sitemap)
        interactions? (contains? flags :interactions)
        on-download #(udl/open! :download)]
    [:div.view-nav
     [:ul.view-options-btn
      [:li.tooltip.tooltip-right
       {:alt (tr "viewer.sitemap")
        :class (when sitemap? "selected")
        :on-click toggle-sitemap}
       i/project-tree]
       [:li.tooltip.tooltip-right
        {:alt (tr "viewer.interactions")
         :class (when interactions? "selected")
         :on-click toggle-interactions}
        i/action]
      [:li.tooltip.tooltip-right
       {:alt (tr "viewer.share")
        :class "disabled"
        :disabled true} i/export]
      [:li.tooltip.tooltip-right
       {:alt (tr "viewer.save")
        :on-click on-download}
       i/save]]]))
