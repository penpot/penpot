;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC


(ns app.main.ui.workspace.tokens.management.group
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pprint :as pp]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layers.layer-button :refer [layer-button*]]
   [app.main.ui.workspace.tokens.management.token-tree :refer [token-tree*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:unfolded-token-paths
  (l/derived (l/key :unfolded-token-paths) refs/workspace-tokens))

(defn token-section-icon
  [type]
  (case type
    :border-radius i/corner-radius
    :color i/drop
    :boolean i/boolean-difference
    :font-family i/text-font-family
    :font-size i/text-font-size
    :letter-spacing i/text-letterspacing
    :text-case i/text-mixed
    :text-decoration i/text-underlined
    :font-weight i/text-font-weight
    :typography i/text-typography
    :opacity i/percentage
    :number i/number
    :rotation i/rotation
    :spacing i/padding-extended
    :string i/text-mixed
    :stroke-width i/stroke-size
    :dimensions i/expand
    :sizing i/expand
    :shadow i/drop-shadow
    "add"))

(def ^:private schema:token-group
  [:map
   [:type :keyword]
   [:tokens :any]
   [:selected-shapes :any]
   [:is-selected-inside-layout {:optional true} [:maybe :boolean]]
   [:active-theme-tokens {:optional true} :any]
   [:selected-token-set-id {:optional true} :any]
   [:tokens-lib {:optional true} :any]
   [:on-token-pill-click {:optional true} fn?]
   [:on-context-menu {:optional true} fn?]])

(mf/defc token-group*
  {::mf/schema schema:token-group}
  [{:keys [type tokens selected-shapes is-selected-inside-layout active-theme-tokens selected-token-set-id tokens-lib selected-ids]}]
  (let [{:keys [modal title]}
        (get dwta/token-properties type)

        unfolded-token-paths (mf/deref ref:unfolded-token-paths)
        is-type-unfolded (contains? (set unfolded-token-paths) (name type))

        editing-ref  (mf/deref refs/workspace-editor-state)
        not-editing? (empty? editing-ref)

        can-edit?
        (mf/use-ctx ctx/can-edit?)

        is-selected-inside-layout (d/nilv is-selected-inside-layout false)

        tokens
        (mf/with-memo [tokens]
          (vec (sort-by :name tokens)))

        expandable? (d/nilv (seq tokens) false)

        on-pill-context-menu
        (mf/use-fn
         (fn [event token]
           (dom/prevent-default event)
           (st/emit! (dwtl/assign-token-context-menu
                      {:type :token
                       :position (dom/get-client-position event)
                       :errors (:errors token)
                       :token-id (:id token)}))))

        on-node-context-menu
        (mf/use-fn
         (fn [event node]
           (dom/prevent-default event)
           (st/emit! (dwtl/assign-token-node-context-menu
                      {:node node
                       :type type
                       :position (dom/get-client-position event)}))))

        on-toggle-open-click
        (mf/use-fn
         (mf/deps type expandable?)
         (fn []
           (when expandable?
             (st/emit! (dwtl/toggle-token-path (name type))))))

        on-popover-open-click
        (mf/use-fn
         (mf/deps type title modal)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit!
            (let [pos (dom/get-client-position event)]
              (modal/show (:key modal)
                          {:x (:x pos)
                           :y (:y pos)
                           :position :right
                           :fields (:fields modal)
                           :title title
                           :action "create"
                           :token-type type})))))

        on-token-pill-click
        (mf/use-fn
         (mf/deps not-editing? selected-ids)
         (fn [event token]
           (let [token (ctob/get-token tokens-lib selected-token-set-id (:id token))]
             (dom/stop-propagation event)
             (when (and not-editing? (seq selected-shapes) (not= (:type token) :number))
               (st/emit! (dwta/toggle-token {:token token
                                             :shape-ids selected-ids}))))))]

    [:div {:class (stl/css :token-section-wrapper)
           :data-testid (dm/str "section-" (name type))}
     [:> layer-button* {:label title
                        :expanded is-type-unfolded
                        :description (when expandable? (dm/str (count tokens)))
                        :is-expandable expandable?
                        :aria-expanded is-type-unfolded
                        :aria-controls (dm/str "token-tree-" (name type))
                        :on-toggle-expand on-toggle-open-click
                        :icon (token-section-icon type)}
      (when can-edit?
        [:> icon-button* {:id (str "add-token-button-" title)
                          :icon "add"
                          :aria-label (tr "workspace.tokens.add-token" title)
                          :variant "ghost"
                          :on-click on-popover-open-click
                          :class (stl/css :token-section-icon)}])]
     (when is-type-unfolded
       [:> token-tree* {:tokens tokens
                        :type type
                        :id (dm/str "token-tree-" (name type))
                        :tokens-lib tokens-lib
                        :unfolded-token-paths unfolded-token-paths
                        :selected-shapes selected-shapes
                        :active-theme-tokens active-theme-tokens
                        :selected-token-set-id selected-token-set-id
                        :is-selected-inside-layout is-selected-inside-layout
                        :on-token-pill-click on-token-pill-click
                        :on-pill-context-menu on-pill-context-menu
                        :on-node-context-menu on-node-context-menu}])]))
