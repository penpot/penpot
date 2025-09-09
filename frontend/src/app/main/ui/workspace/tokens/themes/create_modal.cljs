;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.themes.create-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.logic.tokens :as clt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.tokens.sets.lists :as wts]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc empty-themes
  [{:keys [change-view]}]
  (let [create-theme
        (mf/use-fn
         (mf/deps change-view)
         #(change-view :create-theme))]
    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (tr "workspace.tokens.themes-list")]
     [:div {:class (stl/css :empty-themes-wrapper)}
      [:div {:class (stl/css :empty-themes-message)}
       [:> text* {:as "span" :typography "title-medium" :class (stl/css :empty-theme-title)}
        (tr "workspace.tokens.no-themes-currently")]
       [:> text* {:as "span"
                  :class (stl/css :empty-theme-subtitle)
                  :typography "body-medium"}
        (tr "workspace.tokens.create-new-theme")]]
      [:div {:class (stl/css :button-footer)}
       [:> button* {:variant "secondary"
                    :type "button"
                    :on-click modal/hide!}
        (tr "labels.close")]
       [:> button* {:variant "primary"
                    :type "button"
                    :on-click create-theme}
        (tr "workspace.tokens.add-new-theme")]]]]))

(mf/defc switch
  [{:keys [selected? name on-change]}]
  (let [selected (if selected? :on :off)]
    [:& radio-buttons {:selected selected
                       :on-change on-change
                       :name name}
     [:& radio-button {:id :on
                       :value :on
                       :icon deprecated-icon/tick
                       :label ""}]
     [:& radio-button {:id :off
                       :value :off
                       :icon deprecated-icon/close
                       :label ""}]]))

(mf/defc themes-overview
  [{:keys [change-view]}]
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-paths)
        themes-groups (mf/deref refs/workspace-token-theme-tree-no-hidden)

        create-theme
        (mf/use-fn
         (mf/deps change-view)
         (fn [e]
           (dom/prevent-default e)
           (dom/stop-propagation e)
           (change-view :create-theme)))]

    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (tr "workspace.tokens.themes-list")]
     [:> text* {:as "div" :typography "body-medium" :class (stl/css :themes-modal-description)}
      (tr "workspace.tokens.themes-description")]
     [:ul {:class (stl/css :theme-group-wrapper)}
      (for [[group themes] themes-groups]
        [:li {:key (dm/str "token-theme-group" group)}
         (when (seq group)
           [:> heading* {:level 3
                         :class (stl/css :theme-group-label)
                         :typography "body-large"}
            [:div {:class (stl/css :group-title) :title (str (tr "workspace.tokens.group-name") ": " group)}
             [:> icon* {:icon-id i/group :class (stl/css :group-title-icon)}]
             [:> text* {:as "span" :typography "body-medium" :class (stl/css :group-title-name)} group]]])
         [:ul {:class (stl/css :theme-group-rows-wrapper)}
          (for [[_ {:keys [group name] :as theme}] themes
                :let [theme-id (ctob/theme-path theme)
                      selected? (some? (get active-theme-ids theme-id))
                      delete-theme
                      (fn [e]
                        (dom/prevent-default e)
                        (dom/stop-propagation e)
                        (st/emit! (dwtl/delete-token-theme group name)))
                      on-edit-theme
                      (fn [e]
                        (dom/prevent-default e)
                        (dom/stop-propagation e)
                        (change-view :edit-theme {:theme-path [(:id theme) (:group theme) (:name theme)]}))]]
            [:li {:key theme-id
                  :class (stl/css :theme-row)}
             [:div {:class (stl/css :theme-switch-row)}

              ;; FIXME: FIREEEEEEEEEE THIS
              [:div {:on-click (fn [e]
                                 (dom/prevent-default e)
                                 (dom/stop-propagation e)
                                 (st/emit! (dwtl/toggle-token-theme-active? group name)))}
               [:& switch {:name (tr "workspace.tokens.theme-name" name)
                           :on-change (constantly nil)
                           :selected? selected?}]]]
             [:div {:class (stl/css :theme-name-row)}
              [:> text* {:as "span"  :typography "body-medium" :class (stl/css :theme-name) :title name} name]]


             [:div {:class (stl/css :theme-actions-row)}
              (let [sets-count (some-> theme :sets seq count)]
                [:> button* {:class (stl/css-case :sets-count-button sets-count
                                                  :sets-count-empty-button (not sets-count))
                             :variant "secondary"
                             :type "button"
                             :title (tr "workspace.tokens.sets-hint")
                             :on-click on-edit-theme}
                 [:div {:class (stl/css :label-wrapper)}
                  [:> text* {:as "span" :typography "body-medium"}
                   (if sets-count
                     (tr "workspace.tokens.num-active-sets" sets-count)
                     (tr "workspace.tokens.no-active-sets"))]
                  [:> icon* {:icon-id i/arrow-right}]]])

              [:> icon-button* {:on-click delete-theme
                                :variant "ghost"
                                :aria-label (tr "workspace.tokens.delete-theme-title")
                                :icon i/delete}]]])]])]

     [:div {:class (stl/css :button-footer)}
      [:> button* {:variant "secondary"
                   :type "button"
                   :on-click modal/hide!}
       (tr "labels.close")]
      [:> button* {:variant "primary"
                   :type "button"
                   :on-click create-theme}
       (tr "workspace.tokens.add-new-theme")]]]))

