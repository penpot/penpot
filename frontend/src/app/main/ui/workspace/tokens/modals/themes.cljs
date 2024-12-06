;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.themes
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as ic]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.common :refer [labeled-input] :as wtco]
   [app.main.ui.workspace.tokens.sets :as wts]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc empty-themes
  [{:keys [set-state]}]
  (let [create-theme
        (mf/use-fn
         (mf/deps set-state)
         #(set-state (fn [_] {:type :create-theme})))]
    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (tr "workspace.token.themes")]
     [:div {:class (stl/css :empty-themes-wrapper)}
      [:div {:class (stl/css :empty-themes-message)}
       [:> text* {:as "span" :typography "title-medium" :class (stl/css :empty-theme-title)}
        (tr "workspace.token.no-themes-currently")]
       [:> text* {:as "span"
                  :class (stl/css :empty-theme-subtitle)
                  :typography "body-medium"}
        (tr "workspace.token.create-new-theme")]]
      [:div {:class (stl/css :button-footer)}
       [:> button* {:variant "primary"
                    :type "button"
                    :on-click create-theme}
        (tr "workspace.token.new-theme")]]]]))

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
        themes-groups (mf/deref refs/workspace-token-theme-tree-no-hidden)

        create-theme
        (mf/use-fn
         (mf/deps set-state)
         (fn [e]
           (dom/prevent-default e)
           (dom/stop-propagation e)
           (set-state (fn [_] {:type :create-theme}))))]

    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (tr "workspace.token.themes")]
     [:ul {:class (stl/css :theme-group-wrapper)}
      (for [[group themes] themes-groups]
        [:li {:key (dm/str "token-theme-group" group)}
         (when (seq group)
           [:> heading* {:level 3
                         :class (stl/css :theme-group-label)
                         :typography "body-large"}
            [:span {:class (stl/css :group-title)}
             [:> icon* {:id "group"}]
             group]])
         [:ul {:class (stl/css :theme-group-rows-wrapper)}
          (for [[_ {:keys [group name] :as theme}] themes
                :let [theme-id (ctob/theme-path theme)
                      selected? (some? (get active-theme-ids theme-id))
                      delete-theme
                      (fn [e]
                        (dom/prevent-default e)
                        (dom/stop-propagation e)
                        (st/emit! (wdt/delete-token-theme group name)))
                      on-edit-theme
                      (fn [e]
                        (dom/prevent-default e)
                        (dom/stop-propagation e)
                        (set-state (fn [_] {:type :edit-theme
                                            :theme-path [(:id theme) (:group theme) (:name theme)]})))]]
            [:li {:key theme-id
                  :class (stl/css :theme-row)}
             [:div {:class (stl/css :theme-row-left)}

              ;; FIREEEEEEEEEE THIS
              [:div {:on-click (fn [e]
                                 (dom/prevent-default e)
                                 (dom/stop-propagation e)
                                 (st/emit! (wdt/toggle-token-theme-active? group name)))}
               [:& switch {:name (tr "workspace.token.theme" name)
                           :on-change (constantly nil)
                           :selected? selected?}]]
              [:> text* {:as "span"  :typography "body-medium" :class (stl/css :theme-name)} name]]


             [:div {:class (stl/css :theme-row-right)}
              (if-let [sets-count (some-> theme :sets seq count)]
                [:> button* {:class (stl/css :sets-count-button)
                             :variant "secondary"
                             :type "button"
                             :on-click on-edit-theme}
                 [:div {:class (stl/css :label-wrapper)}
                  [:> text* {:as "span" :typography "body-medium"}
                   (tr "workspace.token.num-sets" sets-count)]
                  [:> icon* {:id "arrow-right"}]]]

                [:> button* {:class (stl/css :sets-count-empty-button)
                             :type "button"
                             :variant "secondary"
                             :on-click on-edit-theme}
                 [:div {:class (stl/css :label-wrapper)}
                  [:> text* {:as "span" :typography "body-medium"}
                   (tr "workspace.token.no-sets")]
                  [:> icon* {:id "arrow-right"}]]])

              [:> icon-button* {:on-click delete-theme
                                :variant "ghost"
                                :aria-label (tr "workspace.token.delete-theme-title")
                                :icon "delete"}]]])]])]

     [:div {:class (stl/css :button-footer)}
      [:> button* {:variant "primary"
                   :type "button"
                   :icon "add"
                   :on-click create-theme}
       (tr "workspace.token.create-theme-title")]]]))

