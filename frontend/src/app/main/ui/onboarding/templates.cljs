;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.onboarding.templates
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc template-item
  [{:keys [name path image project-id]}]
  (let [downloading? (mf/use-state false)
        link         (dm/str (assoc cf/public-uri :path path))

        on-finish-import
        (fn []
          (st/emit! (dd/fetch-recent-files)))

        open-import-modal
        (fn [file]
          (st/emit! (modal/show
                     {:type :import
                      :project-id project-id
                      :files [file]
                      :on-finish-import on-finish-import})))
        on-click
        (fn []
          (reset! downloading? true)
          (->> (http/send! {:method :get :uri link :response-type :blob :mode :no-cors})
               (rx/subs! (fn [{:keys [body] :as response}]
                           (open-import-modal {:name name :uri (wapi/create-uri body)}))
                         (fn [error]
                           (js/console.log "error" error))
                         (fn []
                           (reset! downloading? false)))))]

    [:div.template-item
     [:div.template-item-content
      [:img {:src image}]]
     [:div.template-item-title
      [:div.label name]
      (if @downloading?
        [:div.action "Fetching..."]
        [:div.action {:on-click on-click} "+ Add to drafts"])]]))

(mf/defc onboarding-templates-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :onboarding-templates}
  ;; NOTE: the project usually comes empty, it only comes fulfilled
  ;; when a user creates a new team just after signup.
  [props]
  (let [project-id (unchecked-get props "project-id")
        profile    (mf/deref refs/profile)
        project-id (or project-id (:default-project-id profile))]
    [:div.modal-overlay
     [:div.modal-container.onboarding-templates
      [:div.modal-header
       [:div.modal-close-button
        {:on-click modal/hide!
         :data-testid "close-templates-btn"} i/close]]

      [:div.modal-content
       [:h3 (tr "onboarding.templates.title")]
       [:p (tr "onboarding.templates.subtitle")]

       [:div.templates
        [:& template-item
         {:path "/github/penpot-files/Penpot-Design-system.penpot"
          :image "https://penpot.app/images/libraries/cover-ds-penpot.jpg"
          :name "Penpot Design System"
          :project-id project-id}]
        [:& template-item
         {:path "/github/penpot-files/Material-Design-Kit.penpot"
          :image "https://penpot.app/images/libraries/cover-material.jpg"
          :name "Material Design Kit"
          :project-id project-id}]]]]]))
