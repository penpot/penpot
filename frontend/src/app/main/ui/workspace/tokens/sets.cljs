;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as ic]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.hooks :as h]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- on-start-creation
  []
  (st/emit! (dt/start-token-set-creation [])))

(defn- on-toggle-token-set-click [name]
  (st/emit! (dt/toggle-token-set name)))

(defn- on-toggle-token-set-group-click [path]
  (st/emit! (dt/toggle-token-set-group path)))

(defn- on-select-token-set-click [name]
  (st/emit! (dt/set-selected-token-set-name name)))

(defn on-update-token-set
  [token-set name]
  (st/emit! (dt/clear-token-set-edition)
            (dt/update-token-set token-set name)))

(defn- on-update-token-set-group
  [path name]
  (st/emit! (dt/clear-token-set-edition)
            (dt/rename-token-set-group path name)))

(defn- on-create-token-set
  [parent-set name]
  (let [;; FIXME: this code should be reusable under helper under
        ;; common types namespace
        name
        (if-let [parent-path (ctob/get-token-set-path parent-set)]
          (->> (concat parent-path (ctob/split-token-set-name name))
               (ctob/join-set-path))
          (ctob/normalize-set-name name))]

    (st/emit! (ptk/data-event ::ev/event {::ev/name "create-token-set" :name name})
              (dt/create-token-set name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc editing-label*
  {::mf/private true}
  [{:keys [default-value on-cancel on-submit]}]
  (let [on-submit
        (mf/use-fn
         (mf/deps on-cancel on-submit default-value)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (if (or (str/empty? value)
                     (= value default-value))
               (on-cancel)
               (on-submit value)))))

        on-key-down
        (mf/use-fn
         (mf/deps on-submit on-cancel)
         (fn [event]
           (cond
             (kbd/enter? event) (on-submit event)
             (kbd/esc? event) (on-cancel))))]
    [:input
     {:class (stl/css :editing-node)
      :type "text"
      :on-blur on-submit
      :on-key-down on-key-down
      :maxlength "256"
      :auto-focus true
      :placeholder (tr "workspace.token.set-edit-placeholder")
      :default-value default-value}]))

(mf/defc checkbox*
  [{:keys [checked aria-label on-click disabled]}]
  (let [all?     (true? checked)
        mixed?   (= checked "mixed")
        checked? (or all? mixed?)]

    [:div {:role "checkbox"
           :aria-checked (dm/str checked)
           :disabled disabled
           :title (when disabled (tr "workspace.token.no-permisions-set"))
           :tab-index 0
           :class (stl/css-case :checkbox-style true
                                :checkbox-checked-style checked?
                                :checkbox-disabled-checked (and checked? disabled)
                                :checkbox-disabled disabled)
           :on-click (when-not disabled on-click)}

     (when ^boolean checked?
       [:> icon*
        {:aria-label aria-label
         :class (stl/css :check-icon)
         :size "s"
         :icon-id (if mixed? ic/remove ic/tick)}])]))

(mf/defc inline-add-button*
  []
  (let [can-edit? (mf/use-ctx ctx/can-edit?)]
    (if can-edit?
      [:div {:class (stl/css :empty-sets-wrapper)}
       [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
        (tr "workspace.token.no-sets-yet")]
       [:button {:on-click on-start-creation
                 :class (stl/css :create-set-button)}
        (tr "workspace.token.create-one")]]
      [:div {:class (stl/css :empty-sets-wrapper)}
       [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
        (tr "workspace.token.no-sets-yet")]])))

(mf/defc add-button*
  []
  [:> icon-button* {:variant "ghost"
                    :icon "add"
                    :on-click on-start-creation
                    :aria-label (tr "workspace.token.add set")}])

(mf/defc sets-tree-set-group*
  {::mf/private true}
  [{:keys [id label tree-depth tree-path is-active is-selected is-draggable is-collapsed tree-index on-drop
           on-toggle-collapse on-toggle is-editing on-start-edition on-reset-edition on-edit-submit]}]

  (let [can-edit?
        (mf/use-ctx ctx/can-edit?)

        label-id
        (str id "-label")

        on-context-menu
        (mf/use-fn
         (mf/deps is-editing id tree-path can-edit?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when (and can-edit? (not is-editing))
             (st/emit! (dt/assign-token-set-context-menu
                        {:position (dom/get-client-position event)
                         :is-group true
                         :id id
                         :path tree-path})))))

        on-collapse-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (on-toggle-collapse tree-path)))

        on-double-click
        (mf/use-fn (mf/deps id) #(on-start-edition id))

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
         (mf/deps tree-index on-drop)
         (fn [position data]
           (on-drop tree-index position data)))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/token-set"
         :on-drop on-drop
         :data {:index tree-index
                :is-group true}
         :detect-center? true
         :draggable? is-draggable)]

    [:div {:ref dref
           :role "button"
           :aria-labelledby label-id
           :data-testid "tokens-set-group-item"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :set-item-group true
                                :selected-set is-selected
                                :dnd-over     (= (:over dprops) :center)
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))
           :on-context-menu on-context-menu}
     [:> icon-button*
      {:class (stl/css :set-item-group-collapse-button)
       :on-click on-collapse-click
       :aria-label (tr "labels.collapse")
       :icon (if is-collapsed "arrow-right" "arrow-down")
       :variant "action"}]
     (if is-editing
       [:> editing-label*
        {:default-value label
         :on-cancel on-reset-edition
         :on-submit on-edit-submit'}]
       [:*
        [:div {:class (stl/css :set-name)
               :title label
               :on-double-click on-double-click
               :id label-id}
         label]
        [:> checkbox*
         {:on-click on-checkbox-click
          :disabled (not can-edit?)
          :checked (case is-active
                     :all true
                     :partial "mixed"
                     :none false)
          :arial-label (tr "workspace.token.select-set")}]])]))

