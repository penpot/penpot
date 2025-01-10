;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.tokens :as wdt]
   [app.main.data.workspace.tokens.selected-set :as dwts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as ic]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn on-toggle-token-set-click [token-set-name]
  (st/emit! (wdt/toggle-token-set {:token-set-name token-set-name})))

(defn on-toggle-token-set-group-click [group-path]
  (st/emit! (wdt/toggle-token-set-group group-path)))

(defn on-select-token-set-click [set-name]
  (st/emit! (dwts/set-selected-token-set-name set-name)))

(defn on-update-token-set [set-name token-set]
  (st/emit! (wdt/update-token-set set-name token-set)))

(defn on-update-token-set-group [set-group-path set-group-fname]
  (st/emit! (wdt/rename-token-set-group set-group-path set-group-fname)))

(defn on-create-token-set [_ token-set]
  (st/emit! (ptk/event ::ev/event {::ev/name "create-tokens-set"}))
  (st/emit! (wdt/create-token-set token-set)))

(mf/defc editing-label
  [{:keys [default-value on-cancel on-submit]}]
  (let [ref (mf/use-ref)
        on-submit-valid (mf/use-fn
                         (fn [event]
                           (let [value (str/trim (dom/get-target-val event))]
                             (if (or (str/empty? value)
                                     (= value default-value))
                               (on-cancel)
                               (do
                                 (on-submit value)
                                 (on-cancel))))))
        on-key-down (mf/use-fn
                     (fn [event]
                       (cond
                         (kbd/enter? event) (on-submit-valid event)
                         (kbd/esc? event) (on-cancel))))]
    [:input
     {:class (stl/css :editing-node)
      :type "text"
      :ref ref
      :on-blur on-submit-valid
      :on-key-down on-key-down
      :auto-focus true
      :default-value default-value}]))

(mf/defc checkbox
  [{:keys [checked aria-label on-click disabled]}]
  (let [all? (true? checked)
        mixed? (= checked "mixed")
        checked? (or all? mixed?)
        on-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [e]
           (when-not disabled
             (on-click e))))]
    [:div {:role "checkbox"
           :aria-checked (dm/str checked)
           :disabled disabled
           :title (when disabled (tr "workspace.token.no-permisions-set"))
           :tab-index 0
           :class (stl/css-case :checkbox-style true
                                :checkbox-checked-style checked?
                                :checkbox-disabled-checked (and checked? disabled)
                                :checkbox-disabled  disabled)
           :on-click on-click}

     (when checked?
       [:> icon*
        {:aria-label aria-label
         :class (stl/css :check-icon)
         :size "s"
         :icon-id (if mixed? ic/remove ic/tick)}])]))

