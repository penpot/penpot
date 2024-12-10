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
  (st/emit! (wdt/set-selected-token-set-path tree-path)))

(defn on-update-token-set [set-name token-set]
  (st/emit! (wdt/update-token-set set-name token-set)))

(defn on-update-token-set-group [from-prefixed-path-str to-path-str]
  (st/emit!
   (wdt/rename-token-set-group
    (ctob/prefixed-set-path-string->set-name-string from-prefixed-path-str)
    (-> (ctob/prefixed-set-path-string->set-path from-prefixed-path-str)
        (butlast)
        (ctob/join-set-path)
        (ctob/join-set-path-str to-path-str)))))

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
  [{:keys [label tree-depth tree-path selected? collapsed? editing? on-edit on-edit-reset on-edit-submit]}]
  (let [editing?' (editing? tree-path)
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
                :prefixed-set-path tree-path})))))
        on-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! collapsed? not)))
        on-double-click
        (mf/use-fn
         (mf/deps tree-path)
         #(on-edit tree-path))
        on-edit-submit'
        (mf/use-fn
         (mf/deps tree-path on-edit-submit)
         #(on-edit-submit tree-path %))]
    [:div {:role "button"
           :data-testid "tokens-set-group-item"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :set-item-group true
                                :selected-set selected?)
           :on-context-menu on-context-menu}
     [:> icon-button*
      {:class (stl/css :set-item-group-collapse-button)
       :on-click on-click
       :aria-label (tr "labels.collapse")
       :icon (if @collapsed? "arrow-right" "arrow-down")
       :variant "action"}]
     (if editing?'
       [:& editing-label
        {:default-value label
         :on-cancel on-edit-reset
         :on-create on-edit-reset
         :on-submit on-edit-submit'}]
       [:div {:class (stl/css :set-name)
              :on-double-click on-double-click}
        label])]))

(mf/defc sets-tree-set
  [{:keys [set label tree-depth tree-path selected? on-select active? on-toggle editing? on-edit on-edit-reset on-edit-submit]}]
  (let [set-name (.-name set)
        editing?' (editing? tree-path)
        active?' (some? (active? set-name))
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
                :prefixed-set-path tree-path})))))
        on-double-click (mf/use-fn
                         (mf/deps tree-path)
                         #(on-edit tree-path))
        on-checkbox-click (mf/use-fn
                           (fn [event]
                             (dom/stop-propagation event)
                             (on-toggle set-name)))
        on-edit-submit' (mf/use-fn
                         (mf/deps set on-edit-submit)
                         #(on-edit-submit set-name (ctob/update-name set %)))]
    [:div {:role "button"
           :data-testid "tokens-set-item"
           :style {"--tree-depth" tree-depth}
           :class (stl/css-case :set-item-container true
                                :selected-set selected?)
           :on-click on-click
           :on-context-menu on-context-menu
           :aria-checked active?'}
     [:> icon*
      {:id "document"
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
        [:button {:type "button"
                  :on-click on-checkbox-click
                  :class (stl/css-case :checkbox-style true
                                       :checkbox-checked-style active?')}
         (when active?'
           [:> icon* {:aria-label (tr "workspace.token.select-set")
                      :class (stl/css :check-icon)
                      :size "s"
                      :id ic/tick}])]])]))

(mf/defc sets-tree
  [{:keys [active?
           editing?
           on-edit
           on-edit-reset
           on-edit-submit-set
           on-edit-submit-group
           on-select
           on-toggle
           selected?
           set-node
           set-path
           tree-depth
           tree-path]
    :or {tree-depth 0}
    :as props}]
  (let [[set-path-prefix set-fname] (some-> set-path (ctob/split-set-str-path-prefix))
        set? (instance? ctob/TokenSet set-node)
        set-group? (= ctob/set-group-prefix set-path-prefix)
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
         :label set-fname
         :tree-path (or tree-path set-path)
         :tree-depth tree-depth
         :editing? editing?
         :on-toggle on-toggle
         :on-edit on-edit
         :on-edit-reset on-edit-reset
         :on-edit-submit on-edit-submit-set}]
       set-group?
       [:& sets-tree-set-group
        {:selected? (selected? tree-path)
         :on-select on-select
         :label set-fname
         :collapsed? collapsed?
         :tree-path (or tree-path set-path)
         :tree-depth tree-depth
         :editing? editing?
         :on-edit on-edit
         :on-edit-reset on-edit-reset
         :on-edit-submit on-edit-submit-group}])
     (when children?
       (for [[set-path set-node] set-node
             :let [tree-path' (ctob/join-set-path-str tree-path set-path)]]
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
           :on-edit-submit-set on-edit-submit-set
           :on-edit-submit-group on-update-token-set-group}]))]))

(mf/defc controlled-sets-list
  [{:keys [token-sets
           on-update-token-set
           on-update-token-set-group
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
        selected-token-set-path (mf/deref refs/workspace-selected-token-set-path)
        token-set-selected? (mf/use-fn
                             (mf/deps token-sets selected-token-set-path)
                             (fn [tree-path]
                               (= tree-path selected-token-set-path)))
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
      :on-update-token-set-group on-update-token-set-group
      :on-create-token-set on-create-token-set}]))
