;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.users
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.core :as mx :include-macros true]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.store :as st]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as rt]))

;; --- User Menu

(mx/defc user-menu
  {:mixins [mx/static]}
  [open?]
  [:ul.dropdown {:class (when-not open? "hide")}
   [:li {:on-click #(st/emit! (rt/navigate :settings/profile))}
    i/user
    [:span (tr "ds.user.profile")]]
   [:li {:on-click #(st/emit! (rt/navigate :settings/password))}
    i/lock
    [:span (tr "ds.user.password")]]
   [:li {:on-click #(st/emit! (rt/navigate :settings/notifications))}
    i/mail
    [:span (tr "ds.user.notifications")]]
   [:li {:on-click #(st/emit! (da/logout))}
    i/exit
    [:span (tr "ds.user.exit")]]])

;; --- User Widget

(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(mx/defcs user
  {:mixins [mx/static mx/reactive (mx/local {:open false})]}
  [own]
  (let [profile (mx/react profile-ref)
        local (::mx/local own)
        photo (if (str/empty? (:photo profile ""))
                "/images/avatar.jpg"
                (:photo profile))]
    [:div.user-zone {:on-click #(st/emit! (rt/navigate :settings/profile))
                     :on-mouse-enter #(swap! local assoc :open true)
                     :on-mouse-leave #(swap! local assoc :open false)}
     [:span (:fullname profile)]
     [:img {:src photo}]
     (user-menu (:open @local))]))
