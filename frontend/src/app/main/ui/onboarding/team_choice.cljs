;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.onboarding.team-choice
  (:require
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.timers :as tm]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(s/def ::name ::us/not-empty-string)
(s/def ::team-form
  (s/keys :req-un [::name]))

(mf/defc onboarding-choice-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-choice}
  []
  (let [;; When user choices the option of `fly solo`, we proceed to show
        ;; the onboarding templates modal.
        on-fly-solo
        (fn []
          (tm/schedule 400  #(st/emit! (modal/show {:type :onboarding-templates}))))

        ;; When user choices the option of `team up`, we proceed to show
        ;; the team creation modal.
        on-team-up
        (fn []
          (st/emit! (modal/show {:type :onboarding-team})))
        ]

    [:div.modal-overlay
     [:div.modal-container.onboarding.final.animated.fadeInUp
      [:div.modal-top
       [:h1 (tr "onboarding.welcome.title")]
       [:p (tr "onboarding.welcome.desc3")]]
      [:div.modal-columns
       [:div.modal-left
        [:div.content-button {:on-click on-fly-solo}
         [:h2 (tr "onboarding.choice.fly-solo")]
         [:p (tr "onboarding.choice.fly-solo-desc")]]]
       [:div.modal-right
        [:div.content-button {:on-click on-team-up}
         [:h2 (tr "onboarding.choice.team-up")]
         [:p (tr "onboarding.choice.team-up-desc")]]]]
      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

(mf/defc onboarding-team-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team}
  []
  (let [form  (fm/use-form :spec ::team-form
                           :initial {})
        on-submit
        (mf/use-callback
         (fn [form _]
           (let [tname (get-in @form [:clean-data :name])]
             (st/emit! (modal/show {:type :onboarding-team-invitations :name tname})))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding-team
      [:div.title
       [:h2 (tr "onboarding.choice.team-up")]
       [:p (tr "onboarding.choice.team-up-desc")]]

      [:& fm/form {:form form
                   :on-submit on-submit}

       [:div.team-row
        [:& fm/input {:type "text"
                      :name :name
                      :label (tr "onboarding.team-input-placeholder")}]]

       [:div.buttons
        [:button.btn-secondary.btn-large
         {:on-click #(st/emit! (modal/show {:type :onboarding-choice}))}
         (tr "labels.cancel")]
        [:& fm/submit-button
         {:label (tr "labels.next")}]]]

      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

(defn get-available-roles
  []
  [{:value "editor" :label (tr "labels.editor")}
   {:value "admin" :label (tr "labels.admin")}])

(s/def ::email ::us/email)
(s/def ::role  ::us/keyword)
(s/def ::invite-form
  (s/keys :req-un [::role ::email]))

;; This is the final step of team creation, consists in provide a
;; shortcut for invite users.

(mf/defc onboarding-team-invitations-modal
  {::mf/register modal/components
   ::mf/register-as :onboarding-team-invitations}
  [{:keys [name] :as props}]
  (let [initial (mf/use-memo (constantly
                              {:role "editor"
                               :name name}))
        form    (fm/use-form :spec ::invite-form
                             :initial initial)

        roles   (mf/use-memo #(get-available-roles))

        on-success
        (mf/use-callback
         (fn [_form response]
           (let [project-id (:default-project-id response)
                 team-id    (:id response)]
             (st/emit!
              (modal/hide)
              (rt/nav :dashboard-projects {:team-id team-id}))
             (tm/schedule 400 #(st/emit!
                                (modal/show {:type :onboarding-templates
                                             :project-id project-id}))))))

        on-error
        (mf/use-callback
         (fn [_form _response]
           (st/emit! (dm/error "Error on creating team."))))

        ;; The SKIP branch only creates the team, without invitations
        on-skip
        (mf/use-callback
         (fn [_]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params {:name name}]
             (st/emit! (dd/create-team (with-meta params mdata))))))

        ;; The SUBMIT branch creates the team with the invitations
        on-submit
        (mf/use-callback
         (fn [form _]
           (let [mdata  {:on-success (partial on-success form)
                         :on-error   (partial on-error form)}
                 params (:clean-data @form)]
             (st/emit! (dd/create-team-with-invitations (with-meta params mdata))))))]

    [:div.modal-overlay
     [:div.modal-container.onboarding-team
      [:div.title
       [:h2 (tr "onboarding.choice.team-up")]
       [:p (tr "onboarding.choice.team-up-desc")]]

      [:& fm/form {:form form
                   :on-submit on-submit}

      [:div.invite-row
       [:& fm/input {:name :email
                     :label (tr "labels.email")}]
       [:& fm/select {:name :role
                      :options roles}]]

       [:div.buttons
        [:button.btn-secondary.btn-large
         {:on-click #(st/emit! (modal/show {:type :onboarding-choice}))}
         (tr "labels.cancel")]
        [:& fm/submit-button
         {:label (tr "labels.create")}]]
       [:div.skip-action
        {:on-click on-skip}
        [:div.action "Skip and invite later"]]]
      [:img.deco {:src "images/deco-left.png" :border "0"}]
      [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]))

