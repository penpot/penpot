;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.team-form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.common.types.team :as ctt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private schema:team-form
  [:map {:title "TeamForm"}
   [:name ctt/schema:team-name]
   [:organization-id {:optional true} [:maybe ::sm/uuid]]])

(defn- on-create-success
  [_form response]
  (let [message "Team created successfully"
        team-id (:id response)]
    (st/emit! (ntf/success message)
              (dcm/go-to-dashboard-recent :team-id team-id))))

(defn- on-update-success
  [_form _response]
  (let [message "Team created successfully"]
    (st/emit! (ntf/success message)
              (modal/hide))))

(defn- on-error
  [form organization-name response]
  (let [id   (get-in @form [:clean-data :id])
        code (-> response ex-data :code)]
    (if (= code :not-allowed)
      (rx/of (modal/show :no-permission-create-team {:organization-name organization-name}))
      (if id
        (rx/of (ntf/error "Error on updating team."))
        (rx/of (ntf/error "Error on creating team."))))))

(defn- on-create-submit
  [form organization-name]
  (let [mdata  {:on-success (partial on-create-success form)
                :on-error   (partial on-error form organization-name)}
        data   (:clean-data @form)
        params (cond-> {:name (:name data)}
                 (:organization-id data) (assoc :organization-id (:organization-id data)))]
    (st/emit! (-> (dtm/create-team (with-meta params mdata))
                  (with-meta {::ev/origin :dashboard})))))

(defn- on-update-submit
  [form]
  (let [mdata  {:on-success (partial on-update-success form)
                :on-error   (partial on-error form nil)}
        data   (:clean-data @form)
        team   (select-keys data [:id :name])]
    (st/emit! (dtm/update-team (with-meta team mdata))
              (modal/hide))))

(defn- on-submit
  [organization-name form _]
  (let [data (:clean-data @form)]
    (if (:id data)
      (on-update-submit form)
      (on-create-submit form organization-name))))

(mf/defc team-form-modal
  {::mf/register modal/components
   ::mf/register-as :team-form}
  [{:keys [team organization-id organization-name] :as props}]
  (let [initial (mf/use-memo
                 (mf/deps team organization-id)
                 (fn []
                   (if team
                     ;; For existing teams, only include name and id (no organization changes)
                     (select-keys team [:name :id])
                     ;; For new teams, include organization-id if provided
                     (cond-> {}
                       organization-id (assoc :organization-id organization-id)))))
        form    (fm/use-form :schema schema:team-form
                             :initial initial)
        on-submit* (mf/use-fn
                    (mf/deps organization-name)
                    (partial on-submit organization-name))
        handle-keydown
        (mf/use-fn
         (mf/deps organization-name)
         (fn [e]
           (when (kbd/enter? e)
             (dom/prevent-default e)
             (dom/stop-propagation e)
             (on-submit organization-name form e))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form
                   :on-submit on-submit*
                   :class (stl/css :team-form)}

       [:div {:class (stl/css :modal-header)}
        (if team
          [:h2 {:class (stl/css :modal-title)}
           (tr "labels.rename-team")]
          [:h2 {:class (stl/css :modal-title)}
           (tr "labels.create-team")])

        [:button {:class (stl/css :modal-close-btn)
                  :on-click modal/hide!} deprecated-icon/close]]

       [:div {:class (stl/css :modal-content)}
        [:& fm/input {:type "text"
                      :auto-focus? true
                      :class (stl/css :group-name-input)
                      :form form
                      :name :name
                      :placeholder "E.g. Design"
                      :label (tr "labels.create-team.placeholder")
                      :on-key-down handle-keydown}]]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)}
         [:> fm/submit-button*
          {:label (if team
                    (tr "labels.update-team")
                    (tr "labels.create-team"))
           :class (stl/css :accept-btn)}]]]]]]))


(mf/defc no-permission-create-team-modal*
  {::mf/register modal/components
   ::mf/register-as :no-permission-create-team}
  [{:keys [organization-name]}]
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-container)}
    [:div {:class (stl/css :modal-header)}
     [:h2 {:class (stl/css :modal-title)}
      (tr "labels.create-team")]
     [:button {:class (stl/css :modal-close-btn)
               :on-click modal/hide!} deprecated-icon/close]]
    [:div {:class (stl/css :modal-content)}
     [:div (tr "dashboard.no-permission-create-team.message" organization-name)]]]])


(mf/defc no-org-allows-create-team-modal*
  {::mf/register modal/components
   ::mf/register-as :no-org-allows-create-team}
  [_props]
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-container)}
    [:div {:class (stl/css :modal-header)}
     [:h2 {:class (stl/css :modal-title)}
      (tr "dashboard.select-org-modal.title")]
     [:button {:class (stl/css :modal-close-btn)
               :on-click modal/hide!} deprecated-icon/close]]
    [:div {:class (stl/css :modal-content)}
     [:div (tr "dashboard.no-org-allows-create-team.message")]]]])
