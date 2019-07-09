;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.core :as mx :include-macros true]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.auth :as auth]
   [uxbox.main.ui.dashboard :as dashboard]
   [uxbox.main.ui.lightbox :refer [lightbox]]
   [uxbox.main.ui.loader :refer [loader]]
   [uxbox.main.ui.settings :as settings]
   [uxbox.main.ui.shapes]
   [uxbox.main.ui.workspace :refer [workspace]]
   [uxbox.util.data :refer [parse-int uuid-str?]]
   [uxbox.util.dom :as dom]
   [uxbox.util.html.history :as html-history]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.util.timers :as ts]))

;; --- Refs

(def route-ref
  (-> (l/key :route)
      (l/derive st/state)))

;; --- Routes

(def routes
  [["/auth"
    ["/login" :auth/login]
    ["/register" :auth/register]
    ["/recovery/request" :auth/recovery-request]
    ["/recovery/token/:token" :auth/recovery]]
   ["/settings"
    ["/profile" :settings/profile]
    ["/password" :settings/password]
    ["/notifications" :settings/notifications]]
   ["/dashboard"
    ["/projects" :dashboard/projects]
    ["/elements" :dashboard/elements]
    ["/icons" :dashboard/icons]
    ["/images" :dashboard/images]
    ["/colors" :dashboard/colors]]
   ["/workspace/:project/:page" :workspace/page]])

;; --- Main App (Component)

(mx/defc app
  {:mixins [mx/reactive]}
  []
  (let [route (mx/react route-ref)]
    (case (get-in route [:data :name])
      :auth/login (auth/login-page)
      :auth/register (auth/register-page)
      :auth/recovery-request (auth/recovery-request-page)

      :auth/recovery
      (let [token (get-in route [:params :path :token])]
        (auth/recovery-page token))

      :dashboard/projects (dashboard/projects-page)
      :settings/profile (settings/profile-page)
      :settings/password (settings/password-page)
      :settings/notifications (settings/notifications-page)
      ;; ;; :dashboard/elements (dashboard/elements-page)

      :dashboard/icons
      (let [{:keys [id type]} (get-in route [:params :query])
            id (cond
                 (str/digits? id) (parse-int id)
                 (uuid-str? id) (uuid id)
                 :else nil)
            type (when (str/alpha? type) (keyword type))]
        (dashboard/icons-page type id))

      :dashboard/images
      (let [{:keys [id type]} (get-in route [:params :query])
            id (cond
                 (str/digits? id) (parse-int id)
                 (uuid-str? id) (uuid id)
                 :else nil)
            type (when (str/alpha? type) (keyword type))]
        (dashboard/images-page type id))

      :dashboard/colors
      (let [{:keys [id type]} (get-in route [:params :query])
            type (when (str/alpha? type) (keyword type))
            id (cond
                 (str/digits? id) (parse-int id)
                 (uuid-str? id) (uuid id)
                 :else nil)]
        (dashboard/colors-page type id))

      :workspace/page
      (let [projectid (uuid (get-in route [:params :path :project]))
            pageid (uuid (get-in route [:params :path :page]))]
        (workspace projectid pageid))

      nil
      )))
