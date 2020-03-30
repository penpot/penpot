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
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.exceptions :as ex]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.error :refer [wrap-catch]]
   [uxbox.main.ui.dashboard :refer [dashboard]]
   [uxbox.main.ui.login :refer [login-page]]
   [uxbox.main.ui.profile.recovery :refer [profile-recovery-page]]
   [uxbox.main.ui.profile.recovery-request :refer [profile-recovery-request-page]]
   [uxbox.main.ui.profile.register :refer [profile-register-page]]
   [uxbox.main.ui.settings :as settings]
   [uxbox.main.ui.shapes]
   [uxbox.main.ui.workspace :as workspace]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.util.timers :as ts]))

(def route-iref
  (-> (l/key :route)
      (l/derive st/state)))

;; --- Routes

(def routes
  [["/login" :login]
   ["/register" :profile-register]
   ["/recovery/request" :profile-recovery-request]
   ["/recovery" :profile-recovery]

   ["/settings"
    ["/profile" :settings-profile]
    ["/password" :settings-password]]

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
      :not-found [:span "404"]
      [:span "Internal application errror"])))

(mf/defc app
  {:wrap [#(wrap-catch % {:fallback app-error})]}
  [props]
  (let [route (mf/deref route-iref)]
    (case (get-in route [:data :name])
      :login
      (mf/element login-page)

      :profile-register
      (mf/element profile-register-page)

      :profile-recovery-request
      (mf/element profile-recovery-request-page)

      :profile-recovery
      (mf/element profile-recovery-page)

      (:settings-profile
       :settings-password)
      (mf/element settings/settings #js {:route route})

      :debug-icons-preview
      (when *assert*
        (mf/element i/debug-icons-preview))

      (:dashboard-search
       :dashboard-team
       :dashboard-project
       :dashboard-library-icons
       :dashboard-library-icons-index
       :dashboard-library-images
       :dashboard-library-images-index
       :dashboard-library-palettes
       :dashboard-library-palettes-index)
      (mf/element dashboard #js {:route route})

      :workspace
      (let [project-id (uuid (get-in route [:params :path :project-id]))
            file-id (uuid (get-in route [:params :path :file-id]))
            page-id (uuid (get-in route [:params :query :page-id]))]
        [:& workspace/workspace {:project-id project-id
                                 :file-id file-id
                                 :page-id page-id
                                 :key file-id}])
      nil)))

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
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.network"))))

    ;; Something else
    :else
    (do
      (js/console.error error)
      (ts/schedule 100 #(st/emit! (uum/error (tr "errors.generic")))))))

(set! st/*on-error* on-error)
