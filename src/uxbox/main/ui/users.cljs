;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.users
  (:require [sablono.core :as html :refer-macros [html]]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as s]
            [uxbox.main.data.auth :as da]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.navigation :as nav]
            [uxbox.util.mixins :as mx]))

;; --- User Menu

(defn menu-render
  [own open?]
  (let [open-settings-dialog #(udl/open! :settings)]
    (html
     [:ul.dropdown {:class (when-not open?
                             "hide")}
      [:li
       i/page
       [:span "Page settings"]]
      [:li {:on-click open-settings-dialog}
       i/grid
       [:span "Grid settings"]]
      [:li
       i/eye
       [:span "Preview"]]
      [:li {:on-click #(r/go :settings/profile)}
       i/user
       [:span "Your account"]]
      [:li {:on-click #(rs/emit! (da/logout))}
       i/exit
       [:span "Exit"]]])))

(def user-menu
  (mx/component
   {:render menu-render
    :name "user-menu"
    :mixins []}))

;; --- User Widget

(def profile-l
  (as-> (l/key :profile) $
    (l/derive $ s/state)))

(defn user-render
  [own]
  (let [profile (rum/react profile-l)
        local (:rum/local own)
        photo (if (str/empty? (:photo profile ""))
                "/images/avatar.jpg"
                (:photo profile))]
    (html
     [:div.user-zone {:on-mouse-enter #(swap! local assoc :open true)
                      :on-mouse-leave #(swap! local assoc :open false)}
      [:span (:fullname profile)]
      [:img {:src photo}]
      (user-menu (:open @local))])))

(def user
  (mx/component
   {:render user-render
    :name "user"
    :mixins [mx/reactive (rum/local {:open false})]}))
