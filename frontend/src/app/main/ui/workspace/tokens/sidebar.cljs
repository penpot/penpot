;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.context-menu :refer [token-context-menu]]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.main.ui.workspace.tokens.sets :refer [sets-list]]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-types :as wtty]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def lens:token-type-open-status
  (l/derived (l/in [:workspace-tokens :open-status]) st/state))

(def ^:private download-icon
  (i/icon-xref :download (stl/css :download-icon)))

(def current-set-id
  (l/derived :current-set-id st/state))

(mf/defc token-pill
  {::mf/wrap-props false}
  [{:keys [on-click token highlighted? on-context-menu]}]
  (let [{:keys [name value resolved-value errors]} token
        errors? (seq errors)]
    [:button {:class (stl/css-case :token-pill true
                                   :token-pill-highlighted highlighted?
                                   :token-pill-invalid errors?)
              :title (cond
                       errors? (sd/humanize-errors token)
                       :else (->> [(str "Token: " name)
                                   (str "Original value: " value)
                                   (str "Resolved value: " resolved-value)]
                                  (str/join "\n")))
              :on-click on-click
              :on-context-menu on-context-menu
              :disabled errors?}
     name]))

(mf/defc token-section-icon
  {::mf/wrap-props false}
  [{:keys [type]}]
  (case type
    :border-radius i/corner-radius
    :numeric [:span {:class (stl/css :section-text-icon)} "123"]
    :boolean i/boolean-difference
    :opacity [:span {:class (stl/css :section-text-icon)} "%"]
    :rotation i/rotation
    :spacing i/padding-extended
    :string i/text-mixed
    :stroke-width i/stroke-size
    :typography i/text
    ;; TODO: Add diagonal icon here when it's available
    :dimensions [:div {:style {:rotate "45deg"}} i/constraint-horizontal]
    :sizing [:div {:style {:rotate "45deg"}} i/constraint-horizontal]
    i/add))

(mf/defc token-component
  [{:keys [type tokens selected-shapes token-type-props]}]
  (let [open? (mf/deref (-> (l/key type)
                            (l/derived lens:token-type-open-status)))
        {:keys [modal attributes all-attributes title]} token-type-props

        on-context-menu (mf/use-fn
                         (fn [event token]
                           (dom/prevent-default event)
                           (dom/stop-propagation event)
                           (st/emit! (dt/show-token-context-menu {:type :token
                                                                  :position (dom/get-client-position event)
                                                                  :token-id (:id token)}))))

        on-toggle-open-click (mf/use-fn
                              (mf/deps open? tokens)
                              #(st/emit! (dt/set-token-type-section-open type (not open?))))
        on-popover-open-click (mf/use-fn
                               (fn [event]
                                 (let [{:keys [key fields]} modal]
                                   (dom/stop-propagation event)
                                   (modal/show! key {:x (.-clientX ^js event)
                                                     :y (.-clientY ^js event)
                                                     :position :right
                                                     :fields fields
                                                     :token-type type}))))

        on-token-pill-click (mf/use-fn
                             (mf/deps selected-shapes token-type-props)
                             (fn [event token]
                               (dom/stop-propagation event)
                               (when (seq selected-shapes)
                                 (st/emit!
                                  (wtch/toggle-token {:token token
                                                      :shapes selected-shapes
                                                      :token-type-props token-type-props})))))
        tokens-count (count tokens)]
    [:div {:on-click on-toggle-open-click}
     [:& cmm/asset-section {:icon (mf/fnc icon-wrapper [_]
                                    [:div {:class (stl/css :section-icon)}
                                     [:& token-section-icon {:type type}]])

                            :title title
                            :assets-count tokens-count
                            :open? open?}
      [:& cmm/asset-section-block {:role :title-button}
       [:button {:class (stl/css :action-button)
                 :on-click on-popover-open-click}
        i/add]]
      (when open?
        [:& cmm/asset-section-block {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token (sort-by :modified-at tokens)]
            [:& token-pill
             {:key (:id token)
              :token token
              :highlighted? (wtt/shapes-token-applied? token selected-shapes (or all-attributes attributes))
              :on-click #(on-token-pill-click % token)
              :on-context-menu #(on-context-menu % token)}])]])]]))

(defn sorted-token-groups
  "Separate token-types into groups of `:empty` or `:filled` depending if tokens exist for that type.
  Sort each group alphabetically (by their `:token-key`)."
  [tokens]
  (let [tokens-by-type (wtc/group-tokens-by-type tokens)
        {:keys [empty filled]} (->> wtty/token-types
                                    (map (fn [[token-key token-type-props]]
                                           {:token-key token-key
                                            :token-type-props token-type-props
                                            :tokens (get tokens-by-type token-key [])}))
                                    (group-by (fn [{:keys [tokens]}]
                                                (if (empty? tokens) :empty :filled))))]
    {:empty (sort-by :token-key empty)
     :filled (sort-by :token-key filled)}))

(mf/defc tokens-explorer
  [_props]
  (let [objects (mf/deref refs/workspace-page-objects)

        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)

        tokens (-> (mf/deref refs/workspace-tokens)
                   (sd/use-resolved-tokens))
        token-groups (mf/with-memo [tokens]
                       (sorted-token-groups tokens))]
    [:article
     [:& token-context-menu]
     [:div.assets-bar
      (for [{:keys [token-key token-type-props tokens]} (concat (:filled token-groups)
                                                                (:empty token-groups))]
        [:& token-component {:key token-key
                             :type token-key
                             :selected-shapes selected-shapes
                             :tokens tokens
                             :token-type-props token-type-props}])]]))

(mf/defc sets-sidebar
  []
  (let [current-set-id (mf/deref current-set-id)
        open? (mf/use-state true)]
    [:div {:key (str "sidebar-" current-set-id)
           :class (stl/css :sets-sidebar)}
     [:div {:class (stl/css :sidebar-header)}
      [:& title-bar {:collapsable true
                     :collapsed (not @open?)
                     :title "SETS"
                     :on-collapsed #(swap! open? not)}]
      [:button {:class (stl/css :add-set)
                :on-click #(println "Add Set")}
       i/add]]
     (when @open?
       [:& sets-list {:current-set-id current-set-id}])]))

(mf/defc tokens-sidebar-tab
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [_props]
  [:div {:class (stl/css :sidebar-tab-wrapper)}
    [:div {:class (stl/css :sets-section-wrapper)}
    [:& sets-sidebar]]
    [:div {:class (stl/css :tokens-section-wrapper)}
      [:& tokens-explorer]]
    [:button {:class (stl/css :download-json-button)
              :on-click wtc/download-tokens-as-json}
      download-icon
      "Export JSON"]])
