;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.header
  (:require [lentes.core :as l]
            [uxbox.main.store :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.users :as ui.u]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.mixins :as mx :include-macros true]
            [potok.core :as ptk]))

(def header-ref
  (-> (l/key :dashboard)
      (l/derive st/state)))

(mx/defc header-link
  [section content]
  (let [link (r/route-for section)]
    [:a {:href (str "/#" link)} content]))

(mx/defc header
  {:mixins [mx/static mx/reactive]}
  []
  (let [local (mx/react header-ref)
        projects? (= (:section local) :dashboard/projects)
        elements? (= (:section local) :dashboard/elements)
        icons? (= (:section local) :dashboard/icons)
        images? (= (:section local) :dashboard/images)
        colors? (= (:section local) :dashboard/colors)]
    [:header#main-bar.main-bar
     [:div.main-logo
      (header-link :dashboard/projects i/logo)]
     [:ul.main-nav
      [:li {:class (when projects? "current")}
       (header-link :dashboard/projects (tr "ds.projects"))]
      #_[:li {:class (when elements? "current")}
       (header-link :dashboard/elements (tr "ds.elements"))]
      [:li {:class (when icons? "current")}
       (header-link :dashboard/icons (tr "ds.icons"))]
      [:li {:class (when images? "current")}
       (header-link :dashboard/images (tr "ds.images"))]
      [:li {:class (when colors? "current")}
       (header-link :dashboard/colors (tr "ds.colors"))]]
     (ui.u/user)]))


