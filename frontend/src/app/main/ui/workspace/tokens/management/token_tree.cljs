;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.management.token-tree
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.path-names :as cpn]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
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
   [:on-node-context-menu {:optional true} fn?]
   [:can-edit {:optional true} :boolean]])

(defn- leaf-node?
  "A tree node is a leaf when it holds a token and has no children."
  [node]
  (and (:leaf node) (not (seq (:children node)))))

(defn- sort-nodes
  "Sorts tree nodes so that leaf tokens come before groups, sorting each
   set naturally by name."
  [nodes]
  (let [[leafs groups]
        (reduce (fn [[leafs groups] node]
                  (if (leaf-node? node)
                    [(conj leafs node) groups]
                    [leafs (conj groups node)]))
                [[] []]
                nodes)]
    (concat (d/natural-sort-by :name leafs)
            (d/natural-sort-by :name groups))))

(def ^:private schema:leaf-node
  [:map
   [:leaf :any]
   [:tokens-lib {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} :boolean]
   [:active-theme-tokens {:optional true} :any]
   [:can-edit {:optional true} :boolean]
   [:on-token-pill-click {:optional true} fn?]
   [:on-pill-context-menu {:optional true} fn?]])

(mf/defc leaf-node*
  {::mf/private true
   ::mf/schema schema:leaf-node}
  [{:keys [leaf tokens-lib selected-token-set-id selected-shapes
           is-selected-inside-layout active-theme-tokens can-edit
           on-token-pill-click on-pill-context-menu]}]
  (let [id    (:id leaf)
        token (ctob/get-token tokens-lib selected-token-set-id id)]
    [:> token-pill* {:token token
                     :selected-shapes selected-shapes
                     :is-selected-inside-layout is-selected-inside-layout
                     :active-theme-tokens active-theme-tokens
                     :on-click on-token-pill-click
                     :can-edit can-edit
                     :on-context-menu on-pill-context-menu}]))

(mf/defc folder-node*
  {::mf/private true
   ::mf/schema schema:folder-node}
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
           on-node-context-menu
           can-edit]}]
  (let [full-path            (str (name type) "." (:path node))
        is-folder-expanded   (not (contains? (set (or folded-token-paths [])) full-path))

        children             (:children node)

        sorted-children
        (mf/with-memo [children]
          (sort-nodes children))

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
                        :is-expandable (boolean (seq children))
                        :on-toggle-expand swap-folder-expanded
                        :on-context-menu node-context-menu-prep}]
     (when is-folder-expanded
       [:div {:class (stl/css :folder-children-wrapper)
              :id (str "folder-children-" (:path node))}
        (when-let [leaf (:leaf node)]
          [:> leaf-node* {:key (:id leaf)
                          :leaf leaf
                          :tokens-lib tokens-lib
                          :selected-token-set-id selected-token-set-id
                          :selected-shapes selected-shapes
                          :is-selected-inside-layout is-selected-inside-layout
                          :active-theme-tokens active-theme-tokens
                          :can-edit can-edit
                          :on-token-pill-click on-token-pill-click
                          :on-pill-context-menu on-pill-context-menu}])

        (for [child sorted-children]
          (if (leaf-node? child)
            [:> leaf-node* {:key (:id (:leaf child))
                            :leaf (:leaf child)
                            :tokens-lib tokens-lib
                            :selected-token-set-id selected-token-set-id
                            :selected-shapes selected-shapes
                            :is-selected-inside-layout is-selected-inside-layout
                            :active-theme-tokens active-theme-tokens
                            :can-edit can-edit
                            :on-token-pill-click on-token-pill-click
                            :on-pill-context-menu on-pill-context-menu}]
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
                               :can-edit can-edit
                               :selected-token-set-id selected-token-set-id}]]))])]))

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
   [:on-node-context-menu {:optional true} fn?]
   [:can-edit {:optional true} :boolean]
   [:can-edit-tokens {:optional true} :boolean]])

(mf/defc token-tree*
  {::mf/schema schema:token-tree}
  [{:keys [tokens
           type
           folded-token-paths
           selected-shapes
           is-selected-inside-layout
           active-theme-tokens
           tokens-lib
           selected-token-set-id
           on-token-pill-click
           on-pill-context-menu
           on-node-context-menu
           can-edit
           can-edit-tokens]}]
  (let [raw-tree
        (mf/with-memo [tokens]
          (cpn/build-tree-root tokens ctob/token-separator))

        ordered-nodes
        (mf/with-memo [raw-tree]
          (sort-nodes raw-tree))

        on-node-context-menu
        (mf/use-fn
         (mf/deps can-edit-tokens on-node-context-menu)
         (fn [event node]
           (when can-edit-tokens
             (on-node-context-menu event node))))]

    [:div {:class (stl/css :token-tree-wrapper)}
     (for [node ordered-nodes]
       (if (leaf-node? node)
         [:> leaf-node* {:key (:id (:leaf node))
                         :leaf (:leaf node)
                         :tokens-lib tokens-lib
                         :selected-token-set-id selected-token-set-id
                         :selected-shapes selected-shapes
                         :is-selected-inside-layout is-selected-inside-layout
                         :active-theme-tokens active-theme-tokens
                         :can-edit can-edit
                         :on-token-pill-click on-token-pill-click
                         :on-pill-context-menu on-pill-context-menu}]

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
                            :can-edit can-edit
                            :selected-token-set-id selected-token-set-id}]]))]))
