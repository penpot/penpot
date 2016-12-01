;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.header
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.users :refer (user)]
            [uxbox.util.mixins :as mx :include-macros true]))

(def ^:private section-ref
  (-> (l/in [:route :id])
      (l/derive st/state)))

(defn- header-link
  [section content]
  (let [link (r/route-for section)]
    (html
     [:a {:href (str "/#" link)} content])))

(defn header-render
  [own]
  (let [section (mx/react section-ref)
        profile? (= section :settings/profile)
        password? (= section :settings/password)
        notifications? (= section :settings/notifications)]
    (html
     [:header#main-bar.main-bar
      [:div.main-logo
       (header-link :dashboard/projects i/logo)]
      [:ul.main-nav
       [:li {:class (when profile? "current")}
        (header-link :settings/profile (tr "settings.profile"))]
       [:li {:class (when password? "current")}
        (header-link :settings/password (tr "settings.password"))]
       [:li {:class (when notifications? "current")}
        (header-link :settings/notifications (tr "settings.notifications"))]]
      (user)])))

(def header
  (mx/component
   {:render header-render
    :name "header"
    :mixins [rum/static
             mx/reactive]}))
