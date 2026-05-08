;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.team-form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.types.team :as ctt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.refs :as refs]
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
  [form response]
  (let [id   (get-in @form [:clean-data :id])
        code (-> response ex-data :code)]
    (if (= code :not-allowed)
      (rx/of (modal/show :no-permission-modal {:type :create-team}))
      (if id
        (rx/of (ntf/error "Error on updating team."))
        (rx/of (ntf/error "Error on creating team."))))))

(defn- on-create-submit
  [form]
  (let [mdata  {:on-success (partial on-create-success form)
                :on-error   (partial on-error form)}
        data   (:clean-data @form)
        params (cond-> {:name (:name data)}
                 (:organization-id data) (assoc :organization-id (:organization-id data)))]
    (st/emit! (-> (dtm/create-team (with-meta params mdata))
                  (with-meta {::ev/origin :dashboard})))))

(defn- on-update-submit
  [form]
  (let [mdata  {:on-success (partial on-update-success form)
                :on-error   (partial on-error form)}
        data   (:clean-data @form)
        team   (select-keys data [:id :name])]
    (st/emit! (dtm/update-team (with-meta team mdata))
              (modal/hide))))

(defn- on-submit
  [form _]
  (let [data (:clean-data @form)]
    (if (:id data)
      (on-update-submit form)
      (on-create-submit form))))

(mf/defc team-form-modal
  {::mf/register modal/components
   ::mf/register-as :team-form}
  [{:keys [team organization-id] :as props}]
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
                    (partial on-submit form))
        handle-keydown
        (mf/use-fn
         (mf/deps form)
         (fn [e]
           (when (kbd/enter? e)
             (dom/prevent-default e)
             (dom/stop-propagation e)
             (on-submit form e))))]

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


(mf/defc no-permission-modal*
  "Generic modal for displaying permission-related messages based on error type"
  {::mf/register modal/components
   ::mf/register-as :no-permission-modal}
  [{:keys [type]}]
  (let [team             (mf/deref refs/team)
        organization-name (dm/get-in team [:organization :name])
        [title message] (case type
                          :create-team [(tr "labels.create-team")
                                        (tr "dashboard.no-permission-create-team.message" organization-name)]
                          :delete-team [(tr "dashboard.delete-team")
                                        (tr "dashboard.no-permission-delete-team.message" organization-name)]
                          :no-orgs-create [(tr "dashboard.select-org-modal.title")
                                           (tr "dashboard.no-org-allows-create-team.message")]
                          :no-orgs-change [(tr "dashboard.change-org-modal.title")
                                           (tr "dashboard.no-org-allows-create-team.message")])]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} title]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} deprecated-icon/close]]
      [:div {:class (stl/css :modal-content)}
       [:div message]]]]))
