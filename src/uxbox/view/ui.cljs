;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui
  (:require [sablono.core :refer-macros [html]]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [uxbox.common.i18n :refer (tr)]
            ;; [uxbox.view.ui.loader :refer (loader)]
            ;; [uxbox.view.ui.lightbox :refer (lightbox)]
            [uxbox.main.ui.icons :as i]
            [uxbox.common.ui.mixins :as mx]))

;; --- Main App (Component)

(defn app-render
  [own]
  (html
   [:section.view-content
    [:div.view-nav
     [:ul.view-options-btn
      [:li.tooltip.tooltip-right
        {:alt "sitemap"} i/project-tree]
      [:li.tooltip.tooltip-right
        {:alt "view interactions"} i/action]
      [:li.tooltip.tooltip-right
        {:alt "share"} i/export]
      [:li.tooltip.tooltip-right
        {:alt "save SVG"} i/save]]]
    [:div.view-canvas "VIEW CONTENT"]
   ]
  ))

(def app
  (mx/component
   {:render app-render
    :mixins [mx/static]
    :name "app"}))

;; --- Main Entry Point

(defn init
  []
  (let [app-dom (gdom/getElement "app")
        lightbox-dom (gdom/getElement "lightbox")
        loader-dom (gdom/getElement "loader")]
    (rum/mount (app) app-dom)
    #_(rum/mount (lightbox) lightbox-dom)
    #_(rum/mount (loader) loader-dom)))
