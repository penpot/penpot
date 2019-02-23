;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.header
  (:require [potok.core :as ptk]
            [lentes.core :as l]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.router :as r]
            [uxbox.main.store :as st]
            [uxbox.main.data.auth :as da]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.users :refer [user]]
            [rumext.core :as mx :include-macros true]))

(def ^:private section-ref
  (-> (l/in [:route :id])
      (l/derive st/state)))

(mx/defc header-link
  [section content]
  (let [link (r/route-for section)]
    [:a {:href (str "/#" link)} content]))

(mx/defc header
  {:mixins [mx/static mx/reactive]}
  []
  (let [section (mx/react section-ref)
        profile? (= section :settings/profile)
        password? (= section :settings/password)
        notifications? (= section :settings/notifications)]
    [:header#main-bar.main-bar
     [:div.main-logo
      (header-link :dashboard/projects i/logo)]
     [:ul.main-nav
      [:li {:class (when profile? "current")}
       (header-link :settings/profile (tr "settings.profile"))]
      [:li {:class (when password? "current")}
       (header-link :settings/password (tr "settings.password"))]
      [:li {:class (when notifications? "current")}
       (header-link :settings/notifications (tr "settings.notifications"))]
      [:li {:on-click #(st/emit! (da/logout))}
       (header-link :auth/login (tr "settings.exit"))]]
     (user)]))