(mf/defc sets-tree-set*
  [{:keys [id set label tree-depth tree-path tree-index is-selected is-active is-draggable is-editing
           on-select on-drop on-toggle on-start-edition on-reset-edition on-edit-submit]}]

  (let [set-name  (get set :name)
        can-edit? (mf/use-ctx ctx/can-edit?)

        on-click
        (mf/use-fn
         (mf/deps is-editing tree-path)
         (fn [event]
           (dom/stop-propagation event)
           (when-not is-editing
             (when (fn? on-select)
               (on-select set-name)))))

        on-context-menu
        (mf/use-fn
         (mf/deps is-editing id tree-path can-edit?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when (and can-edit? (not is-editing))
             (st/emit! (dt/assign-token-set-context-menu
                        {:position (dom/get-client-position event)
                         :is-group false
                         :id id
                         :path tree-path})))))

        on-double-click
        (mf/use-fn (mf/deps id) #(on-start-edition id))

        on-checkbox-click
        (mf/use-fn
         (mf/deps set-name on-toggle)
         (fn [event]
           (dom/stop-propagation event)
           (when (fn? on-toggle)
             (on-toggle set-name))))

        on-edit-submit'
        (mf/use-fn
         (mf/deps set on-edit-submit)
         #(on-edit-submit set %))

        on-drag
        (mf/use-fn
         (mf/deps tree-path)
         (fn [_]
           (when-not is-selected
             (on-select tree-path))))

        on-drop
        (mf/use-fn
         (mf/deps tree-index on-drop)
         (fn [position data]
           (on-drop tree-index position data)))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/token-set"
         :on-drag on-drag
         :on-drop on-drop
         :data {:index tree-index
                :is-group false}
         :draggable? is-draggable)

        drop-over
        (get dprops :over)]

    [:div {:ref dref
           :role "button"
           :data-testid "tokens-set-item"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :selected-set is-selected
                                :dnd-over     (= drop-over :center)
                                :dnd-over-top (= drop-over :top)
                                :dnd-over-bot (= drop-over :bot))
           :on-click on-click
           :on-context-menu on-context-menu
           :aria-checked is-active}

     [:> icon*
      {:icon-id "document"
       :class (stl/css-case :icon true
                            :root-icon (not tree-depth))}]
     (if is-editing
       [:> editing-label*
        {:default-value label
         :on-cancel on-reset-edition
         :on-submit on-edit-submit'}]
       [:*
        [:div {:class (stl/css :set-name)
               :on-double-click on-double-click}
         label]
        [:> checkbox*
         {:on-click on-checkbox-click
          :disabled (not can-edit?)
          :arial-label (tr "workspace.token.select-set")
          :checked is-active}]])]))

(mf/defc token-sets-tree*
  [{:keys [is-draggable
           selected
           is-token-set-group-active
           is-token-set-active
           on-start-edition
           on-reset-edition
           on-edit-submit-set
           on-edit-submit-group
           on-select
           on-toggle-set
           on-toggle-set-group
           token-sets
           new-path
           edition-id]}]

  (let [collapsed-paths* (mf/use-state #{})
        collapsed-paths  (deref collapsed-paths*)

        collapsed?
        (mf/use-fn
         (mf/deps collapsed-paths)
         (partial contains? collapsed-paths))

        on-drop
        (mf/use-fn
         (mf/deps collapsed-paths)
         (fn [tree-index position data]
           (let [params {:from-index (:index data)
                         :to-index tree-index
                         :position position
                         :collapsed-paths collapsed-paths}]
             (if (:is-group data)
               (st/emit! (dt/drop-token-set-group params))
               (st/emit! (dt/drop-token-set params))))))

        on-toggle-collapse
        (mf/use-fn
         (fn [path]
           (swap! collapsed-paths* #(if (contains? % path)
                                      (disj % path)
                                      (conj % path)))))]

    (for [{:keys [id token-set index is-new is-group path parent-path depth] :as node}
          (ctob/sets-tree-seq token-sets
                              {:skip-children-pred collapsed?
                               :new-at-path new-path})]
      (cond
        ^boolean is-group
        [:> sets-tree-set-group*
         {:key index
          :label (peek path)
          :id id
          :is-active (is-token-set-group-active path)
          :is-selected false
          :is-draggable is-draggable
          :is-editing (= edition-id id)
          :is-collapsed (collapsed? path)
          :on-select on-select

          :tree-path path
          :tree-depth depth
          :tree-index index
          :tree-parent-path parent-path

          :on-drop on-drop
          :on-start-edition on-start-edition
          :on-reset-edition on-reset-edition
          :on-edit-submit on-edit-submit-group
          :on-toggle-collapse on-toggle-collapse
          :on-toggle on-toggle-set-group}]

        ^boolean is-new
        [:> sets-tree-set*
         {:key index
          :set token-set
          :label ""
          :id id
          :is-editing true
          :is-active true
          :is-selected true

          :tree-path path
          :tree-depth depth
          :tree-index index
          :tree-parent-path parent-path

          :on-drop on-drop
          :on-reset-edition on-reset-edition
          :on-edit-submit on-create-token-set}]

        :else
        [:> sets-tree-set*
         {:key index
          :set token-set
          :id  id
          :label (peek path)
          :is-editing (= edition-id id)
          :is-active (is-token-set-active id)
          :is-selected (= selected id)
          :is-draggable is-draggable
          :on-select on-select
          :tree-path path
          :tree-depth depth
          :tree-index index
          :tree-parent-path parent-path
          :on-toggle on-toggle-set
          :edition-id edition-id
          :on-start-edition on-start-edition
          :on-drop on-drop
          :on-reset-edition on-reset-edition
          :on-edit-submit on-edit-submit-set}]))))

(mf/defc controlled-sets-list*
  {::mf/props :obj}
  [{:keys [token-sets
           selected
           on-update-token-set
           on-update-token-set-group
           is-token-set-active
           is-token-set-group-active
           on-create-token-set
           on-toggle-token-set
           on-toggle-token-set-group
           on-start-edition
           on-reset-edition
           origin
           on-select
           new-path
           edition-id]}]

  (assert (fn? is-token-set-group-active) "expected a function for `is-token-set-group-active` prop")
  (assert (fn? is-token-set-active) "expected a function for `is-token-set-active` prop")

  (let [theme-modal? (= origin "theme-modal")
        can-edit?    (mf/use-ctx ctx/can-edit?)
        draggable?   (and (not theme-modal?) can-edit?)
        empty-state? (and theme-modal?
                          (empty? token-sets)
                          (not new-path))

        ;; NOTE: on-reset-edition and on-start-edition function can
        ;; come as nil, in this case we need to provide a safe
        ;; fallback for them
        on-reset-edition
        (mf/use-fn
         (mf/deps on-reset-edition)
         (fn [v]
           (when (fn? on-reset-edition)
             (on-reset-edition v))))

        on-start-edition
        (mf/use-fn
         (mf/deps on-start-edition)
         (fn [v]
           (when (fn? on-start-edition)
             (on-start-edition v))))]

    [:div {:class (stl/css :sets-list)}
     (if ^boolean empty-state?
       [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message-sets)}
        (tr "workspace.token.no-sets-create")]

       [:> token-sets-tree*
        {:is-draggable draggable?
         :new-path new-path
         :edition-id edition-id
         :token-sets token-sets
         :selected selected
         :on-select on-select
         :is-token-set-active is-token-set-active
         :is-token-set-group-active is-token-set-group-active
         :on-toggle-set on-toggle-token-set
         :on-toggle-set-group on-toggle-token-set-group
         :on-create-token-set on-create-token-set
         :on-start-edition on-start-edition
         :on-reset-edition on-reset-edition
         :on-edit-submit-set on-update-token-set
         :on-edit-submit-group on-update-token-set-group}])]))

