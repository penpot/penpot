;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard :refer [dashboard]]
   [uxbox.main.ui.login :refer [login-page]]
   [uxbox.main.ui.static :refer [not-found-page not-authorized-page]]
   [uxbox.main.ui.profile.recovery :refer [profile-recovery-page]]
   [uxbox.main.ui.profile.recovery-request :refer [profile-recovery-request-page]]
   [uxbox.main.ui.profile.register :refer [profile-register-page]]
   [uxbox.main.ui.settings :as settings]
   [uxbox.main.ui.viewer :refer [viewer-page]]
   [uxbox.main.ui.workspace :as workspace]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.timers :as ts]))

;; --- Routes

(def routes
  [["/login" :login]
   ["/register" :profile-register]
   ["/recovery/request" :profile-recovery-request]
   ["/recovery" :profile-recovery]

   ["/settings"
    ["/profile" :settings-profile]
    ["/password" :settings-password]]

   ["/view/:page-id" :viewer]
   ["/not-found" :not-found]
   ["/not-authorized" :not-authorized]

   (when *assert*
     ["/debug/icons-preview" :debug-icons-preview])

   ["/dashboard"
    ["/team/:team-id"
     ["/" :dashboard-team]
     ["/search" :dashboard-search]
     ["/project/:project-id" :dashboard-project]
     ["/library"
      ["/icons"
       ["" { :name :dashboard-library-icons-index :section :icons}]
       ["/:library-id" { :name :dashboard-library-icons :section :icons}]]

      ["/images"
       ["" { :name :dashboard-library-images-index :section :images}]
       ["/:library-id" { :name :dashboard-library-images :section :images}]]

      ["/palettes"
       ["" { :name :dashboard-library-palettes-index :section :palettes}]
       ["/:library-id" { :name :dashboard-library-palettes :section :palettes }]]

      ]]]

   ["/workspace/:project-id/:file-id" :workspace]])

(mf/defc app-error
  [{:keys [error] :as props}]
  (let [data (ex-data error)]
    (case (:type data)
      :not-found [:& not-found-page {:error data}]
      [:span "Internal application errror"])))

(mf/defc app-container
  {::mf/wrap [#(mf/catch % {:fallback app-error})]}
  [{:keys [route] :as props}]
  (case (get-in route [:data :name])
    :login
    [:& login-page]

    :profile-register
    [:& profile-register-page]

    :profile-recovery-request
    [:& profile-recovery-request-page]

    :profile-recovery
    [:& profile-recovery-page]

    (:settings-profile
     :settings-password)
    [:& settings/settings {:route route}]

    :debug-icons-preview
    (when *assert*
      [:& i/debug-icons-preview])

    (:dashboard-search
     :dashboard-team
     :dashboard-project
     :dashboard-library-icons
     :dashboard-library-icons-index
     :dashboard-library-images
     :dashboard-library-images-index
     :dashboard-library-palettes
     :dashboard-library-palettes-index)
    [:& dashboard {:route route}]

    :viewer
    (let [index (d/parse-integer (get-in route [:params :query :index]))
          token (get-in route [:params :query :token])
          page-id (uuid (get-in route [:params :path :page-id]))]
      [:& viewer-page {:page-id page-id
                       :index index
                       :token token}])

    :workspace
    (let [project-id (uuid (get-in route [:params :path :project-id]))
          file-id (uuid (get-in route [:params :path :file-id]))
          page-id (uuid (get-in route [:params :query :page-id]))]
      [:& workspace/workspace {:project-id project-id
                               :file-id file-id
                               :page-id page-id
                               :key file-id}])

    :not-authorized
    [:& not-authorized-page]

    :not-found
    [:& not-found-page]))

(mf/defc app
  []
  (let [route (mf/deref refs/route)]
    (when route
      [:& app-container {:route route}])))

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
    (ts/schedule 0 #(st/emit! logout))

    ;; Network error
    (and (map? error)
         (= :unexpected type)
         (= :abort code))
    (ts/schedule 100 #(st/emit! (dm/error (tr "errors.network"))))

    ;; Something else
    :else
    (do
      (js/console.error error)
      (ts/schedule 100 #(st/emit! (dm/error (tr "errors.generic")))))))

(set! st/*on-error* on-error)
