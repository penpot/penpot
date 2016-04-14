;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.dashboard.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.icons :as i]
            [uxbox.ui.users :as ui.u]
            [uxbox.ui.mixins :as mx]))

(def ^:static header-l
  (as-> (l/in [:dashboard]) $
    (l/focus-atom $ s/state)))

(defn- header-link
  [section content]
  (let [link (r/route-for section)]
    (html
     [:a {:href (str "/#" link)} content])))

(defn header-render
  [own]
  (let [local (rum/react header-l)
        projects? (= (:section local) :dashboard/projects)
        elements? (= (:section local) :dashboard/elements)
        icons? (= (:section local) :dashboard/icons)
        colors? (= (:section local) :dashboard/colors)]
    (html
     [:header#main-bar.main-bar
      [:div.main-logo
       (header-link :dashboard/projects i/logo)]
      [:ul.main-nav
       [:li {:class (when projects? "current")}
        (header-link :dashboard/projects (tr "ds.projects"))]
       [:li {:class (when elements? "current")}
        (header-link :dashboard/elements (tr "ds.elements"))]
       [:li {:class (when icons? "current")}
        (header-link :dashboard/icons (tr "ds.icons"))]
       [:li {:class (when colors? "current")}
        (header-link :dashboard/colors (tr "ds.colors"))]]
      (ui.u/user)])))

(def ^:static header
  (mx/component
   {:render header-render
    :name "header"
    :mixins [rum/static
             rum/reactive]}))
