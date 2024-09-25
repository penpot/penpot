;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.themes
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.common :refer [labeled-input] :as wtco]
   [app.main.ui.workspace.tokens.sets :as wts]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private chevron-icon
  (i/icon-xref :arrow (stl/css :chevron-icon)))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(mf/defc empty-themes
  [{:keys [set-state]}]
  [:div {:class (stl/css :empty-themes-wrapper)}
   [:div {:class (stl/css :empty-themes-message)}
    [:h1 "You currently have no themes."]
    [:p "Create your first theme now."]]
   [:div {:class (stl/css :button-footer)}
    [:button {:class (stl/css :button-primary)
              :on-click #(set-state (fn [_] {:type :create-theme}))}
     "New theme"]]])

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
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-paths)
        themes-groups (mf/deref refs/workspace-token-theme-tree)
        on-edit-theme (fn [theme e]
                        (dom/prevent-default e)
                        (dom/stop-propagation e)
                        (set-state (fn [_] {:type :edit-theme
                                            :theme-path [(:id theme) (:group theme) (:name theme)]})))]
    [:div
     [:ul {:class (stl/css :theme-group-wrapper)}
      (for [[group themes] themes-groups]
        [:li {:key (str "token-theme-group" group)}
         (when (seq group)
           [:span {:class (stl/css :theme-group-label)} group])
         [:ul {:class (stl/css :theme-group-rows-wrapper)}
          (for [[_ {:keys [group name] :as theme}] themes
                :let [theme-id (ctob/theme-path theme)
                      selected? (some? (get active-theme-ids theme-id))]]
            [:li {:key theme-id
                  :class (stl/css :theme-row)}
             [:div {:class (stl/css :theme-row-left)}
              [:div {:on-click (fn [e]
                                 (dom/prevent-default e)
                                 (dom/stop-propagation e)
                                 (st/emit! (wdt/toggle-token-theme-active? group name)))}
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
                                     (st/emit! (wdt/delete-token-theme group name)))}
                i/delete]]]])]])]
     [:div {:class (stl/css :button-footer)}
      [:button {:class (stl/css :create-theme-button)
                :on-click (fn [e]
                            (dom/prevent-default e)
                            (dom/stop-propagation e)
                            (set-state (fn [_] {:type :create-theme})))}
       i/add
       "Create theme"]]]))

