;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.main.ui.workspace.tokens.management.token-tree
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.path-names :as cpn]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.ds.layers.layer-button :refer [layer-button*]]
   [app.main.ui.workspace.tokens.management.token-pill :refer [token-pill*]]
   [rumext.v2 :as mf]))



(def ^:private schema:folder-node
  [:map
   [:node :any]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} :boolean]
   [:active-theme-tokens {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:tokens-lib {:optional true} :any]
   [:on-token-pill-click {:optional true} fn?]
   [:on-context-menu {:optional true} fn?]])

(mf/defc folder-node*
  {::mf/schema schema:folder-node}
  [{:keys [node selected-shapes is-selected-inside-layout active-theme-tokens selected-token-set-id tokens-lib on-token-pill-click on-context-menu]}]
  (let [expanded* (mf/use-state false)
        expanded (deref expanded*)
        swap-folder-expanded #(swap! expanded* not)]
    [:li {:class (stl/css :folder-node)}
     [:> layer-button* {:label (:name node)
                        :expanded expanded
                        :is-expandable (:has-children node)
                        :on-toggle-expand swap-folder-expanded}]
     (when expanded
       (let [children-fn (:children-fn node)]
         [:div {:class (stl/css :folder-children-wrapper)}
          (when children-fn
            (let [children (children-fn)]
              (for [child children]
                (if (not (:is-leaf child))
                  [:ul {:class (stl/css :node-parent)}
                   [:> folder-node* {:key (:path child)
                                     :node child
                                     :selected-shapes selected-shapes
                                     :is-selected-inside-layout is-selected-inside-layout
                                     :active-theme-tokens active-theme-tokens
                                     :on-token-pill-click on-token-pill-click
                                     :on-context-menu on-context-menu
                                     :tokens-lib tokens-lib
                                     :selected-token-set-id selected-token-set-id}]]
                  (let [id (:id (:leaf child))
                        token (ctob/get-token tokens-lib selected-token-set-id id)]
                    [:> token-pill*
                     {:key id
                      :token token
                      :selected-shapes selected-shapes
                      :is-selected-inside-layout is-selected-inside-layout
                      :active-theme-tokens active-theme-tokens
                      :on-click on-token-pill-click
                      :on-context-menu on-context-menu}])))))]))]))

(def ^:private schema:token-tree
  [:map
   [:tokens :any]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} :boolean]
   [:active-theme-tokens {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:tokens-lib {:optional true} :any]
   [:on-token-pill-click {:optional true} fn?]
   [:on-context-menu {:optional true} fn?]])

(mf/defc token-tree*
  {::mf/schema schema:token-tree}
  [{:keys [tokens selected-shapes is-selected-inside-layout active-theme-tokens tokens-lib selected-token-set-id on-token-pill-click on-context-menu]}]
  (let [separator "."
        tree (mf/use-memo
              (mf/deps tokens)
              (fn []
                (cpn/build-tree-root tokens separator)))]
    [:div {:class (stl/css :token-tree-wrapper)}
     (for [node tree]
       [:ul {:class (stl/css :node-parent)
             :key (:path node)
             :style {:--node-depth (inc (:depth node))}}
        (if (:is-leaf node)
          (let [token (ctob/get-token tokens-lib selected-token-set-id (get-in node [:leaf :id]))]
            [:> token-pill*
             {:token token
              :selected-shapes selected-shapes
              :is-selected-inside-layout is-selected-inside-layout
              :active-theme-tokens active-theme-tokens
              :on-click on-token-pill-click
              :on-context-menu on-context-menu}])
          ;; Render segment folder
          [:> folder-node* {:node node
                            :selected-shapes selected-shapes
                            :is-selected-inside-layout is-selected-inside-layout
                            :active-theme-tokens active-theme-tokens
                            :on-token-pill-click on-token-pill-click
                            :on-context-menu on-context-menu
                            :tokens-lib tokens-lib
                            :selected-token-set-id selected-token-set-id}])])]))