(mf/defc sets-tree-set-group
  [{:keys [label tree-depth tree-path active? selected? draggable? on-toggle-collapse on-toggle editing-id editing? on-edit on-edit-reset on-edit-submit collapsed-paths tree-index]}]
  (let [active?'   (active? tree-path)
        editing?'  (editing? editing-id)
        collapsed? (some? (get @collapsed-paths tree-path))
        can-edit?  (:can-edit (deref refs/permissions))

        on-context-menu
        (mf/use-fn
         (mf/deps editing?' editing-id can-edit?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not editing?'
             (st/emit!
              (wdt/show-token-set-context-menu
               {:position (dom/get-client-position event)
                :group? true
                :path tree-path})))))

        on-collapse-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (on-toggle-collapse tree-path)))

        on-double-click
        (mf/use-fn
         (mf/deps editing-id can-edit?)
         #(on-edit editing-id))

        on-checkbox-click
        (mf/use-fn
         (mf/deps on-toggle tree-path can-edit?)
         #(on-toggle tree-path))

        on-edit-submit'
        (mf/use-fn
         (mf/deps tree-path on-edit-submit can-edit?)
         #(on-edit-submit tree-path %))

        on-drop
        (mf/use-fn
         (mf/deps tree-index collapsed-paths)
         (fn [position data]
           (let [props {:from-index (:index data)
                        :to-index tree-index
                        :position position
                        :collapsed-paths @collapsed-paths}]
             (if (:group? data)
               (st/emit! (wdt/drop-token-set-group props))
               (st/emit! (wdt/drop-token-set props))))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/token-set"
         :on-drop on-drop
         :data {:index tree-index
                :group? true}
         :detect-center? true
         :draggable? draggable?)]

    [:div {:ref dref
           :role "button"
           :data-testid "tokens-set-group-item"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :set-item-group true
                                :selected-set selected?
                                :dnd-over     (= (:over dprops) :center)
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))
           :on-context-menu on-context-menu}
     [:> icon-button*
      {:class (stl/css :set-item-group-collapse-button)
       :on-click on-collapse-click
       :aria-label (tr "labels.collapse")
       :icon (if collapsed? "arrow-right" "arrow-down")
       :variant "action"}]
     (if editing?'
       [:& editing-label
        {:default-value label
         :on-cancel on-edit-reset
         :on-create on-edit-reset
         :on-submit on-edit-submit'}]
       [:*
        [:div {:class (stl/css :set-name)
               :on-double-click on-double-click}
         label]
        [:& checkbox
         {:on-click on-checkbox-click
          :disabled (not can-edit?)
          :checked (case active?'
                     :all true
                     :partial "mixed"
                     :none false)
          :arial-label (tr "workspace.token.select-set")}]])]))

(mf/defc sets-tree-set
  [{:keys [set label tree-depth tree-path tree-index selected? on-select active? draggable? on-toggle editing-id editing? on-edit on-edit-reset on-edit-submit collapsed-paths]}]
  (let [set-name  (.-name set)
        editing?' (editing? editing-id)
        active?'  (some? (active? set-name))
        can-edit?  (:can-edit (deref refs/permissions))

        on-click
        (mf/use-fn
         (mf/deps editing?' tree-path)
         (fn [event]
           (dom/stop-propagation event)
           (when-not editing?'
             (on-select set-name))))

        on-context-menu
        (mf/use-fn
         (mf/deps editing?' tree-path can-edit?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when (and can-edit? (not editing?'))
             (st/emit!
              (wdt/show-token-set-context-menu
               {:position (dom/get-client-position event)
                :group? false
                :path tree-path})))))

        on-double-click
        (mf/use-fn
         (mf/deps editing-id)
         (fn []
           (on-edit editing-id)))

        on-checkbox-click
        (mf/use-fn
         (mf/deps set-name)
         (fn [event]
           (dom/stop-propagation event)
           (on-toggle set-name)))

        on-edit-submit'
        (mf/use-fn
         (mf/deps set on-edit-submit)
         #(on-edit-submit set-name (ctob/update-name set %)))

        on-drag
        (mf/use-fn
         (mf/deps tree-path)
         (fn [_]
           (when-not selected?
             (on-select tree-path))))

        on-drop
        (mf/use-fn
         (mf/deps tree-index collapsed-paths)
         (fn [position data]
           (let [props {:from-index (:index data)
                        :to-index tree-index
                        :position position
                        :collapsed-paths @collapsed-paths}]
             (if (:group? data)
               (st/emit! (wdt/drop-token-set-group props))
               (st/emit! (wdt/drop-token-set props))))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/token-set"
         :on-drag on-drag
         :on-drop on-drop
         :data {:index tree-index
                :group? false}
         :draggable? draggable?)]

    [:div {:ref dref
           :role "button"
           :data-testid "tokens-set-item"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :selected-set selected?
                                :dnd-over     (= (:over dprops) :center)
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))
           :on-click on-click
           :on-context-menu on-context-menu
           :aria-checked active?'}
     [:> icon*
      {:icon-id "document"
       :class (stl/css-case :icon true
                            :root-icon (not tree-depth))}]
     (if editing?'
       [:& editing-label
        {:default-value label
         :on-cancel on-edit-reset
         :on-create on-edit-reset
         :on-submit on-edit-submit'}]
       [:*
        [:div {:class (stl/css :set-name)
               :on-double-click on-double-click}
         label]
        [:& checkbox
         {:on-click on-checkbox-click
          :disabled (not can-edit?)
          :arial-label (tr "workspace.token.select-set")
          :checked active?'}]])]))

(mf/defc sets-tree
  [{:keys [draggable?
           active?
           selected?
           group-active?
           editing?
           on-edit-reset
           on-edit-submit-set
           on-edit-submit-group
           on-select
           on-toggle-set
           on-toggle-set-group
           set-node]
    :as props}]
  (let [{:keys [on-edit] :as ctx} (sets-context/use-context)
        collapsed-paths (mf/use-state #{})
        collapsed?
        (mf/use-fn
         #(contains? @collapsed-paths %))

        on-toggle-collapse
        (mf/use-fn
         (fn [path]
           (swap! collapsed-paths #(if (contains? % path)
                                     (disj % path)
                                     (conj % path)))))]
    (for [[index {:keys [group? path parent-path depth] :as node}]
          (d/enumerate (ctob/walk-sets-tree-seq set-node :walk-children? #(contains? @collapsed-paths %)))]
      (if (not group?)
        (let [editing-id (sets-context/set-path->id path)]
          [:& sets-tree-set
           {:key editing-id
            :set (:set node)
            :label (last path)
            :active? active?
            :selected? (selected? (get-in node [:set :name]))
            :draggable? draggable?
            :on-select on-select
            :tree-path path
            :tree-depth depth
            :tree-index index
            :tree-parent-path parent-path
            :on-toggle on-toggle-set
            :editing-id editing-id
            :editing? editing?
            :on-edit on-edit
            :on-edit-reset on-edit-reset
            :on-edit-submit on-edit-submit-set
            :collapsed-paths collapsed-paths}])
        (let [editing-id (sets-context/set-group-path->id path)]
          [:& sets-tree-set-group
           {:key editing-id
            :label (last path)
            :active? group-active?
            :selected? false
            :draggable? draggable?
            :on-select on-select
            :tree-path path
            :tree-depth depth
            :tree-index index
            :tree-parent-path parent-path
            :editing-id editing-id
            :editing? editing?
            :on-edit on-edit
            :on-edit-reset on-edit-reset
            :on-edit-submit on-edit-submit-group
            :collapsed? (collapsed? path)
            :on-toggle-collapse on-toggle-collapse
            :on-toggle on-toggle-set-group
            :collapsed-paths collapsed-paths}])))))

(mf/defc controlled-sets-list
  [{:keys [token-sets
           on-update-token-set
           on-update-token-set-group
           token-set-selected?
           token-set-active?
           token-set-group-active?
           on-create-token-set
           on-toggle-token-set
           on-toggle-token-set-group
           origin
           on-select
           context]
    :as _props}]
  (let [{:keys [editing? new? on-edit on-reset] :as ctx} (or context (sets-context/use-context))
        theme-modal? (= origin "theme-modal")
        can-edit? (:can-edit (deref refs/permissions))
        draggable? (and (not theme-modal?) can-edit?)]
    [:fieldset {:class (stl/css :sets-list)}
     (if (and theme-modal?
              (empty? token-sets))
       [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message-sets)}
        (tr "workspace.token.no-sets-create")]
       (if (and theme-modal?
                (empty? token-sets))
         [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message-sets)}
          (tr "workspace.token.no-sets-create")]
         [:*
          [:& sets-tree
           {:draggable? draggable?
            :set-node token-sets
            :selected? token-set-selected?
            :on-select on-select
            :active? token-set-active?
            :group-active? token-set-group-active?
            :on-toggle-set on-toggle-token-set
            :on-toggle-set-group on-toggle-token-set-group
            :editing? editing?
            :on-edit on-edit
            :on-edit-reset on-reset
            :on-edit-submit-set on-update-token-set
            :on-edit-submit-group on-update-token-set-group}]
          (when new?
            [:& sets-tree-set
             {:set (ctob/make-token-set :name "")
              :label ""
              :selected? (constantly true)
              :active? (constantly true)
              :editing? (constantly true)
              :on-select (constantly nil)
              :on-edit (constantly nil)
              :on-edit-reset on-reset
              :on-edit-submit on-create-token-set}])]))]))


(mf/defc sets-list
  [{:keys []}]
  (let [token-sets (mf/deref refs/workspace-token-sets-tree)
        selected-token-set-name (mf/deref refs/workspace-selected-token-set-name)
        token-set-selected? (mf/use-fn
                             (mf/deps token-sets selected-token-set-name)
                             (fn [set-name]
                               (= set-name selected-token-set-name)))
        active-token-set-names (mf/deref refs/workspace-active-set-names)
        token-set-active? (mf/use-fn
                           (mf/deps active-token-set-names)
                           (fn [set-name]
                             (get active-token-set-names set-name)))
        token-set-group-active? (mf/use-fn
                                 (fn [group-path]
                                   @(refs/token-sets-at-path-all-active group-path)))]
    [:& controlled-sets-list
     {:token-sets token-sets
      :token-set-selected? token-set-selected?
      :token-set-active? token-set-active?
      :token-set-group-active? token-set-group-active?
      :on-select on-select-token-set-click
      :origin "set-panel"
      :on-toggle-token-set on-toggle-token-set-click
      :on-toggle-token-set-group on-toggle-token-set-group-click
      :on-update-token-set on-update-token-set
      :on-update-token-set-group on-update-token-set-group
      :on-create-token-set on-create-token-set}]))
