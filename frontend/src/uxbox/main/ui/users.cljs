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
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.store :as st]
   [uxbox.main.ui.navigation :as nav]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as rt]))

;; --- User Menu

(mf/defc user-menu
  [props]
  (letfn [(on-click [event section]
            (dom/stop-propagation event)
            (if (keyword? section)
              (st/emit! (rt/nav section))
              (st/emit! section)))]
    [:ul.dropdown
     [:li {:on-click #(on-click % :settings/profile)}
      i/user
      [:span (tr "ds.user.profile")]]
     [:li {:on-click #(on-click % :settings/password)}
      i/lock
      [:span (tr "ds.user.password")]]
     [:li {:on-click #(on-click % :settings/notifications)}
      i/mail
      [:span (tr "ds.user.notifications")]]
     [:li {:on-click #(on-click % da/logout)}
      i/exit
      [:span (tr "ds.user.exit")]]]))

;; --- User Widget

(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(mf/defc user
  [_]
  (let [open (mf/use-state false)
        profile (mf/deref profile-ref)
        photo (if (str/empty? (:photo profile ""))
                "/images/avatar.jpg"
                (:photo profile))]
    [:div.user-zone {:on-click #(st/emit! (rt/navigate :settings/profile))
                     :on-mouse-enter #(reset! open true)
                     :on-mouse-leave #(reset! open false)}
     [:span (:fullname profile)]
     [:img {:src photo}]
     (when @open
       [:& user-menu])]))
