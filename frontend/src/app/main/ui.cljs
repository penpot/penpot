;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.ui.auth :refer [auth]]
   [app.main.ui.auth.verify-token :refer [verify-token]]
   [app.main.ui.components.fullscreen :as fs]
   [app.main.ui.context :as ctx]
   [app.main.ui.cursors :as c]
   [app.main.ui.dashboard :refer [dashboard]]
   [app.main.ui.errors]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.onboarding]
   [app.main.ui.render :as render]
   [app.main.ui.settings :as settings]
   [app.main.ui.static :as static]
   [app.main.ui.viewer :as viewer]
   [app.main.ui.workspace :as workspace]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; --- Routes

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::section ::us/keyword)
(s/def ::index ::us/integer)
(s/def ::token (s/nilable ::us/not-empty-string))
(s/def ::share-id ::us/uuid)

(s/def ::viewer-path-params
  (s/keys :req-un [::file-id]))

(s/def ::viewer-query-params
  (s/keys :req-un [::index]
          :opt-un [::share-id ::section ::page-id]))

(def routes
  [["/auth"
    ["/login"            :auth-login]
    (when (contains? @cf/flags :registration)
      ["/register"         :auth-register])
    (when (contains? @cf/flags :registration)
      ["/register/validate" :auth-register-validate])
    (when (contains? @cf/flags :registration)
      ["/register/success" :auth-register-success])
    ["/recovery/request" :auth-recovery-request]
    ["/recovery"         :auth-recovery]
    ["/verify-token"     :auth-verify-token]]

   ["/settings"
    ["/profile"  :settings-profile]
    ["/password" :settings-password]
    ["/feedback" :settings-feedback]
    ["/options"  :settings-options]]

   ["/view/:file-id"
    {:name :viewer
     :conform
     {:path-params ::viewer-path-params
      :query-params ::viewer-query-params}}]

   (when *assert*
     ["/debug/icons-preview" :debug-icons-preview])

   ;; Used for export
   ["/render-object/:file-id/:page-id/:object-id" :render-object]
   ["/render-sprite/:file-id" :render-sprite]

   ["/dashboard/team/:team-id"
    ["/members"              :dashboard-team-members]
    ["/settings"             :dashboard-team-settings]
    ["/projects"             :dashboard-projects]
    ["/search"               :dashboard-search]
    ["/fonts"                :dashboard-fonts]
    ["/fonts/providers"      :dashboard-font-providers]
    ["/libraries"            :dashboard-libraries]
    ["/projects/:project-id" :dashboard-files]]

   ["/workspace/:project-id/:file-id" :workspace]])

(mf/defc on-main-error
  [{:keys [error] :as props}]
  (mf/use-effect #(ptk/handle-error error))
  [:span "Internal application errror"])

(mf/defc main-page
  {::mf/wrap [#(mf/catch % {:fallback on-main-error})]}
  [{:keys [route] :as props}]
  (let [{:keys [data params]} route]
    [:& (mf/provider ctx/current-route) {:value route}
     (case (:name data)
       (:auth-login
        :auth-register
        :auth-register-validate
        :auth-register-success
        :auth-recovery-request
        :auth-recovery)
       [:& auth {:route route}]

       :auth-verify-token
       [:& verify-token {:route route}]

       (:settings-profile
        :settings-password
        :settings-options
        :settings-feedback)
       [:& settings/settings {:route route}]

       :debug-icons-preview
       (when *assert*
         [:div.debug-preview
          [:h1 "Cursors"]
          [:& c/debug-preview]
          [:h1 "Icons"]
          [:& i/debug-icons-preview]
          ])

       (:dashboard-search
        :dashboard-projects
        :dashboard-files
        :dashboard-libraries
        :dashboard-fonts
        :dashboard-font-providers
        :dashboard-team-members
        :dashboard-team-settings)
       [:*
        #_[:div.modal-wrapper
           [:& app.main.ui.onboarding/release-notes-modal {:version "1.8"}]]
        [:& dashboard {:route route}]]

       :viewer
       (let [{:keys [query-params path-params]} route
             {:keys [index share-id section page-id] :or {section :interactions}} query-params
             {:keys [file-id]} path-params]
         [:& fs/fullscreen-wrapper {}
          (if (:token query-params)
            [:& viewer/breaking-change-notice]
            [:& viewer/viewer-page {:page-id page-id
                                    :file-id file-id
                                    :section section
                                    :index index
                                    :share-id share-id}])])

       :render-object
       (do
         (let [file-id   (uuid (get-in route [:path-params :file-id]))
               page-id   (uuid (get-in route [:path-params :page-id]))
               object-id (uuid (get-in route [:path-params :object-id]))]
           [:& render/render-object {:file-id file-id
                                     :page-id page-id
                                     :object-id object-id}]))

       :render-sprite
       (do
         (let [file-id      (uuid (get-in route [:path-params :file-id]))
               component-id (get-in route [:query-params :component-id])
               component-id (when (some? component-id) (uuid component-id))]
           [:& render/render-sprite {:file-id file-id
                                     :component-id component-id}]))

       :workspace
       (let [project-id (some-> params :path :project-id uuid)
             file-id    (some-> params :path :file-id uuid)
             page-id    (some-> params :query :page-id uuid)
             layout     (some-> params :query :layout keyword)]
         [:& workspace/workspace {:project-id project-id
                                  :file-id file-id
                                  :page-id page-id
                                  :layout-name layout
                                  :key file-id}])
       nil)]))

(mf/defc app
  []
  (let [route (mf/deref refs/route)
        edata (mf/deref refs/exception)]
    [:& (mf/provider ctx/current-route) {:value route}
     (if edata
       [:& static/exception-page {:data edata}]
       [:*
        [:& msgs/notifications]
        (when route
          [:& main-page {:route route}])])]))
