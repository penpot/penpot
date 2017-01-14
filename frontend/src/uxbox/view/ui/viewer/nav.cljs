;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.nav
  (:require [uxbox.util.mixins :as mx :include-macros true]
            [potok.core :as ptk]
            [uxbox.view.store :as st]
            [uxbox.builtins.icons :as i]
            [uxbox.view.data.viewer :as dv]))

(mx/defc nav
  [flags]
  (let [toggle-sitemap #(st/emit! (dv/toggle-flag :sitemap))
        toggle-interactions #(st/emit! (dv/toggle-flag :interactions))
        sitemap? (contains? flags :sitemap)
        interactions? (contains? flags :interactions)]
    [:div.view-nav
     [:ul.view-options-btn
      [:li.tooltip.tooltip-right
       {:alt "sitemap"
        :class (when sitemap? "selected")
        :on-click toggle-sitemap}
       i/project-tree]
       [:li.tooltip.tooltip-right
        {:alt "view interactions"
         :class (when interactions? "selected")
         :on-click toggle-interactions}
        i/action]
      [:li.tooltip.tooltip-right
       {:alt "share"} i/export]
      [:li.tooltip.tooltip-right
       {:alt "save SVG"} i/save]]]))
