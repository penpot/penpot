(ns uxbox.ui.settings
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.data.dashboard :as dd]
            [uxbox.ui.dashboard.header :refer (header)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: Profile
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn profile-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     [:ul
      [:li {:on-click #(r/go :settings/profile)} "Profile"]
      [:li {:on-click #(r/go :settings/password)} "Password"]
      [:li {:on-click #(r/go :settings/notifications)} "Notifications"]]

     [:section.dashboard-grid.library
      [:span "TODO 1"]]]]))

(def ^:static profile-page
  (mx/component
   {:render profile-page-render
    :name "profile-page"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: password
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn password-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     [:ul
      [:li {:on-click #(r/go :settings/profile)} "Profile"]
      [:li {:on-click #(r/go :settings/password)} "Password"]
      [:li {:on-click #(r/go :settings/notifications)} "Notifications"]]
     [:section.dashboard-grid.library
      [:span "TODO 2"]]]]))

(def ^:static password-page
  (mx/component
   {:render password-page-render
    :name "password-page"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Page: notifications
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn notifications-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    [:section.dashboard-content
     [:ul
      [:li {:on-click #(r/go :settings/profile)} "Profile"]
      [:li {:on-click #(r/go :settings/password)} "Password"]
      [:li {:on-click #(r/go :settings/notifications)} "Notifications"]]
     [:section.dashboard-grid.library
      [:span "TODO 3"]]]]))

(def ^:static notifications-page
  (mx/component
   {:render notifications-page-render
    :name "notifications-page"
    :mixins [mx/static]}))