(mf/defc theme-inputs*
  [{:keys [theme on-change-field]}]
  (let [theme-groups (mf/deref refs/workspace-token-theme-groups)
        theme-name-ref (mf/use-ref (:name theme))
        options (map (fn [group]
                       {:label group
                        :id group})
                     theme-groups)

        on-update-group
        (mf/use-fn
         (mf/deps on-change-field)
         #(on-change-field :group %))

        on-update-name
        (mf/use-fn
         (mf/deps on-change-field)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value)]
             (on-change-field :name value)
             (mf/set-ref-val! theme-name-ref value))))]

    [:div {:class (stl/css :edit-theme-inputs-wrapper)}
     [:div {:class (stl/css :group-input-wrapper)}
      [:> label* {:for "groups-dropdown" :is-optional true} (tr "workspace.tokens.label.group")]
      [:> combobox* {:id (dm/str "groups-dropdown")
                     :placeholder (tr "workspace.tokens.label.group-placeholder")
                     :default-selected (:group theme)
                     :options (clj->js options)
                     :on-change on-update-group}]]

     [:div {:class (stl/css :group-input-wrapper)}
      [:> input* {:label (tr "workspace.tokens.label.theme")
                  :placeholder (tr "workspace.tokens.label.theme-placeholder")
                  :max-length max-input-length
                  :variant "comfortable"
                  :default-value (mf/ref-val theme-name-ref)
                  :auto-focus true
                  :on-change on-update-name}]]]))

(mf/defc theme-modal-buttons*
  [{:keys [close-modal on-save-form disabled?] :as props}]
  (let [handle-key-down-cancel
        (mf/use-fn
         (mf/deps close-modal)
         (fn [event]
           (when (k/enter? event)
             (close-modal event))))

        handle-key-down-save
        (mf/use-fn
         (mf/deps on-save-form)
         (fn [event]
           (when (k/enter? event)
             (on-save-form event))))]

    [:*
     [:> button* {:variant "secondary"
                  :type "button"
                  :on-click close-modal
                  :on-key-down handle-key-down-cancel}
      (tr "labels.cancel")]
     [:> button* {:variant "primary"
                  :type "submit"
                  :on-click on-save-form
                  :on-key-down handle-key-down-save
                  :disabled disabled?}
      (tr "workspace.tokens.save-theme")]]))

(defn- make-lib-with-theme
  [theme sets]
  (let [tlib (-> (ctob/make-tokens-lib)
                 (ctob/add-theme theme))
        tlib (reduce ctob/add-set tlib sets)]
    (ctob/activate-theme tlib (:group theme) (:name theme))))

