;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.themes
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.common :refer [labeled-input]]
   [app.main.ui.workspace.tokens.sets :as wts]
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private chevron-icon
  (i/icon-xref :arrow (stl/css :chevron-icon)))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(mf/defc empty-themes
  [{:keys []}]
  "Empty")


(mf/defc switch
  [{:keys [selected? name on-change]}]
  (let [selected (if selected? :on :off)]
    [:& radio-buttons {:selected selected
                       :on-change on-change
                       :name name}
     [:& radio-button {:id :on
                       :value :on
                       :icon i/tick
                       :label ""}]
     [:& radio-button {:id :off
                       :value :off
                       :icon i/close
                       :label ""}]]))

(mf/defc themes-overview
  [{:keys [set-state]}]
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-ids)
        themes (mf/deref refs/workspace-ordered-token-themes)
        on-edit-theme (fn [theme e]
                        (dom/prevent-default e)
                        (dom/stop-propagation e)
                        (set-state (fn [_] {:type :edit-theme
                                            :theme-id (:id theme)})))]
    [:div
     [:ul {:class (stl/css :theme-group-wrapper)}
      (for [[group themes] themes]
        [:li {:key (str "token-theme-group" group)}
         (when (seq group)
           [:span {:class (stl/css :theme-group-label)} group])
         [:ul {:class (stl/css :theme-group-rows-wrapper)}
          (for [{:keys [id name] :as theme} themes
                :let [selected? (some? (get active-theme-ids id))]]
            [:li {:key (str "token-theme-" id)
                  :class (stl/css :theme-row)}
             [:div {:class (stl/css :theme-row-left)}
              [:div {:on-click (fn [e]
                                 (dom/prevent-default e)
                                 (dom/stop-propagation e)
                                 (st/emit! (wdt/toggle-token-theme id)))}
               [:& switch {:name (str "Theme" name)
                           :on-change (constantly nil)
                           :selected? selected?}]]
              [:span {:class (stl/css :theme-row-label)} name]]
             [:div {:class (stl/css :theme-row-right)}
              (if-let [sets-count (some-> theme :sets seq count)]
                [:button {:class (stl/css :sets-count-button)
                          :on-click #(on-edit-theme theme %)}
                 (str sets-count " sets")
                 chevron-icon]
                [:button {:class (stl/css :sets-count-empty-button)
                          :on-click #(on-edit-theme theme %)}
                 "No sets defined"
                 chevron-icon])
              [:div {:class (stl/css :delete-theme-button)}
               [:button {:on-click (fn [e]
                                     (dom/prevent-default e)
                                     (dom/stop-propagation e)
                                     (st/emit! (wdt/delete-token-theme id)))}
                i/delete]]]])]])]
     [:div {:class (stl/css :button-footer)}
      [:button {:class (stl/css :create-theme-button)}
       i/add
       "Create theme"]]]))

(mf/defc edit-theme
  [{:keys [token-sets theme on-back on-submit] :as props}]
  (let [edit? (some? (:id theme))
        theme-state (mf/use-state {:token-sets token-sets
                                   :theme theme})
        token-set-active? (mf/use-callback
                           (mf/deps theme-state)
                           (fn [id]
                             (get-in @theme-state [:theme :sets id])))
        on-toggle-token-set (mf/use-callback
                             (mf/deps theme-state)
                             (fn [token-set-id]
                               (swap! theme-state (fn [st]
                                                    (update st :theme #(wtts/toggle-token-set-to-token-theme token-set-id %))))))
        on-change-field (fn [field]
                          (fn [e]
                            (swap! theme-state (fn [st] (assoc-in st field (dom/get-target-val e))))))
        on-update-group (on-change-field [:theme :group])
        on-update-name (on-change-field [:theme :name])
        on-save-form (mf/use-callback
                      (mf/deps theme-state on-submit)
                      (fn [e]
                        (dom/prevent-default e)
                        (on-submit (:theme @theme-state))
                        (on-back)))]
    [:form {:on-submit on-save-form}
     [:div {:class (stl/css :edit-theme-wrapper)}
      [:div
       [:button {:class (stl/css :back-button)
                 :type "button"
                 :on-click on-back}
        chevron-icon "Back"]]
      [:div {:class (stl/css :edit-theme-inputs-wrapper)}
       [:& labeled-input {:label "Group"
                          :input-props {:default-value (:group theme)
                                        :on-change on-update-group}}]
       [:& labeled-input {:label "Theme"
                          :input-props {:default-value (:name theme)
                                        :on-change on-update-name}}]]
      [:div {:class (stl/css :sets-list-wrapper)}
       [:& wts/controlled-sets-list
        {:token-sets token-sets
         :token-set-selected? (constantly false)
         :token-set-active? token-set-active?
         :on-select on-toggle-token-set
         :on-toggle on-toggle-token-set}]]
      [:div {:class (stl/css :edit-theme-footer)}
       (when edit?
         [:button {:class (stl/css :button-secondary)
                   :type "button"}
          "Delete"])
       [:div {:class (stl/css :button-footer)}
        [:button {:class (stl/css :button-secondary)
                  :type "button"
                  :on-click #(st/emit! (modal/hide))}
         "Cancel"]
        [:button {:class (stl/css :button-primary)
                  :type "submit"
                  :on-click on-save-form}
         "Save theme"]]]]]))

(mf/defc controlled-edit-theme
  [{:keys [state set-state]}]
  (let [{:keys [theme-id]} @state
        token-sets (mf/deref refs/workspace-token-sets)
        theme (mf/deref (refs/workspace-token-theme theme-id))]
    [:& edit-theme
     {:token-sets token-sets
      :theme theme
      :on-back #(set-state (constantly {:type :themes-overview}))
      :on-submit #(st/emit! (wdt/update-token-theme %))}]))

(mf/defc themes
  [{:keys [] :as _args}]
  (let [themes (mf/deref refs/workspace-ordered-token-themes)
        state (mf/use-state (if (empty? themes)
                              {:type :empty-themes}
                              {:type :themes-overview}))
        set-state (mf/use-callback #(swap! state %))
        title (case (:type @state)
                :edit-theme "Edit Theme"
                "Themes")
        component (case (:type @state)
                    :empty-themes empty-themes
                    :themes-overview themes-overview
                    :edit-theme controlled-edit-theme)]
    [:div

     [:div {:class (stl/css :modal-title)} title]
     [:div {:class (stl/css :modal-content)}
      [:& component {:state state
                     :set-state set-state}]]]))

(mf/defc modal
  {::mf/wrap-props false}
  [{:keys [] :as _args}]
  (let [handle-close-dialog (mf/use-callback #(st/emit! (modal/hide)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:& themes]]]))
