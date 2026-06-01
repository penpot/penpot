;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.token-tree
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.path-names :as cpn]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layers.layer-button :refer [layer-button*]]
   [app.main.ui.workspace.tokens.management.token-pill :refer [token-pill*]]
   [rumext.v2 :as mf]))

(def ^:private schema:folder-node
  [:map
   [:node :any]
   [:type :keyword]
   [:folded-token-paths {:optional true} [:maybe [:vector :string]]]
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
           folded-token-paths
           selected-shapes
           is-selected-inside-layout
           active-theme-tokens
           selected-token-set-id
           tokens-lib
           on-token-pill-click
           on-pill-context-menu
           on-node-context-menu]}]
  (let [full-path            (str (name type) "." (:path node))
        is-folder-expanded   (not (contains? (set (or folded-token-paths [])) full-path))
        children             (:children node)

        sorted-children
        (mf/with-memo [children]
          (let [[leafs groups]
                (reduce (fn [[l g] item]
                          (if (:leaf item)
                            [(conj l item) g]
                            [l (conj g item)]))
                        [[] []]
                        children)
                sorted-leafs  (d/natural-sort-by :name leafs)
                sorted-groups (d/natural-sort-by :name groups)]
            (concat sorted-leafs sorted-groups)))

        swap-folder-expanded
        (mf/use-fn
         (mf/deps full-path)
         (fn []
           (st/emit! (dwtl/toggle-token-path full-path))))

        node-context-menu-prep
        (mf/use-fn
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
       [:div {:class (stl/css :folder-children-wrapper)
              :id (str "folder-children-" (:path node))}
        (when (seq children)
          (for [child sorted-children]
            (if (not (:leaf child))
              [:ul {:class (stl/css :node-parent)
                    :key (:path child)}
               [:> folder-node* {:type type
                                 :node child
                                 :folded-token-paths folded-token-paths
                                 :selected-shapes selected-shapes
                                 :is-selected-inside-layout is-selected-inside-layout
                                 :active-theme-tokens active-theme-tokens
                                 :on-token-pill-click on-token-pill-click
                                 :on-pill-context-menu on-pill-context-menu
                                 :on-node-context-menu on-node-context-menu
                                 :tokens-lib tokens-lib
                                 :selected-token-set-id selected-token-set-id}]]
              (let [id    (:id (:leaf child))
                    token (ctob/get-token tokens-lib selected-token-set-id id)]
                [:> token-pill*
                 {:key id
                  :token token
                  :selected-shapes selected-shapes
                  :is-selected-inside-layout is-selected-inside-layout
                  :active-theme-tokens active-theme-tokens
                  :on-click on-token-pill-click
                  :on-context-menu on-pill-context-menu}]))))])]))

(def ^:private schema:token-tree
  [:map
   [:tokens :any]
   [:type :keyword]
   [:folded-token-paths {:optional true} [:maybe [:vector :string]]]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} :boolean]
   [:active-theme-tokens {:optional true} :any]
   [:tokens-lib {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:on-token-pill-click {:optional true} fn?]
   [:on-pill-context-menu {:optional true} fn?]
   [:on-node-context-menu {:optional true} fn?]])

(mf/defc token-tree*
  {::mf/schema schema:token-tree}
  [{:keys [tokens
           type
           folded-token-paths
           selected-shapes
           is-selected-inside-layout
           active-theme-tokens
           active-theme-tokens-not-forced
           tokens-lib
           selected-token-set-id
           on-token-pill-click
           on-pill-context-menu
           on-node-context-menu]}]
  (let [separator "."
        raw-tree      (mf/with-memo [tokens]
                        (cpn/build-tree-root tokens separator))
        permissions   (mf/use-ctx ctx/permissions)
        can-edit?     (:can-edit permissions)
        on-node-context-menu (mf/use-fn
                              (mf/deps can-edit? on-node-context-menu)
                              (fn [event node]
                                (when can-edit?
                                  (on-node-context-menu event node))))

        ordered-nodes (mf/with-memo [raw-tree]
                        (let [[leafs groups]
                              (reduce (fn [[l g] item]
                                        (if (:leaf item)
                                          [(conj l item) g]
                                          [l (conj g item)]))
                                      [[] []]
                                      raw-tree)
                              sorted-leafs  (d/natural-sort-by :name leafs)
                              sorted-groups (d/natural-sort-by :name groups)]
                          (concat sorted-leafs sorted-groups)))]
    [:div {:class (stl/css :token-tree-wrapper)}
     (for [node ordered-nodes]
       (if (:leaf node)
         (let [token (ctob/get-token tokens-lib selected-token-set-id (get-in node [:leaf :id]))]
           [:> token-pill*
            {:token token
             :key (:id (:leaf node))
             :selected-shapes selected-shapes
             :is-selected-inside-layout is-selected-inside-layout
             :active-theme-tokens active-theme-tokens
             :active-theme-tokens-not-forced active-theme-tokens-not-forced
             :on-click on-token-pill-click
             :on-context-menu on-pill-context-menu}])
         ;; Render segment folder
         [:ul {:class (stl/css :node-parent)
               :key (:path node)}
          [:> folder-node* {:node node
                            :type type
                            :folded-token-paths folded-token-paths
                            :selected-shapes selected-shapes
                            :is-selected-inside-layout is-selected-inside-layout
                            :active-theme-tokens active-theme-tokens
                            :on-token-pill-click on-token-pill-click
                            :on-node-context-menu on-node-context-menu
                            :on-pill-context-menu on-pill-context-menu
                            :tokens-lib tokens-lib
                            :selected-token-set-id selected-token-set-id}]]))]))
