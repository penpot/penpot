;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.tokens.common :refer [workspace-shapes]]
   [app.main.ui.workspace.tokens.core :refer [token-types tokens-applied?]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc token-pill
  {::mf/wrap-props false}
  [{:keys [on-click token highlighted?]}]
  (let [{:keys [name value]} token]
    [:div {:class (stl/css-case :token-pill true
                                :token-pill-highlighted highlighted?)
           :title (str "Token value: " value)
           :on-click on-click}
     name]))

(defn- on-apply-token [token attributes selected-shapes on-apply on-update-shape event]
  (let [shapes-to-apply-token (filter #(not (tokens-applied? token % attributes)) selected-shapes)
        shapes-to-apply-token-ids (map #(:id %) shapes-to-apply-token)]
    (dom/stop-propagation event)
    (doseq [shape selected-shapes]
      (st/emit! (on-apply {:token-id (:id token)
                           :shape-id (:id shape)
                           :attributes attributes}))
      (st/emit! (on-update-shape (:value token) shapes-to-apply-token-ids)))))

(mf/defc token-component
  [{:keys [type file tokens selected-shapes token-type-props]}]
  (let [open? (mf/use-state false)
        {:keys [modal attributes title on-apply on-update-shape]} token-type-props
        on-toggle-open-click (mf/use-fn
                              (mf/deps open? tokens)
                              #(when (seq tokens)
                                 (swap! open? not)))
        on-popover-open-click (mf/use-fn
                               (fn [event]
                                 (let [{:keys [key fields]} modal]
                                   (dom/stop-propagation event)
                                   (modal/show! key {:x (.-clientX ^js event)
                                                     :y (.-clientY ^js event)
                                                     :position :right
                                                     :fields fields
                                                     :token-type type}))))
        tokens-count (count tokens)]
    [:div {:on-click on-toggle-open-click}
     [:& cmm/asset-section {:file-id (:id file)
                            :title title
                            :assets-count tokens-count
                            :open? @open?}
      [:& cmm/asset-section-block {:role :title-button}
       [:button {:class (stl/css :action-button)
                 :on-click on-popover-open-click}
        i/add]]
      (when open?
        [:& cmm/asset-section-block {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token tokens]
            [:& token-pill {:key (:id token)
                            :token token
                            :highlighted? (tokens-applied? token selected-shapes attributes)
                            :on-click #(on-apply-token token attributes selected-shapes on-apply on-update-shape %1)}])]])]]))

(mf/defc tokens-explorer
  [_props]
  (let [file (mf/deref refs/workspace-file)
        current-page-id (:current-page-id @st/state)
        workspace-data (mf/deref refs/workspace-data)
        tokens (get workspace-data :tokens)
        tokens-by-group (->> (vals tokens)
                             (group-by :type))
        selected-shape-ids (mf/deref refs/selected-shapes)
        selected-shapes (workspace-shapes workspace-data current-page-id selected-shape-ids)]
    (js/console.log "tokens" tokens)
    [:article
     [:& search-bar {:placeholder "Filter"
                     :on-change js/console.log}]
     [:div.assets-bar
      (for [[token-key token-type-props] token-types
            :let [tokens (or (get tokens-by-group token-key) [])]]
        [:& token-component {:key token-key
                             :type token-key
                             :file file
                             :selected-shapes selected-shapes
                             :tokens tokens
                             :token-type-props token-type-props}])]]))

(mf/defc tokens-sidebar-tab
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [_props]
  [:div {:class (stl/css :sidebar-tab-wrapper)}
   [:& tokens-explorer]])