(mf/defc sets-list*
  [{:keys [tokens-lib selected new-path edition-id]}]

  (let [token-sets
        (some-> tokens-lib (ctob/get-set-tree))

        can-edit?
        (mf/use-ctx ctx/can-edit?)

        active-token-sets-names
        (mf/with-memo [tokens-lib]
          (some-> tokens-lib (ctob/get-active-themes-set-names)))

        token-set-active?
        (mf/use-fn
         (mf/deps active-token-sets-names)
         (fn [name]
           (contains? active-token-sets-names name)))

        token-set-group-active?
        (mf/use-fn
         (fn [group-path]
           ;; FIXME
           @(refs/token-sets-at-path-all-active group-path)))

        on-reset-edition
        (mf/use-fn
         (mf/deps can-edit?)
         (fn [_]
           (when can-edit?
             (st/emit! (dt/clear-token-set-edition)
                       (dt/clear-token-set-creation)))))

        on-start-edition
        (mf/use-fn
         (mf/deps can-edit?)
         (fn [id]
           (when can-edit?
             (st/emit! (dt/start-token-set-edition id)))))]

    [:> controlled-sets-list*
     {:token-sets token-sets

      :is-token-set-active token-set-active?
      :is-token-set-group-active token-set-group-active?
      :on-select on-select-token-set-click

      :selected selected
      :new-path new-path
      :edition-id edition-id

      :origin "set-panel"
      :can-edit can-edit?
      :on-start-edition on-start-edition
      :on-reset-edition on-reset-edition

      :on-toggle-token-set on-toggle-token-set-click
      :on-toggle-token-set-group on-toggle-token-set-group-click
      :on-update-token-set on-update-token-set
      :on-update-token-set-group on-update-token-set-group
      :on-create-token-set on-create-token-set}]))
