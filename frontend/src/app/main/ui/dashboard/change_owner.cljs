;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.change-owner
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:leave-modal-form
  [:map {:title "LeaveModalForm"}
   [:member-id ::sm/uuid]])

(mf/defc leave-and-reassign-modal
  {::mf/register modal/components
   ::mf/register-as :leave-and-reassign}
  [{:keys [profile team accept]}]
  (let [form        (fm/use-form :schema schema:leave-modal-form :initial {})
        members     (get team :members)

        options
        (into [{:value ""
                :label (tr "modals.leave-and-reassign.select-member-to-promote")}]
              (comp
               (filter #(not= (:email %) (:email profile)))
               (map #(hash-map :label (:name %) :value (str (:id %)))))
              members)

        on-accept
        (fn [_]
          (let [member-id (get-in @form [:clean-data :member-id])]
            (accept member-id)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.leave-and-reassign.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       [:p {:class (stl/css :modal-msg)}
        (tr "modals.leave-and-reassign.hint1" (:name team))]

       (if (empty? members)
         [:p {:class (stl/css :modal-msg)}
          (tr "modals.leave-and-reassign.forbidden")]
         [:*
          [:& fm/form {:form form}
           [:& fm/select {:name :member-id
                          :options options}]]])]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input {:class (stl/css :cancel-button)
                 :type "button"
                 :value (tr "labels.cancel")
                 :on-click modal/hide!}]

        [:input.accept-button
         {:type "button"
          :class (stl/css-case  :accept-btn true
                                :danger (:valid @form)
                                :global/disabled (not (:valid @form)))
          :disabled (not (:valid @form))
          :value (tr "modals.leave-and-reassign.promote-and-leave")
          :on-click on-accept}]]]]]))



(mf/defc ^:private team-member-select*
  [{:keys [team profile form field-name default-member-id]}]
  (let [members (get team :members)
        filtered-members (->> members
                              (filter #(not= (:email %) (:email profile))))
        options          (->> filtered-members
                              (map #(hash-map :label (:name %) :value (str (:id %)))))]
    [:div {:class (stl/css :team-select-container)}
     [:div {:class (stl/css :team-name)} (:name team)]
     (if (empty? filtered-members)
       [:p {:class (stl/css :modal-msg)}
        (tr "modals.leave-and-reassign.forbidden")]
       [:& fm/select {:name field-name
                      :select-class (stl/css :team-member)
                      :dropdown-class (stl/css :team-member)
                      :options options
                      :form form
                      :default default-member-id}])]))

(defn- make-leave-org-modal-form-schema [teams]
  (into
   [:map {:title "LeaveOrgModalForm"}]
   (for [team teams]
     [(keyword (str "member-id-" (:id team))) ::sm/text])))


(mf/defc leave-and-reassign-org-modal
  {::mf/register modal/components
   ::mf/register-as :leave-and-reassign-org
   ::mf/wrap [mf/memo]}
  [{:keys [profile teams-to-transfer num-teams-to-delete accept] :as props}]
  (let [schema (mf/with-memo [teams-to-transfer]
                 (make-leave-org-modal-form-schema teams-to-transfer))
        ;; Compute initial values for each team select
        team-fields (mf/with-memo [teams-to-transfer]
                      (for [team teams-to-transfer]
                        (let [members           (get team :members)
                              filtered-members  (filter #(not= (:email %) (:email profile)) members)
                              first-admin       (first (filter :is-admin filtered-members))
                              first-member      (first filtered-members)
                              default-member-id (cond
                                                  first-admin (str (:id first-admin))
                                                  first-member (str (:id first-member))
                                                  :else "")
                              field-name        (keyword (str "member-id-" (:id team)))]
                          {:team team
                           :field-name field-name
                           :default-member-id default-member-id})))

        initial-values (mf/with-memo [team-fields]
                         (d/index-by :field-name :default-member-id team-fields))

        form (fm/use-form :schema schema :initial initial-values)

        all-valid? (every?
                    (fn [{:keys [field-name]}]
                      (let [val (get-in @form [:clean-data field-name])]
                        (not (str/blank? val))))
                    team-fields)

        on-accept (fn [_]
                    (let [result (mapv (fn [{:keys [team field-name]}]
                                         (let [val (get-in @form [:clean-data field-name])]
                                           {:id (:id team)
                                            :reassign-to (uuid/parse val)}))
                                       team-fields)]
                      (accept result)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-org-title)} (tr "modals.before-leave-org.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       (if (zero? num-teams-to-delete)
         [:p {:class (stl/css :modal-org-msg)}
          (tr "modals.leave-org-and-reassign.hint")]
         [:*
          [:p {:class (stl/css :modal-org-msg)}
           (tr "modals.leave-org-and-reassign.hint-delete")]
          [:p {:class (stl/css :modal-org-msg)}
           (tr "modals.leave-org-and-reassign.hint-promote")]])
       [:& fm/form {:form form}
        [:div {:class (stl/css :teams-container)}
         (for [{:keys [team field-name default-member-id]} team-fields]
           ^{:key (:id team)}
           [:> team-member-select* {:team team :profile profile :form form :field-name field-name :default-member-id default-member-id}])]]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input {:class (stl/css :cancel-button)
                 :type "button"
                 :value (tr "labels.cancel")
                 :on-click modal/hide!}]

        [:input.accept-button
         {:type "button"
          :class (stl/css-case  :accept-btn true
                                :danger all-valid?
                                :global/disabled (not all-valid?))
          :disabled (not all-valid?)
          :value (tr "modals.leave-and-reassign.promote-and-leave")
          :on-click on-accept}]]]]]))