(mf/defc edit-theme
  [{:keys [edit? token-sets theme theme-groups on-back on-submit]}]
  (let [{:keys [dropdown-open? on-open-dropdown on-close-dropdown on-toggle-dropdown]} (wtco/use-dropdown-open-state)
        theme-state (mf/use-state {:token-sets token-sets
                                   :theme theme})
        disabled? (-> (get-in @theme-state [:theme :name])
                      (str/trim)
                      (str/empty?))
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
        group-input-ref (mf/use-ref)
        on-update-group (on-change-field [:theme :group])
        on-update-name (on-change-field [:theme :name])
        on-save-form (mf/use-callback
                      (mf/deps theme-state on-submit)
                      (fn [e]
                        (dom/prevent-default e)
                        (let [theme (:theme @theme-state)
                              final-name (-> (:name theme)
                                             (str/trim))
                              empty-description? (-> (:description theme)
                                                     (str/trim)
                                                     (str/empty?))]
                          (when-not (str/empty? final-name)
                            (cond-> theme
                              empty-description? (assoc :description "")
                              :always (doto js/console.log)
                              :always on-submit)))
                        (on-back)))]
    [:form {:on-submit on-save-form}
     [:div {:class (stl/css :edit-theme-wrapper)}
      [:div
       [:button {:class (stl/css :back-button)
                 :type "button"
                 :on-click on-back}
        chevron-icon "Back"]]
      [:div {:class (stl/css :edit-theme-inputs-wrapper)}
       [:div {:class (stl/css :group-input-wrapper)}
        (when dropdown-open?
          [:& wtco/dropdown-select {:id ::groups-dropdown
                                    :shortcuts-key ::groups-dropdown
                                    :options (map (fn [group]
                                                    {:label group
                                                     :value group})
                                                  theme-groups)
                                    :on-select (fn [{:keys [value]}]
                                                 (set! (.-value (mf/ref-val group-input-ref)) value)
                                                 (swap! theme-state assoc-in [:theme :group] value))
                                    :on-close on-close-dropdown}])
        [:& labeled-input {:label "Group"
                           :input-props {:ref group-input-ref
                                         :default-value (:group theme)
                                         :on-change on-update-group}
                           :render-right (when (seq theme-groups)
                                           (mf/fnc []
                                             [:button {:class (stl/css :group-drop-down-button)
                                                       :type "button"
                                                       :on-click (fn [e]
                                                                   (dom/stop-propagation e)
                                                                   (on-toggle-dropdown))}
                                              i/arrow]))}]]
       [:& labeled-input {:label "Theme"
                          :input-props {:default-value (:name theme)
                                        :on-change on-update-name}}]]
      [:div {:class (stl/css :sets-list-wrapper)}
       [:& wts/controlled-sets-list
        {:token-sets token-sets
         :token-set-selected? (constantly false)
         :token-set-active? token-set-active?
         :on-select on-toggle-token-set
         :on-toggle-token-set on-toggle-token-set
         :context sets-context/static-context}]]
      [:div {:class (stl/css :edit-theme-footer)}
       (if edit?
         [:button {:class (stl/css :button-secondary)
                   :type "button"
                   :on-click (fn []
                               (st/emit! (wdt/delete-token-theme (:group theme) (:name theme)))
                               (on-back))}
          "Delete"]
         [:div])
       [:div {:class (stl/css :button-footer)}
        [:button {:class (stl/css :button-secondary)
                  :type "button"
                  :on-click #(st/emit! (modal/hide))}
         "Cancel"]
        [:button {:class (stl/css :button-primary)
                  :type "submit"
                  :on-click on-save-form
                  :disabled disabled?}
         "Save theme"]]]]]))

(mf/defc controlled-edit-theme
  [{:keys [state set-state]}]
  (let [{:keys [theme-path]} @state
        [_ theme-group theme-name] theme-path
        token-sets (mf/deref refs/workspace-ordered-token-sets)
        theme (mf/deref (refs/workspace-token-theme theme-group theme-name))
        theme-groups (mf/deref refs/workspace-token-theme-groups)]
    [:& edit-theme
     {:edit? true
      :token-sets token-sets
      :theme theme
      :theme-groups theme-groups
      :on-back #(set-state (constantly {:type :themes-overview}))
      :on-submit #(st/emit! (wdt/update-token-theme [(:group theme) (:name theme)] %))}]))

(mf/defc create-theme
  [{:keys [set-state]}]
  (let [token-sets (mf/deref refs/workspace-ordered-token-sets)
        theme {:name "" :sets #{}}
        theme-groups (mf/deref refs/workspace-token-theme-groups)]
    [:& edit-theme
     {:edit? false
      :token-sets token-sets
      :theme theme
      :theme-groups theme-groups
      :on-back #(set-state (constantly {:type :themes-overview}))
      :on-submit #(st/emit! (wdt/create-token-theme %))}]))

(mf/defc themes
  [_]
  (let [themes (mf/deref refs/workspace-token-themes)
        state (mf/use-state (if (empty? themes)
                              {:type :create-theme}
                              {:type :themes-overview}))
        set-state (mf/use-callback #(swap! state %))
        title (case (:type @state)
                :edit-theme "Edit Theme"
                "Themes")
        component (case (:type @state)
                    :empty-themes empty-themes
                    :themes-overview (if (empty? themes) empty-themes themes-overview)
                    :edit-theme controlled-edit-theme
                    :create-theme create-theme)]
    [:div
     [:div {:class (stl/css :modal-title)} title]
     [:div {:class (stl/css :modal-content)}
      [:& component {:state state
                     :set-state set-state}]]]))

(mf/defc modal
  {::mf/wrap-props false}
  [_]
  (let [handle-close-dialog (mf/use-callback #(st/emit! (modal/hide)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:& themes]]]))
