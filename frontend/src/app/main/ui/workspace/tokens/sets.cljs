;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private chevron-icon
  (i/icon-xref :arrow (stl/css :chevron-icon)))

(defn on-toggle-token-set-click [token-set-id]
  (st/emit! (wdt/toggle-token-set {:token-set-id token-set-id})))

(defn on-select-token-set-click [id]
  (st/emit! (wdt/set-selected-token-set-id id)))

(defn on-delete-token-set-click [id event]
  (dom/stop-propagation event)
  (st/emit! (wdt/delete-token-set id)))

(defn on-update-token-set [token-set]
  (st/emit! (wdt/update-token-set token-set)))

(defn on-create-token-set [token-set]
  (st/emit! (wdt/create-token-set token-set)))

(mf/defc editing-node
  [{:keys [default-value on-cancel on-submit]}]
  (let [ref (mf/use-ref)
        on-submit-valid (mf/use-fn
                         (fn [event]
                           (let [value (str/trim (dom/get-target-val event))]
                             (if (or (str/empty? value)
                                     (= value default-value))
                               (on-cancel)
                               (on-submit value)))))
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

(mf/defc sets-tree
  [{:keys [token-set
           token-set-active?
           token-set-selected?
           editing?
           on-select
           on-toggle
           on-edit
           on-submit
           on-cancel]
    :as _props}]
  (let [{:keys [id name _children]} token-set
        selected? (and set? (token-set-selected? id))
        visible? (token-set-active? id)
        collapsed? (mf/use-state false)
        set? true #_(= type :set)
        group? false #_(= type :group)
        editing-node? (editing? id)
        on-select (mf/use-callback
                   (mf/deps editing-node?)
                   (fn [event]
                     (dom/stop-propagation event)
                     (when-not editing-node?
                       (on-select id))))
        on-context-menu (mf/use-callback
                         (mf/deps editing-node? id)
                         (fn [event]
                           (dom/prevent-default event)
                           (dom/stop-propagation event)
                           (when-not editing-node?
                             (st/emit!
                              (wdt/show-token-set-context-menu
                               {:position (dom/get-client-position event)
                                :token-set-id id})))))]
    [:div {:class (stl/css :set-item-container)
           :on-click on-select
           :on-double-click #(on-edit id)
           :on-context-menu on-context-menu}
     [:div {:class (stl/css-case :set-item-group group?
                                 :set-item-set set?
                                 :selected-set selected?)}
      (when group?
        [:span {:class (stl/css-case :collapsabled-icon true
                                     :collapsed @collapsed?)
                :on-click #(swap! collapsed? not)}
         chevron-icon])
      [:span {:class (stl/css :icon)}
       (if set? i/document i/group)]
      (if editing-node?
        [:& editing-node {:default-value name
                          :on-submit #(on-submit (assoc token-set :name %))
                          :on-cancel on-cancel}]
        [:*
         [:div {:class (stl/css :set-name)} name]
         [:div {:class (stl/css :delete-set)}
          [:button {:on-click #(on-delete-token-set-click id %)
                    :type "button"}
           i/delete]]
         (if set?
           [:span {:class (stl/css :action-btn)
                   :on-click (fn [event]
                               (dom/stop-propagation event)
                               (on-toggle id))}
            (if visible? i/shown i/hide)]
           nil
           #_(when (and children (not @collapsed?))
               [:div {:class (stl/css :set-children)}
                (for [child-id children]
                  [:& sets-tree (assoc props :key child-id
                                       {:key child-id}
                                       :set-id child-id
                                       :selected-set-id selected-token-set-id)])]))])]]))

(mf/defc controlled-sets-list
  [{:keys [token-sets
           on-update-token-set
           token-set-selected?
           token-set-active?
           on-create-token-set
           on-toggle-token-set
           on-select
           context]
    :as _props}]
  (let [{:keys [editing? new? on-edit on-create on-reset] :as ctx} (or context (sets-context/use-context))]
    [:ul {:class (stl/css :sets-list)}
     (for [[id token-set] token-sets]
       (when token-set
         [:& sets-tree {:key id
                        :token-set token-set
                        :token-set-selected? (if new? (constantly false) token-set-selected?)
                        :token-set-active? token-set-active?
                        :editing? editing?
                        :on-select on-select
                        :on-edit on-edit
                        :on-toggle on-toggle-token-set
                        :on-submit #(do
                                      (on-update-token-set %)
                                      (on-reset))
                        :on-cancel on-reset}]))
     (when new?
       [:& sets-tree {:token-set {:name ""}
                      :token-set-selected? (constantly true)
                      :token-set-active? (constantly true)
                      :editing? (constantly true)
                      :on-select (constantly nil)
                      :on-edit on-create
                      :on-submit #(do
                                    (on-create-token-set %)
                                    (on-reset))
                      :on-cancel on-reset}])]))

(mf/defc sets-list
  [{:keys []}]
  (let [token-sets (mf/deref refs/workspace-ordered-token-sets)
        selected-token-set-id (mf/deref refs/workspace-selected-token-set-id)
        token-set-selected? (mf/use-callback
                             (mf/deps selected-token-set-id)
                             (fn [id]
                               (= id selected-token-set-id)))
        active-token-set-ids (mf/deref refs/workspace-active-set-ids)
        token-set-active? (mf/use-callback
                           (mf/deps active-token-set-ids)
                           (fn [id]
                             (get active-token-set-ids id)))]
    [:& controlled-sets-list
     {:token-sets token-sets
      :token-set-selected? token-set-selected?
      :token-set-active? token-set-active?
      :on-select on-select-token-set-click
      :on-toggle-token-set on-toggle-token-set-click
      :on-update-token-set on-update-token-set
      :on-create-token-set on-create-token-set}]))