(mf/defc theme-inputs
  [{:keys [theme dropdown-open? on-close-dropdown on-toggle-dropdown on-change-field]}]
  (let [theme-groups (mf/deref refs/workspace-token-theme-groups)
        group-input-ref (mf/use-ref)
        on-update-group (partial on-change-field :group)
        on-update-name (partial on-change-field :name)]
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
                                               (on-update-group value))
                                  :on-close on-close-dropdown}])
      ;; TODO: This span should be remove when labeled-input is updated
      [:span {:class (stl/css :labeled-input-label)}  "Theme group"]
      [:& labeled-input {:label "Group"
                         :input-props {:ref group-input-ref
                                       :default-value (:group theme)
                                       :on-change (comp on-update-group dom/get-target-val)}
                         :render-right (when (seq theme-groups)
                                         (mf/fnc drop-down-button []
                                           [:button {:class (stl/css :group-drop-down-button)
                                                     :type "button"
                                                     :on-click (fn [e]
                                                                 (dom/stop-propagation e)
                                                                 (on-toggle-dropdown))}
                                            [:> icon* {:id "arrow-down"}]]))}]]
     [:div {:class (stl/css :group-input-wrapper)}
      ;; TODO: This span should be remove when labeled-input is updated
      [:span {:class (stl/css :labeled-input-label)}  "Theme"]
      [:& labeled-input {:label "Theme"
                         :input-props {:default-value (:name theme)
                                       :on-change (comp on-update-name dom/get-target-val)}}]]]))

(mf/defc theme-modal-buttons
  [{:keys [close-modal on-save-form disabled?] :as props}]
  [:*
   [:> button* {:variant "secondary"
                :type "button"
                :on-click close-modal}
    (tr "labels.cancel")]
   [:> button* {:variant "primary"
                :type "submit"
                :on-click on-save-form
                :disabled disabled?}
    (tr "workspace.token.save-theme")]])

(mf/defc create-theme
  [{:keys [set-state]}]
  (let [{:keys [dropdown-open? _on-open-dropdown on-close-dropdown on-toggle-dropdown]} (wtco/use-dropdown-open-state)
        theme (ctob/make-token-theme :name "")
        on-back #(set-state (constantly {:type :themes-overview}))
        on-submit #(st/emit! (wdt/create-token-theme %))
        theme-state (mf/use-state theme)
        disabled? (-> (:name @theme-state)
                      (str/trim)
                      (str/empty?))
        on-change-field (fn [field value]
                          (swap! theme-state #(assoc % field value)))
        on-save-form (mf/use-callback
                      (mf/deps theme-state on-submit)
                      (fn [e]
                        (dom/prevent-default e)
                        (let [theme (-> @theme-state
                                        (update :name str/trim)
                                        (update :group str/trim)
                                        (update :description str/trim))]
                          (when-not (str/empty? (:name theme))
                            (on-submit theme)))
                        (on-back)))
        close-modal (mf/use-fn
                     (fn [e]
                       (dom/prevent-default e)
                       (st/emit! (modal/hide))))]
    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (tr "workspace.token.create-theme-title")]
     [:form {:on-submit on-save-form}
      [:div {:class (stl/css :create-theme-wrapper)}
       [:& theme-inputs {:dropdown-open? dropdown-open?
                         :on-close-dropdown on-close-dropdown
                         :on-toggle-dropdown on-toggle-dropdown
                         :theme theme
                         :on-change-field on-change-field}]

       [:div {:class (stl/css :button-footer)}
        [:& theme-modal-buttons {:close-modal close-modal
                                 :on-save-form on-save-form
                                 :disabled? disabled?}]]]]]))

