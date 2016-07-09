;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.nav
  (:require [sablono.core :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.util.mixins :as mx]
            [uxbox.util.rstore :as rs]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.state :as st]
            [uxbox.view.data.viewer :as dv]))

(defn nav-render
  [own flags]
  (let [toggle #(rs/emit! (dv/toggle-flag :sitemap))
        sitemap? (contains? flags :sitemap)]
    (html
     [:div.view-nav
      [:ul.view-options-btn
       [:li.tooltip.tooltip-right
        {:alt "sitemap"
         :class (when sitemap? "selected")
         :on-click toggle}
        i/project-tree]
       [:li.tooltip.tooltip-right
        {:alt "view interactions"}
        i/action]
       [:li.tooltip.tooltip-right
        {:alt "share"} i/export]
       [:li.tooltip.tooltip-right
        {:alt "save SVG"} i/save]]])))

(def nav
  (mx/component
   {:render nav-render
    :name "nav"
    :mixins [mx/static mx/reactive]}))