(mf/defc edit-create-theme*
  [{:keys [change-view theme on-save is-editing has-prev-view]}]
  (let [ordered-token-sets (mf/deref refs/workspace-ordered-token-sets)
        token-sets (mf/deref refs/workspace-token-sets-tree)

        current-theme* (mf/use-state theme)
        current-theme (deref current-theme*)
        lib (make-lib-with-theme current-theme ordered-token-sets)

        ;; Form / Modal handlers
        on-back (mf/use-fn
                 (mf/deps change-view)
                 #(change-view :themes-overview))
        disabled? (-> (:name current-theme)
                      (str/trim)
                      (str/empty?))

        on-change-field
        (mf/use-fn
         (fn [field value]
           (swap! current-theme* #(assoc % field value))))

        on-save-form
        (mf/use-fn
         (mf/deps current-theme on-save on-back)
         (fn [e]
           (dom/prevent-default e)
           (let [theme' (-> current-theme
                            (update :name str/trim)
                            (update :group str/trim)
                            (update :description str/trim))]
             (when-not (str/empty? (:name theme'))
               (on-save theme'))
             (on-back))))

        close-modal
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (st/emit! (modal/hide))))

        on-delete-theme
        (mf/use-fn
         (mf/deps current-theme on-back)
         (fn []
           (st/emit! (dwtl/delete-token-theme (:group current-theme) (:name current-theme)))
           (on-back)))

        ;; Sets tree handlers
        token-set-group-active?
        (mf/use-fn
         (mf/deps current-theme)
         (fn [group-path]
           (ctob/sets-at-path-all-active? lib group-path)))

        token-set-active?
        (mf/use-fn
         (mf/deps current-theme)
         (fn [name]
           (contains? (:sets current-theme) name)))

        on-toggle-token-set
        (mf/use-fn
         (mf/deps current-theme)
         (fn [set-name]
           (swap! current-theme* #(ctob/toggle-set % set-name))))

        on-toggle-token-set-group
        (mf/use-fn
         (mf/deps current-theme ordered-token-sets)
         (fn [group-path]
           (swap! current-theme* (fn [theme']
                                   (let [lib' (make-lib-with-theme theme' ordered-token-sets)]
                                     (clt/toggle-token-set-group group-path lib' theme'))))))

        on-click-token-set
        (mf/use-fn
         (mf/deps on-toggle-token-set)
         (fn [prefixed-set-path-str]
           (let [set-name (ctob/prefixed-set-path-string->set-name-string prefixed-set-path-str)]
             (on-toggle-token-set set-name))))]

    [:div {:class (stl/css :themes-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :themes-modal-title)}
      (if is-editing
        (tr "workspace.tokens.edit-theme-title")
        (tr "workspace.tokens.add-new-theme"))]

     [:form {:on-submit on-save-form :class (stl/css :edit-theme-form)}
      [:div {:class (stl/css :edit-theme-wrapper)}
       (when has-prev-view
         [:button {:on-click on-back
                   :class (stl/css :back-btn)
                   :type "button"}
          [:> icon* {:icon-id i/arrow-left :aria-hidden true}]
          (tr "workspace.tokens.back-to-themes")])

       [:> theme-inputs* {:theme current-theme
                          :on-change-field on-change-field}]
       [:> text* {:as "span"  :typography "body-small" :class (stl/css :select-sets-message)}
        (tr "workspace.tokens.set-selection-theme")]
       [:div {:class (stl/css :sets-list-wrapper)}

        [:> wts/controlled-sets-list*
         {:token-sets token-sets
          :is-token-set-active token-set-active?
          :is-token-set-group-active token-set-group-active?
          :on-select on-click-token-set
          :can-edit false
          :on-toggle-token-set on-toggle-token-set
          :on-toggle-token-set-group on-toggle-token-set-group
          :origin "theme-modal"}]]

       [:div {:class (stl/css :edit-theme-footer)}
        (when is-editing
          [:> button* {:variant "secondary"
                       :type "button"
                       :icon i/delete
                       :on-click on-delete-theme}
           (tr "labels.delete")])
        [:div {:class (stl/css :button-footer)}
         [:> theme-modal-buttons* {:close-modal close-modal
                                   :on-save-form on-save-form
                                   :disabled? disabled?}]]]]]]))

(defn has-prev-view [prev-view-type]
  (contains? #{:empty-themes :themes-overview} prev-view-type))

(mf/defc edit-theme
  [{:keys [state change-view]}]
  (let [{:keys [theme-path]} state
        [_ theme-group theme-name] theme-path
        theme (mf/deref (refs/workspace-token-theme theme-group theme-name))
        has-prev-view (has-prev-view (:prev-type state))

        on-save
        (mf/use-fn
         (mf/deps theme)
         (fn [theme']
           (st/emit! (dwtl/update-token-theme [(:group theme) (:name theme)] theme'))))]

    [:> edit-create-theme*
     {:change-view change-view
      :theme theme
      :on-save on-save
      :is-editing true
      :has-prev-view has-prev-view}]))

(mf/defc create-theme
  [{:keys [state change-view]}]
  (let [theme (ctob/make-token-theme :name "")
        on-save
        (mf/use-fn
         (fn [theme]
           (st/emit! (ptk/event ::ev/event {::ev/name "create-tokens-theme"})
                     (dwtl/create-token-theme theme))))
        has-prev-view (has-prev-view (:prev-type state))]

    [:> edit-create-theme*
     {:change-view change-view
      :theme theme
      :on-save on-save
      :has-prev-view has-prev-view}]))

(mf/defc themes-modal-body*
  {::mf/private true}
  []
  (let [themes      (mf/deref refs/workspace-token-themes-no-hidden)
        state*      (mf/use-state #(if (empty? themes)
                                     {:type :create-theme}
                                     {:type :themes-overview}))
        state       (deref state*)

        change-view (mf/use-fn
                     (fn [type & {:keys [theme-path]}]
                       (swap! state* (fn [current-state]
                                       (cond-> current-state
                                         :always (assoc :type type
                                                        :prev-type (:type current-state))
                                         :theme-path (assoc :theme-path theme-path))))))

        component (case (:type state)
                    :empty-themes empty-themes
                    :themes-overview (if (empty? themes) empty-themes themes-overview)
                    :edit-theme edit-theme
                    :create-theme create-theme)]
    [:& component {:state state
                   :change-view change-view}]))

(mf/defc token-themes-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :tokens/themes}
  []
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-dialog)
          :data-testid "token-theme-update-create-modal"}
    [:> icon-button* {:class (stl/css :close-btn)
                      :on-click modal/hide!
                      :aria-label (tr "labels.close")
                      :variant "action"
                      :icon i/close}]
    [:> themes-modal-body*]]])