(mf/defc controlled-edit-theme
  [{:keys [state set-state]}]
  (let [{:keys [theme-path]} @state
        [_ theme-group theme-name] theme-path
        token-sets (mf/deref refs/workspace-token-sets-tree)
        theme (mf/deref (refs/workspace-token-theme theme-group theme-name))
        on-back #(set-state (constantly {:type :themes-overview}))
        on-submit #(st/emit! (wdt/update-token-theme [(:group theme) (:name theme)] %))
        {:keys [dropdown-open? _on-open-dropdown on-close-dropdown on-toggle-dropdown]} (wtco/use-dropdown-open-state)
        theme-state (mf/use-state theme)
        disabled? (-> (:name @theme-state)
                      (str/trim)
                      (str/empty?))
        token-set-active? (mf/use-callback
                           (mf/deps theme-state)
                           (fn [set-name]
                             (get-in @theme-state [:sets set-name])))
        on-toggle-token-set (mf/use-callback
                             (mf/deps theme-state)
                             (fn [set-name]
                               (swap! theme-state #(ctob/toggle-set % set-name))))
        on-change-field (fn [field value]
                          (swap! theme-state #(assoc % field value)))
        on-save-form (mf/use-callback
                      (mf/deps theme-state on-submit)
                      (fn [e]
                        (dom/prevent-default e)
                        (let [theme (-> @theme-state
                                        (update :name str/trim)
                                        (update :group str/trim)
                                        (update :description str/trim))]
                          (when-not (str/empty? (:name theme))
                            (on-submit theme)))
                        (on-back)))
        close-modal
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (st/emit! (modal/hide))))

        on-delete-token
        (mf/use-fn
         (mf/deps theme on-back)
         (fn []
           (st/emit! (wdt/delete-token-theme (:group theme) (:name theme)))
           (on-back)))]

    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (tr "workspace.token.edit-theme-title")]

     [:form {:on-submit on-save-form}
      [:div {:class (stl/css :edit-theme-wrapper)}
       [:button {:on-click on-back
                 :class (stl/css :back-btn)
                 :type "button"}
        [:> icon* {:id ic/arrow-left :aria-hidden true}]
        (tr "workspace.token.back-to-themes")]

       [:& theme-inputs {:dropdown-open? dropdown-open?
                         :on-close-dropdown on-close-dropdown
                         :on-toggle-dropdown on-toggle-dropdown
                         :theme theme
                         :on-change-field on-change-field}]
       [:> text* {:as "span"  :typography "body-small" :class (stl/css :select-sets-message)}
        (tr "workspace.token.set-selection-theme")]
       [:div {:class (stl/css :sets-list-wrapper)}

        [:& wts/controlled-sets-list
         {:token-sets token-sets
          :token-set-selected? (constantly false)
          :token-set-active? token-set-active?
          :on-select on-toggle-token-set
          :on-toggle-token-set on-toggle-token-set
          :origin "theme-modal"
          :context sets-context/static-context}]]

       [:div {:class (stl/css :edit-theme-footer)}
        [:> button* {:variant "secondary"
                     :type "button"
                     :icon "delete"
                     :on-click on-delete-token}
         (tr "labels.delete")]
        [:div {:class (stl/css :button-footer)}
         [:& theme-modal-buttons {:close-modal close-modal
                                  :on-save-form on-save-form
                                  :disabled? disabled?}]]]]]]))

(mf/defc themes-modal-body
  [_]
  (let [themes (mf/deref refs/workspace-token-themes-no-hidden)
        state (mf/use-state (if (empty? themes)
                              {:type :create-theme}
                              {:type :themes-overview}))
        set-state (mf/use-callback #(swap! state %))
        component (case (:type @state)
                    :empty-themes empty-themes
                    :themes-overview (if (empty? themes) empty-themes themes-overview)
                    :edit-theme controlled-edit-theme
                    :create-theme create-theme)]
    [:& component {:state state
                   :set-state set-state}]))

(mf/defc token-themes-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :tokens/themes}
  [_args]
  (let [handle-close-dialog (mf/use-callback #(st/emit! (modal/hide)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :on-click handle-close-dialog
                        :aria-label (tr "labels.close")
                        :variant "action"
                        :icon "close"}]
      [:& themes-modal-body]]]))
