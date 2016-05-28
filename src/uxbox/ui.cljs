;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [promesa.core :as p]
            [beicon.core :as rx]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.locales :refer (tr)]
            [uxbox.data.projects :as dp]
            [uxbox.data.users :as udu]
            [uxbox.data.auth :as dauth]
            [uxbox.data.messages :as dmsg]
            [uxbox.ui.icons :as i]
            [uxbox.ui.lightbox :as lightbox]
            [uxbox.ui.auth :as auth]
            [uxbox.ui.dashboard :as dashboard]
            [uxbox.ui.settings :as settings]
            [uxbox.ui.workspace :refer (workspace)]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.shapes]))

;; --- Constants

(def ^:const +unrestricted+ #{:auth/login :auth/register})
(def ^:const restricted? (complement +unrestricted+))

(def route-l
  (as-> (l/key :route) $
    (l/focus-atom $ st/state)))

;; --- Error Handling

(defn- on-error
  "A default error handler."
  [error]
  (cond
    ;; Unauthorized or Auth timeout
    (and (:status error)
         (:payload error)
         (or (= (:status error) 403)
             (= (:status error) 419)))
    (rs/emit! (dauth/logout))

    ;; Network error
    (= (:status error) 0)
    (do
      (dmsg/error! (tr "errors.network"))
      (js/console.error "Stack:" (.-stack error)))

    ;; Something else
    :else
    (do
      (dmsg/error! (tr "errors.generic"))
      (js/console.error "Stack:" (.-stack error)))))

(rs/add-error-watcher :ui on-error)

;; --- Main App (Component)

(defn app-render
  [own]
  (let [route (rum/react route-l)
        auth (rum/react st/auth-l)
        location (:id route)
        params (:params route)]
    (if (and (restricted? location) (not auth))
      (do (p/schedule 0 #(r/go :auth/login)) nil)
      (case location
        :auth/login (auth/login-page)
        :auth/register (auth/register-page)
        :dashboard/projects (dashboard/projects-page)
        :dashboard/elements (dashboard/elements-page)
        :dashboard/icons (dashboard/icons-page)
        :dashboard/images (dashboard/images-page)
        :dashboard/colors (dashboard/colors-page)
        :settings/profile (settings/profile-page)
        :settings/password (settings/password-page)
        :settings/notifications (settings/notifications-page)
        :workspace/page (let [projectid (:project-uuid params)
                              pageid (:page-uuid params)]
                          (workspace projectid pageid))
        nil
        ))))

(defn app-will-mount
  [own]
  (when @st/auth-l
    (rs/emit! (udu/fetch-profile)))
  own)

(def app
  (mx/component
   {:render app-render
    :will-mount app-will-mount
    :mixins [rum/reactive]
    :name "app"}))

;; --- Loader

(defn loader-render
  [own]
  (when (rum/react st/loader)
    (html
     [:div.loader-content i/loader])))

(def loader
  (mx/component
   {:render loader-render
    :name "loader"
    :mixins [rum/reactive mx/static]}))

;; --- Main Entry Point

(defn init
  []
  (let [app-dom (gdom/getElement "app")
        lightbox-dom (gdom/getElement "lightbox")
        loader-dom (gdom/getElement "loader")]
    (rum/mount (app) app-dom)
    (rum/mount (lightbox/lightbox) lightbox-dom)
    (rum/mount (loader) loader-dom)))
