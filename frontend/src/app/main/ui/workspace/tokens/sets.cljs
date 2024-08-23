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
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private chevron-icon
  (i/icon-xref :arrow (stl/css :chevron-icon)))

(defn on-toggle-token-set-click [id event]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (st/emit! (wdt/toggle-token-set id)))

(defn on-select-token-set-click [id event]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (st/emit! (wdt/set-selected-token-set-id id)))

(mf/defc sets-tree
  [{:keys [token-set token-set-active? token-set-selected?] :as _props}]
  (let [{:keys [id name _children]} token-set
        selected? (and set? (token-set-selected? id))
        visible? (token-set-active? id)
        collapsed? (mf/use-state false)
        set? true #_(= type :set)
        group? false #_ (= type :group)]
    [:div {:class (stl/css :set-item-container)
           :on-click #(on-select-token-set-click id %)}
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
      [:div {:class (stl/css :set-name)} name]
      (when set?
        [:span {:class (stl/css :action-btn)
                :on-click #(on-toggle-token-set-click id %)}
         (if visible? i/shown i/hide)])]
     #_(when (and children (not @collapsed?))
         [:div {:class (stl/css :set-children)}
          (for [child-id children]
            [:& sets-tree (assoc props :key child-id
                                 {:key child-id}
                                 :set-id child-id
                                 :selected-set-id selected-token-set-id)])])]))

(mf/defc sets-list
  [{:keys []}]
  (let [token-sets (mf/deref refs/workspace-token-sets)
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
    [:ul {:class (stl/css :sets-list)}
     (for [[id token-set] token-sets]
       [:& sets-tree
        {:key id
         :token-set token-set
         :selected-token-set-id selected-token-set-id
         :token-set-selected? token-set-selected?
         :token-set-active? token-set-active?}])]))
