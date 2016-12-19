;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.users
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.auth :as da]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- User Menu

(mx/defc user-menu
  {:mixins [mx/static]}
  [open?]
  [:ul.dropdown {:class (when-not open? "hide")}
   [:li {:on-click #(r/go :settings/profile)}
    i/user
    [:span "Profile"]]
   [:li {:on-click #(r/go :settings/password)}
    i/lock
    [:span "Password"]]
   [:li {:on-click #(r/go :settings/notifications)}
    i/mail
    [:span "Notifications"]]
   [:li {:on-click #(st/emit! (da/logout))}
    i/exit
    [:span "Exit"]]])

;; --- User Widget

(def profile-ref
  (as-> (l/key :profile) $
    (l/derive $ st/state)))

(mx/defcs user
  {:mixins [mx/static mx/reactive (mx/local {:open false})]}
  [own]
  (let [profile (mx/react profile-ref)
        local (:rum/local own)
        photo (if (str/empty? (:photo profile ""))
                "/images/avatar.jpg"
                (:photo profile))]
    [:div.user-zone {:on-mouse-enter #(swap! local assoc :open true)
                     :on-mouse-leave #(swap! local assoc :open false)}
     [:span (:fullname profile)]
     [:img {:src photo}]
     (user-menu (:open @local))]))
