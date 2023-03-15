;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui
  (:require
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.auth :refer [auth]]
   [app.main.ui.auth.verify-token :refer [verify-token]]
   [app.main.ui.context :as ctx]
   [app.main.ui.cursors :as c]
   [app.main.ui.dashboard :refer [dashboard]]
   [app.main.ui.debug.components-preview :as cm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.onboarding]
   [app.main.ui.onboarding.questions]
   [app.main.ui.releases]
   [app.main.ui.settings :as settings]
   [app.main.ui.static :as static]
   [app.main.ui.viewer :as viewer]
   [app.main.ui.workspace :as workspace]
   [app.util.dom :as dom]
   [app.util.router :as rt]
   [rumext.v2 :as mf]))

(mf/defc on-main-error
  [{:keys [error] :as props}]
  (mf/with-effect
    (js/console.log error)
    (st/emit! (rt/assign-exception error)))
  [:span "Internal application error"])

(mf/defc main-page
  {::mf/wrap [#(mf/catch % {:fallback on-main-error})]}
  [{:keys [route profile]}]
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
          [:& i/debug-icons-preview]])

       :debug-components-preview
       [:div.debug-preview
        [:h1 "Components preview"]
        [:& cm/components-preview]]

       (:dashboard-search
        :dashboard-projects
        :dashboard-files
        :dashboard-libraries
        :dashboard-fonts
        :dashboard-font-providers
        :dashboard-team-members
        :dashboard-team-invitations
        :dashboard-team-webhooks
        :dashboard-team-settings)

       [:*
        #_[:div.modal-wrapper
           #_[:& app.main.ui.releases/release-notes-modal {:version "1.16"}]
           #_[:& app.main.ui.onboarding/onboarding-templates-modal]
           #_[:& app.main.ui.onboarding/onboarding-modal]
           #_[:& app.main.ui.onboarding/onboarding-team-modal]]
        (when-let [props (some-> profile (get :props {}))]
          (cond
            (and cf/onboarding-form-id
                 (not (:onboarding-questions-answered props false))
                 (not (:onboarding-viewed props false)))
            [:& app.main.ui.onboarding.questions/questions
             {:profile profile
              :form-id cf/onboarding-form-id}]

            (not (:onboarding-viewed props))
            [:& app.main.ui.onboarding/onboarding-modal {}]

            (and (:onboarding-viewed props)
                 (not= (:release-notes-viewed props) (:main @cf/version))
                 (not= "0.0" (:main @cf/version)))
            [:& app.main.ui.releases/release-notes-modal {:version (:main @cf/version)}]))

        [:& dashboard {:route route :profile profile}]]

       :viewer
       (let [{:keys [query-params path-params]} route
             {:keys [index share-id section page-id] :or {section :interactions}} query-params
             {:keys [file-id]} path-params]
         (if (:token query-params)
           [:& viewer/breaking-change-notice]
           [:& viewer/viewer-page {:page-id page-id
                                   :file-id file-id
                                   :section section
                                   :index index
                                   :share-id share-id}]))

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
  (let [route   (mf/deref refs/route)
        edata   (mf/deref refs/exception)
        profile (mf/deref refs/profile)
        theme   (or (:theme profile) "default")]

    (mf/with-effect [theme]
      (dom/set-html-theme-color theme))
    [:& (mf/provider ctx/current-route) {:value route}
     [:& (mf/provider ctx/current-profile) {:value profile}
      (if edata
        [:& static/exception-page {:data edata}]
        [:*
         [:& msgs/notifications]
         (when route
           [:& main-page {:route route :profile profile}])])]]))
