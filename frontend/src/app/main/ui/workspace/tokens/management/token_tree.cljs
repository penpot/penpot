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
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.layers.layer-button :refer [layer-button*]]
   [app.main.ui.workspace.tokens.management.token-pill :refer [token-pill*]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private schema:folder-node
  [:map
   [:node :any]
   [:type :keyword]
   [:unfolded-token-paths {:optional true} [:vector :string]]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} :boolean]
   [:active-theme-tokens {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:tokens-lib {:optional true} :any]
   [:on-token-pill-click {:optional true} fn?]
   [:on-pill-context-menu {:optional true} fn?]
   [:on-node-context-menu {:optional true} fn?]])

(mf/defc folder-node*
  {::mf/schema schema:folder-node}
  [{:keys [node
           type
           unfolded-token-paths
           selected-shapes
           is-selected-inside-layout
           active-theme-tokens
           selected-token-set-id
           tokens-lib
           on-token-pill-click
           on-pill-context-menu
           on-node-context-menu]}]
  (let [full-path (str (name type) "." (:path node))
        is-folder-expanded (contains? (set (or unfolded-token-paths [])) full-path)
        swap-folder-expanded (mf/use-fn
                              (mf/deps (:path node) type)
                              (fn []
                                (let [path (str (name type)  "." (:path node))]
                                  (st/emit! (dwtl/toggle-token-path path)))))
        node-context-menu-prep (mf/use-fn
                                (mf/deps on-node-context-menu node)
                                (fn [event]
                                  (when on-node-context-menu
                                    (on-node-context-menu event node))))]
    [:li {:class (stl/css :folder-node)}
     [:> layer-button* {:label (:name node)
                        :expanded is-folder-expanded
                        :aria-expanded is-folder-expanded
                        :aria-controls (str "folder-children-" (:path node))
                        :is-expandable (not (:leaf node))
                        :on-toggle-expand swap-folder-expanded
                        :on-context-menu node-context-menu-prep}]
     (when is-folder-expanded
       (let [children-fn (:children-fn node)]
         [:div {:class (stl/css :folder-children-wrapper)
                :id (str "folder-children-" (:path node))}
          (when children-fn
            (let [children (children-fn)]
              (for [child children]
                (if (not (:leaf child))
                  [:ul {:class (stl/css :node-parent)
                        :key (:path child)}
                   [:> folder-node* {:type type
                                     :node child
                                     :unfolded-token-paths unfolded-token-paths
                                     :selected-shapes selected-shapes
                                     :is-selected-inside-layout is-selected-inside-layout
                                     :active-theme-tokens active-theme-tokens
                                     :on-token-pill-click on-token-pill-click
                                     :on-node-context-menu on-node-context-menu
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
                      :on-context-menu on-pill-context-menu}])))))]))]))

(def ^:private schema:token-tree
  [:map
   [:tokens :any]
   [:type :keyword]
   [:unfolded-token-paths {:optional true} [:vector :string]]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} :boolean]
   [:active-theme-tokens {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:tokens-lib {:optional true} :any]
   [:on-token-pill-click {:optional true} fn?]
   [:on-pill-context-menu {:optional true} fn?]
   [:on-node-context-menu {:optional true} fn?]])

(mf/defc token-tree*
  {::mf/schema schema:token-tree}
  [{:keys [tokens
           type
           unfolded-token-paths
           selected-shapes
           is-selected-inside-layout
           active-theme-tokens
           tokens-lib
           selected-token-set-id
           on-token-pill-click
           on-pill-context-menu
           on-node-context-menu]}]
  (let [separator "."
        tree (mf/use-memo
              (mf/deps tokens)
              (fn []
                (cpn/build-tree-root tokens separator)))
        can-edit? (:can-edit (deref refs/permissions))
        on-node-context-menu (mf/use-fn
                              (mf/deps can-edit? on-node-context-menu)
                              (fn [event node]
                                (when can-edit?
                                  (on-node-context-menu event node))))]
    [:div {:class (stl/css :token-tree-wrapper)}
     (for [node tree]
       (if (:leaf node)
         (let [token (ctob/get-token tokens-lib selected-token-set-id (get-in node [:leaf :id]))]
           [:> token-pill*
            {:token token
             :key (:id (:leaf node))
             :selected-shapes selected-shapes
             :is-selected-inside-layout is-selected-inside-layout
             :active-theme-tokens active-theme-tokens
             :on-click on-token-pill-click
             :on-context-menu on-pill-context-menu}])
          ;; Render segment folder
         [:ul {:class (stl/css :node-parent)
               :key (:path node)}
          [:> folder-node* {:node node
                            :type type
                            :unfolded-token-paths unfolded-token-paths
                            :selected-shapes selected-shapes
                            :is-selected-inside-layout is-selected-inside-layout
                            :active-theme-tokens active-theme-tokens
                            :on-token-pill-click on-token-pill-click
                            :on-node-context-menu on-node-context-menu
                            :on-pill-context-menu on-pill-context-menu
                            :tokens-lib tokens-lib
                            :selected-token-set-id selected-token-set-id}]]))]))
