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
   [app.main.ui.context :as ctx]
   [app.main.ui.debug.components-preview :as cm]
   [app.main.ui.debug.icons-preview :refer [icons-preview]]
   [app.main.ui.frame-preview :as frame-preview]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.onboarding.newsletter :refer [onboarding-newsletter]]
   [app.main.ui.onboarding.questions :refer [questions-modal]]
   [app.main.ui.onboarding.team-choice :refer [onboarding-team-modal]]
   [app.main.ui.releases :refer [release-notes-modal]]
   [app.main.ui.static :as static]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [rumext.v2 :as mf]))

(def auth-page
  (mf/lazy-component app.main.ui.auth/auth))

(def verify-token-page
  (mf/lazy-component app.main.ui.auth.verify-token/verify-token))

(def viewer-page
  (mf/lazy-component app.main.ui.viewer/viewer))

(def dashboard-page
  (mf/lazy-component app.main.ui.dashboard/dashboard))

(def settings-page
  (mf/lazy-component app.main.ui.settings/settings))

(def workspace-page
  (mf/lazy-component app.main.ui.workspace/workspace))

(mf/defc on-main-error
  [{:keys [error] :as props}]
  (mf/with-effect
    (st/emit! (rt/assign-exception error)))
  [:span "Internal application error"])

(mf/defc main-page
  {::mf/wrap [#(mf/catch % {:fallback on-main-error})]
   ::mf/props :obj}
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
       [:? [:& auth-page {:route route}]]

       :auth-verify-token
       [:? [:& verify-token-page {:route route}]]

       (:settings-profile
        :settings-password
        :settings-options
        :settings-feedback
        :settings-access-tokens)
       [:? [:& settings-page {:route route}]]

       :debug-icons-preview
       (when *assert*
         [:& icons-preview])

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
       [:?
        #_[:& app.main.ui.releases/release-notes-modal {:version "1.19"}]
        #_[:& app.main.ui.onboarding/onboarding-templates-modal]
        #_[:& app.main.ui.onboarding/onboarding-modal]
        #_[:& app.main.ui.onboarding.team-choice/onboarding-team-modal]
        (when-let [props (get profile :props)]
          (let [show-question-modal?
                (and (contains? cf/flags :onboarding)
                     (not (:onboarding-viewed props))
                     (not (contains? props :onboarding-questions)))

                show-newsletter-modal?
                (and (contains? cf/flags :onboarding)
                     (not (:onboarding-viewed props))
                     (not (contains? props :newsletter-updates))
                     (contains? props :onboarding-questions))

                show-team-modal?
                (and (contains? cf/flags :onboarding)
                     (not (:onboarding-viewed props))
                     (not (contains? props :onboarding-team-id))
                     (contains? props :newsletter-updates))

                show-release-modal?
                (and (contains? cf/flags :onboarding)
                     (:onboarding-viewed props)
                     (not= (:release-notes-viewed props) (:main cf/version))
                     (not= "0.0" (:main cf/version)))]

            (cond
              show-question-modal?
              [:& questions-modal]

              show-newsletter-modal?
              [:& onboarding-newsletter]

              show-team-modal?
              [:& onboarding-team-modal]

              show-release-modal?
              [:& release-notes-modal {:version (:main cf/version)}])))

        [:& dashboard-page {:route route :profile profile}]]
       :viewer
       (let [{:keys [query-params path-params]} route
             {:keys [index share-id section page-id interactions-mode frame-id share]
              :or {section :interactions interactions-mode :show-on-click}} query-params
             {:keys [file-id]} path-params]
         [:? {}
          (if (:token query-params)
            [:> static/error-container {}
             [:div.image i/detach]
             [:div.main-message (tr "viewer.breaking-change.message")]
             [:div.desc-message (tr "viewer.breaking-change.description")]]

            [:& viewer-page
             {:page-id page-id
              :file-id file-id
              :section section
              :index index
              :share-id share-id
              :interactions-mode (keyword interactions-mode)
              :interactions-show? (case (keyword interactions-mode)
                                    :hide false
                                    :show true
                                    :show-on-click false)
              :frame-id frame-id
              :share share}])])

       :workspace
       (let [project-id (some-> params :path :project-id uuid)
             file-id    (some-> params :path :file-id uuid)
             page-id    (some-> params :query :page-id uuid)
             layout     (some-> params :query :layout keyword)]
         [:? {}
          [:& workspace-page {:project-id project-id
                              :file-id file-id
                              :page-id page-id
                              :layout-name layout
                              :key file-id}]])


       :debug-components-preview
       [:div.debug-preview
        [:h1 "Components preview"]
        [:& cm/components-preview]]

       :frame-preview
       [:& frame-preview/frame-preview]

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
        [:& static/exception-page {:data edata :route route}]
        [:*
         [:& msgs/notifications-hub]
         (when route
           [:& main-page {:route route :profile profile}])])]]))
