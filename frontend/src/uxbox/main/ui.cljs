;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [bide.core :as bc]
            [uxbox.store :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.users :as udu]
            [uxbox.main.data.auth :refer [logout]]
            [uxbox.main.data.messages :as dmsg]
            [uxbox.main.ui.loader :refer (loader)]
            [uxbox.main.ui.lightbox :refer (lightbox)]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.auth :as auth]
            [uxbox.main.ui.dashboard :as dashboard]
            [uxbox.main.ui.settings :as settings]
            [uxbox.main.ui.workspace :refer (workspace)]
            [uxbox.util.timers :as ts]
            [uxbox.util.router :as rt]
            [potok.core :as ptk]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.data :refer (parse-int uuid-str?)]
            [uxbox.util.dom :as dom]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.shapes]))

;; --- Constants

(def +unrestricted+
  #{:auth/login
    :auth/register
    :auth/recovery-request
    :auth/recovery})

(def restricted?
  (complement +unrestricted+))

(def route-ref
  (-> (l/key :route)
      (l/derive st/state)))

;; --- Error Handling

(defn- on-error
  "A default error handler."
  [{:keys [status] :as error}]
  (js/console.log "on-error:" (pr-str error))
  (reset! st/loader false)
  (cond
    ;; Unauthorized or Auth timeout
    (and (:status error)
         (or (= (:status error) 403)
             (= (:status error) 419)))
    (st/emit! (logout))

    ;; Conflict
    (= status 412)
    (dmsg/error! (tr "errors.conflict"))

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

(set! st/*on-error* on-error)

;; --- Main App (Component)

(defn app-will-mount
  [own]
  (when @st/auth-ref
    (st/emit! (udu/fetch-profile)))
  own)

(mx/defc app
  {:will-mount app-will-mount
   :mixins [mx/reactive]}
  []
  (let [route (mx/react route-ref)
        auth (mx/react st/auth-ref)
        location (:id route)
        params (:params route)]
    (if (and (restricted? location) (not auth))
      (do (ts/schedule 0 #(rt/go :auth/login)) nil)
      (case location
        :auth/login (auth/login-page)
        :auth/register (auth/register-page)
        :auth/recovery-request (auth/recovery-request-page)
        :auth/recovery (auth/recovery-page (:token params))
        :dashboard/projects (dashboard/projects-page)
        ;; :dashboard/elements (dashboard/elements-page)
        :dashboard/icons (let [{:keys [id type]} params
                                type (when (str/alpha? type) (keyword type))
                                id (cond
                                     (str/digits? id) (parse-int id)
                                     (uuid-str? id) (uuid id)
                                     :else nil)]
                           (dashboard/icons-page type id))

        :dashboard/images (let [{:keys [id type]} params
                                type (when (str/alpha? type) (keyword type))
                                id (cond
                                     (str/digits? id) (parse-int id)
                                     (uuid-str? id) (uuid id)
                                     :else nil)]
                            (dashboard/images-page type id))

        :dashboard/colors (let [{:keys [id type]} params
                                type (when (str/alpha? type) (keyword type))
                                id (cond
                                     (str/digits? id) (parse-int id)
                                     (uuid-str? id) (uuid id)
                                     :else nil)]
                            (dashboard/colors-page type id))
        :settings/profile (settings/profile-page)
        :settings/password (settings/password-page)
        :settings/notifications (settings/notifications-page)
        :workspace/page (let [projectid (uuid (:project params))
                              pageid (uuid (:page params))]
                          (workspace projectid pageid))
        nil
        ))))

;; --- Routes

(def routes
  [["/auth/login" :auth/login]
   ["/auth/register" :auth/register]
   ["/auth/recovery/request" :auth/recovery-request]
   ["/auth/recovery/token/:token" :auth/recovery]
   ["/settings/profile" :settings/profile]
   ["/settings/password" :settings/password]
   ["/settings/notifications" :settings/notifications]
   ["/dashboard/projects" :dashboard/projects]
   ["/dashboard/elements" :dashboard/elements]

   ["/dashboard/icons" :dashboard/icons]
   ["/dashboard/icons/:type/:id" :dashboard/icons]
   ["/dashboard/icons/:type" :dashboard/icons]

   ["/dashboard/images" :dashboard/images]
   ["/dashboard/images/:type/:id" :dashboard/images]
   ["/dashboard/images/:type" :dashboard/images]

   ["/dashboard/colors" :dashboard/colors]
   ["/dashboard/colors/:type/:id" :dashboard/colors]
   ["/dashboard/colors/:type" :dashboard/colors]

   ["/workspace/:project/:page" :workspace/page]])

;; --- Main Entry Point

(defn init
  []
  (rt/init routes))
  (mx/mount (app) (dom/get-element "app"))
  (mx/mount (lightbox) (dom/get-element "lightbox"))
  (mx/mount (loader) (dom/get-element "loader")))
