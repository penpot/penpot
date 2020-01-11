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
   [uxbox.main.ui.workspace :as workspace]
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
    ["/projects" :dashboard-projects]
    ["/icons" :dashboard-icons]
    ["/images" :dashboard-images]
    ["/colors" :dashboard-colors]]

   ["/workspace/:file-id" :workspace]])

;; --- Error Handling

(defn- on-error
  "A default error handler."
  [{:keys [type code] :as error}]
  (reset! st/loader false)
  (cond
    (and (map? error)
         (= :validation type)
         (= :spec-validation code))
    (do
      (println "============ SERVER RESPONSE ERROR ================")
      (println (:explain error))
      (println "============ END SERVER RESPONSE ERROR ================"))

    ;; Unauthorized or Auth timeout
    (and (map? error)
         (= :authentication type)
         (= :unauthorized code))
    (ts/schedule 0 #(st/emit! (rt/nav :auth/login)))

    ;; Network error
    (and (map? error)
         (= :unexpected type)
         (= :abort code))
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.network"))))

    ;; Something else
    :else
    (do
      (js/console.error error)
      (ts/schedule 100 #(st/emit! (uum/error (tr "errors.generic")))))))

(set! st/*on-error* on-error)

;; --- Main App (Component)

(def route-iref
  (-> (l/key :route)
      (l/derive st/state)))

(mf/defc app
  [props]
  (let [route (mf/deref route-iref)]
    (case (get-in route [:data :name])
      :auth/login (mf/element auth/login-page)
      :auth/register (mf/element auth/register-page)

      ;; :auth/recovery-request (auth/recovery-request-page)
      ;; :auth/recovery
      ;; (let [token (get-in route [:params :path :token])]
      ;;   (auth/recovery-page token))

      (:settings/profile
       :settings/password
       :settings/notifications)
      (mf/element settings/settings #js {:route route})

      :dashboard-projects
      (mf/element dashboard/dashboard-projects #js {:route route})

      (:dashboard-icons
       :dashboard-images
       :dashboard-colors)
      (mf/element dashboard/dashboard-assets #js {:route route})

      :workspace
      (let [file-id (uuid (get-in route [:params :path :file-id]))
            page-id (uuid (get-in route [:params :query :page-id]))]
        [:& workspace/workspace {:file-id file-id
                                 :page-id page-id
                                 :key file-id}])
      nil)))

