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
   [rumext.core :as mx]
   [rumext.alpha :as mf]
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
  (js/console.error "on-error:" (pr-str error))
  (js/console.error (.-stack error))
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

(mf/def app
  :mixins [mx/reactive]

  :init
  (fn [own props]
    (assoc own ::route-ref (l/derive (l/key :route) st/state)))

  :render
  (fn [own props]
    (let [route (mx/react (::route-ref own))
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
        (mf/element settings/settings {:route route})

        (:dashboard/projects
         :dashboard/icons
         :dashboard/images
         :dashboard/colors)
        (mf/element dashboard/dashboard {:route route})

        :workspace/page
        (let [project (uuid (get-in route [:params :path :project]))
              page (uuid (get-in route [:params :path :page]))]
          [:& workspace {:project project :page page}])

        nil
        ))))
