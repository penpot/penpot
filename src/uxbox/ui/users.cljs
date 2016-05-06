(ns uxbox.ui.users
  (:require [sablono.core :as html :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.auth :as da]
            [uxbox.data.lightbox :as udl]
            [uxbox.ui.icons :as i]
            [uxbox.ui.navigation :as nav]
            [uxbox.ui.mixins :as mx]))

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

(def ^:static profile-l
  (as-> (l/key :profile) $
    (l/focus-atom $ s/state)))

(defn user-render
  [own]
  (let [profile (rum/react profile-l)
        local (:rum/local own)]
    (html
     [:div.user-zone {:on-mouse-enter #(swap! local assoc :open true)
                      :on-mouse-leave #(swap! local assoc :open false)}
      [:span (:fullname profile)]
      [:img {:border "0"
             :src (:photo profile "/images/avatar.jpg")}]
      (user-menu (:open @local))])))

(def user
  (mx/component
   {:render user-render
    :name "user"
    :mixins [rum/reactive (rum/local {:open false})]}))
