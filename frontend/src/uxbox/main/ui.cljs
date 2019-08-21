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
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.store :as st]
   [uxbox.main.ui.auth :as auth]
   [uxbox.main.ui.dashboard :as dashboard]
   [uxbox.main.ui.settings :as settings]
   [uxbox.main.ui.shapes]
   [uxbox.main.ui.workspace :refer [workspace-page]]
   [uxbox.util.data :refer [parse-int uuid-str?]]
   [uxbox.util.dom :as dom]
   [uxbox.util.html.history :as html-history]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.util.timers :as ts]))

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

;; --- Error Handling

(defn- on-error
  "A default error handler."
  [{:keys [status] :as error}]
  (js/console.error "Unhandled Error:"
                    "\n - message:" (ex-message error)
                    "\n - data:" (pr-str (ex-data error))
                    "\n - stack:" (.-stack error))
  (reset! st/loader false)
  (cond
    ;; Unauthorized or Auth timeout
    (and (:status error)
         (or (= (:status error) 403)
             (= (:status error) 419)))

    (ts/schedule 0 #(st/emit! (rt/nav :auth/login)))

    ;; Conflict
    (= status 412)
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.conflict"))))

    ;; Network error
    (= (:status error) 0)
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.network"))))

    ;; Something else
    :else
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.generic"))))))

(set! st/*on-error* on-error)

;; --- Main App (Component)

(def route-iref
  (-> (l/key :route)
      (l/derive st/state)))

(mf/defc app
  [props]
  (let [route (mf/deref route-iref)
        route-id (get-in route [:data :name])]
    (case route-id
      :auth/login (mf/element auth/login-page)
      :auth/register (auth/register-page)
      :auth/recovery-request (auth/recovery-request-page)

      :auth/recovery
      (let [token (get-in route [:params :path :token])]
        (auth/recovery-page token))

      (:settings/profile
       :settings/password
       :settings/notifications)
      (mf/element settings/settings #js {:route route})

      (:dashboard/projects
       :dashboard/icons
       :dashboard/images
       :dashboard/colors)
      (mf/element dashboard/dashboard #js {:route route})

      :workspace/page
      (let [project-id (uuid (get-in route [:params :path :project]))
            page-id (uuid (get-in route [:params :path :page]))]
        [:& workspace-page {:project-id project-id
                            :page-id page-id
                            :key page-id}])

      nil)))

