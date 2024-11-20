;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as ic]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn on-toggle-token-set-click [token-set-name]
  (st/emit! (wdt/toggle-token-set {:token-set-name token-set-name})))

(defn on-select-token-set-click [tree-path]
  (st/emit! (wdt/set-selected-token-set-id tree-path)))

(defn on-update-token-set [set-name token-set]
  (st/emit! (wdt/update-token-set set-name token-set)))

(defn on-create-token-set [_ token-set]
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

(mf/defc sets-tree-set-group
  [{:keys [label tree-depth tree-path selected? collapsed? on-select editing? on-edit on-edit-reset on-edit-submit]}]
  (let [editing?' (editing? tree-path)
        on-click
        (mf/use-fn
         (mf/deps editing? tree-path)
         (fn [event]
           (dom/stop-propagation event)
           (when-not (editing? tree-path)
             (on-select tree-path))))

        on-context-menu
        (mf/use-fn
         (mf/deps editing? tree-path)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not (editing? tree-path)
             (st/emit!
              (wdt/show-token-set-context-menu
               {:position (dom/get-client-position event)
                :tree-path tree-path})))))]
    [:div {;; :ref dref
           :role "button"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :selected-set selected?)
           :on-click on-click
           :on-context-menu on-context-menu
           :on-double-click #(on-edit tree-path)}
     [:> icon-button*
      {:on-click (fn [event]
                   (.stopPropagation event)
                   (swap! collapsed? not))
       :aria-label (tr "labels.collapse")
       :icon (if @collapsed? "arrow-right" "arrow-down")
       :variant "action"}]
     [:> icon*
      {:id "group"
       :class (stl/css :icon)}]
     (if editing?'
       [:& editing-label
        {:default-value label
         :on-cancel on-edit-reset
         :on-create on-edit-reset
         :on-submit #(on-edit-submit)}]
       [:div {:class (stl/css :set-name)} label])]))

(mf/defc sets-tree-set
  [{:keys [set label tree-depth tree-path selected? on-select active? on-toggle editing? on-edit on-edit-reset on-edit-submit]}]
  (let [set-name (.-name set)
        editing?' (editing? tree-path)
        active?' (active? set-name)
        on-click
        (mf/use-fn
         (mf/deps editing?' tree-path)
         (fn [event]
           (dom/stop-propagation event)
           (when-not editing?'
             (on-select tree-path))))

        on-context-menu
        (mf/use-fn
         (mf/deps editing?' tree-path)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not editing?'
             (st/emit!
              (wdt/show-token-set-context-menu
               {:position (dom/get-client-position event)
                :tree-path tree-path})))))]
    [:div {;; :ref dref
           :role "button"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :selected-set selected?)
           :on-click on-click
           :on-double-click #(on-edit tree-path)
           :on-context-menu on-context-menu}
     [:> icon*
      {:id "document"
       :class (stl/css-case :icon true
                            :root-icon (not tree-depth))}]
     (if editing?'
       [:& editing-label
        {:default-value label
         :on-cancel on-edit-reset
         :on-create on-edit-reset
         :on-submit #(on-edit-submit set-name (ctob/update-name set %))}]
       [:*
        [:div {:class (stl/css :set-name)} label]
        [:button {:on-click (fn [event]
                              (dom/stop-propagation event)
                              (on-toggle set-name))
                  :class (stl/css-case :checkbox-style true
                                       :checkbox-checked-style active?')}
         (when active?'
           [:> icon* {:aria-label (tr "workspace.token.select-set")
                      :class (stl/css :check-icon)
                      :size "s"
                      :id ic/tick}])]])]))

(mf/defc sets-tree
  [{:keys [set-path set-node tree-depth tree-path on-select selected? on-toggle active? editing? on-edit on-edit-reset on-edit-submit]
    :or {tree-depth 0}
    :as props}]
  (let [[set-prefix set-path'] (some-> set-path (ctob/split-set-prefix))
        set? (instance? ctob/TokenSet set-node)
        set-group? (= ctob/set-group-prefix set-prefix)
        root? (= tree-depth 0)
        collapsed? (mf/use-state false)
        children? (and
                   (or root? set-group?)
                   (not @collapsed?))]
    [:*
     (cond
       root? nil
       set?
       [:& sets-tree-set
        {:set set-node
         :active? active?
         :selected? (selected? tree-path)
         :on-select on-select
         :label set-path'
         :tree-path (or tree-path set-path)
         :tree-depth tree-depth
         :editing? editing?
         :on-toggle on-toggle
         :on-edit on-edit
         :on-edit-reset on-edit-reset
         :on-edit-submit on-edit-submit}]
       set-group?
       [:& sets-tree-set-group
        {:selected? (selected? tree-path)
         :on-select on-select
         :label set-path'
         :collapsed? collapsed?
         :tree-path (or tree-path set-path)
         :tree-depth tree-depth
         :editing? editing?
         :on-edit on-edit
         :on-edit-reset on-edit-reset
         :on-edit-submit on-edit-submit}])
     (when children?
       (for [[set-path set-node] set-node
             :let [tree-path' (str (when tree-path (str tree-path "/")) set-path)]]
         [:& sets-tree
          {:key tree-path'
           :set-path set-path
           :set-node set-node
           :tree-depth (when-not root? (inc tree-depth))
           :tree-path tree-path'
           :on-select on-select
           :selected? selected?
           :on-toggle on-toggle
           :active? active?
           :editing? editing?
           :on-edit on-edit
           :on-edit-reset on-edit-reset
           :on-edit-submit on-edit-submit}]))]))

(mf/defc controlled-sets-list
  [{:keys [token-sets
           on-update-token-set
           token-set-selected?
           token-set-active?
           on-create-token-set
           on-toggle-token-set
           origin
           on-select
           context]
    :as _props}]
  (let [{:keys [editing? new? on-edit on-reset] :as ctx} (or context (sets-context/use-context))]
    [:ul {:class (stl/css :sets-list)}
     (if (and
          (= origin "theme-modal")
          (empty? token-sets))
       [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message-sets)}
        (tr "workspace.token.no-sets-create")]
       (if (and (= origin "theme-modal")
                (empty? token-sets))
         [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message-sets)}
          (tr "workspace.token.no-sets-create")]
         [:*
          [:& sets-tree
           {:set-node token-sets
            :selected? token-set-selected?
            :on-select on-select
            :active? token-set-active?
            :on-toggle on-toggle-token-set
            :editing? editing?
            :on-edit on-edit
            :on-edit-reset on-reset
            :on-edit-submit on-update-token-set}]
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
        selected-token-set-id (mf/deref refs/workspace-selected-token-set-id)
        token-set-selected? (mf/use-fn
                             (mf/deps token-sets selected-token-set-id)
                             (fn [tree-path]
                               (= tree-path selected-token-set-id)))
        active-token-set-names (mf/deref refs/workspace-active-set-names)
        token-set-active? (mf/use-fn
                           (mf/deps active-token-set-names)
                           (fn [set-name]
                             (get active-token-set-names set-name)))]
    [:& controlled-sets-list
     {:token-sets token-sets
      :token-set-selected? token-set-selected?
      :token-set-active? token-set-active?
      :on-select on-select-token-set-click
      :origin "set-panel"
      :on-toggle-token-set on-toggle-token-set-click
      :on-update-token-set on-update-token-set
      :on-create-token-set on-create-token-set}]))
