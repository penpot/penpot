;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu dropdown-menu-item*]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.context-menu :refer [token-context-menu]]
   [app.main.ui.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.sets :refer [sets-list]]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.main.ui.workspace.tokens.sets-context-menu :refer [sets-context-menu]]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.theme-select :refer [theme-select]]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-types :as wtty]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def lens:token-type-open-status
  (l/derived (l/in [:workspace-tokens :open-status]) st/state))

(def ^:private download-icon
  (i/icon-xref :download (stl/css :download-icon)))

;; Components ------------------------------------------------------------------

(mf/defc token-pill
  {::mf/wrap-props false}
  [{:keys [on-click token theme-token highlighted? on-context-menu]}]
  (let [{:keys [name value resolved-value errors]} token
        errors? (and (seq errors) (seq (:errors theme-token)))]
    [:button
     {:class (stl/css-case :token-pill true
                           :token-pill-highlighted highlighted?
                           :token-pill-invalid errors?)
      :title (cond
               errors? (sd/humanize-errors token)
               :else (->> [(str "Token: " name)
                           (str (tr "workspace.token.original-value") value)
                           (str (tr "workspace.token.resolved-value") resolved-value)]
                          (str/join "\n")))
      :on-click on-click
      :on-context-menu on-context-menu
      :disabled errors?}
     (when-let [color (if (seq (ctob/find-token-value-references (:value token)))
                        (wtt/resolved-value-hex theme-token)
                        (wtt/resolved-value-hex token))]
       [:& color-bullet {:color color
                         :mini true}])
     name]))

(mf/defc token-section-icon
  {::mf/wrap-props false}
  [{:keys [type]}]
  (case type
    :border-radius i/corner-radius
    :numeric [:span {:class (stl/css :section-text-icon)} "123"]
    :color i/drop-icon
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
  [{:keys [type tokens selected-shapes token-type-props active-theme-tokens]}]
  (let [open? (mf/deref (-> (l/key type)
                            (l/derived lens:token-type-open-status)))
        {:keys [modal attributes all-attributes title]} token-type-props

        on-context-menu (mf/use-fn
                         (fn [event token]
                           (dom/prevent-default event)
                           (dom/stop-propagation event)
                           (st/emit! (dt/show-token-context-menu {:type :token
                                                                  :position (dom/get-client-position event)
                                                                  :token-name (:name token)}))))

        on-toggle-open-click (mf/use-fn
                              (mf/deps open? tokens)
                              #(st/emit! (dt/set-token-type-section-open type (not open?))))
        on-popover-open-click (mf/use-fn
                               (fn [event]
                                 (mf/deps type title)
                                 (let [{:keys [key fields]} modal]
                                   (dom/stop-propagation event)
                                   (st/emit! (dt/set-token-type-section-open type true))
                                   (modal/show! key {:x (.-clientX ^js event)
                                                     :y (.-clientY ^js event)
                                                     :position :right
                                                     :fields fields
                                                     :title title
                                                     :action "create"
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
     [:& cmm/asset-section {:icon (mf/fnc icon-wrapper []
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
          (for [token (sort-by :name tokens)]
            (let [theme-token (get active-theme-tokens (wtt/token-identifier token))]
              [:& token-pill
               {:key (:name token)
                :token token
                :theme-token theme-token
                :highlighted? (wtt/shapes-token-applied? token selected-shapes (or all-attributes attributes))
                :on-click #(on-token-pill-click % token)
                :on-context-menu #(on-context-menu % token)}]))]])]]))

(defn sorted-token-groups
  "Separate token-types into groups of `:empty` or `:filled` depending if tokens exist for that type.
  Sort each group alphabetically (by their `:token-key`)."
  [tokens]
  (let [tokens-by-type (ctob/group-by-type tokens)
        {:keys [empty filled]} (->> wtty/token-types
                                    (map (fn [[token-key token-type-props]]
                                           {:token-key token-key
                                            :token-type-props token-type-props
                                            :tokens (get tokens-by-type token-key [])}))
                                    (group-by (fn [{:keys [tokens]}]
                                                (if (empty? tokens) :empty :filled))))]
    {:empty (sort-by :token-key empty)
     :filled (sort-by :token-key filled)}))

(mf/defc themes-header
  [_props]
  (let [ordered-themes (mf/deref refs/workspace-token-themes-no-hidden)
        open-modal
        (mf/use-fn
         (fn [e]
           (dom/stop-propagation e)
           (modal/show! :tokens/themes {})))]
    [:div {:class (stl/css :themes-wrapper)}
     [:span {:class (stl/css :themes-header)} (tr "labels.themes")]
     (if (empty? ordered-themes)
       [:div {:class (stl/css :empty-theme-wrapper)}
        [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
         (tr "workspace.token.no-themes")]
        [:button {:on-click open-modal
                  :class (stl/css :create-theme-button)}
         (tr "workspace.token.create-one")]]
       [:div {:class (stl/css :theme-select-wrapper)}
        [:& theme-select]
        [:> button* {:variant "secondary"
                     :class (stl/css :edit-theme-button)
                     :on-click open-modal}
         (tr "labels.edit")]])]))

(mf/defc add-set-button
  [{:keys [on-open style]}]
  (let [{:keys [on-create new?]} (sets-context/use-context)
        on-click #(do
                    (on-open)
                    (on-create))]
    (if (= style "inline")
      (when-not new?
        [:div {:class (stl/css :empty-sets-wrapper)}
         [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
          (tr "workspace.token.no-sets-yet")]
         [:button {:on-click on-click
                   :class (stl/css :create-theme-button)}
          (tr "workspace.token.create-one")]])
      [:> icon-button* {:variant "ghost"
                        :icon "add"
                        :on-click on-click
                        :aria-label (tr "workspace.token.add set")}])))

(mf/defc theme-sets-list
  [{:keys [on-open]}]
  (let [token-sets (mf/deref refs/workspace-ordered-token-sets)
        {:keys [new?] :as ctx} (sets-context/use-context)]
    (if (and (empty? token-sets)
             (not new?))
      [:& add-set-button {:on-open on-open
                          :style "inline"}]
      [:& h/sortable-container {}
       [:& sets-list]])))

(mf/defc themes-sets-tab
  [{:keys [resize-height]}]
  (let [open? (mf/use-state true)
        on-open (mf/use-fn #(reset! open? true))]
    [:& sets-context/provider {}
     [:& sets-context-menu]
     [:article {:class (stl/css :sets-section-wrapper)
                :style {"--resize-height" (str resize-height "px")}}
      [:div {:class (stl/css :sets-sidebar)}
       [:& themes-header]
       [:div {:class (stl/css :sidebar-header)}
        [:& title-bar {:collapsable true
                       :collapsed (not @open?)
                       :all-clickable true
                       :title (tr "labels.sets")
                       :on-collapsed #(swap! open? not)}
         [:& add-set-button {:on-open on-open
                             :style "header"}]]]
       [:& theme-sets-list {:on-open on-open}]]]]))

(mf/defc tokens-tab
  [_props]
  (let [objects (mf/deref refs/workspace-page-objects)

        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)

        active-theme-tokens (sd/use-active-theme-sets-tokens)

        tokens (sd/use-resolved-workspace-tokens)
        token-groups (mf/with-memo [tokens]
                       (sorted-token-groups tokens))]
    [:*
     [:& token-context-menu]
     [:& title-bar {:all-clickable true
                    :title "TOKENS"}]
     [:div.assets-bar
      (for [{:keys [token-key token-type-props tokens]} (concat (:filled token-groups)
                                                                (:empty token-groups))]
        [:& token-component {:key token-key
                             :type token-key
                             :selected-shapes selected-shapes
                             :active-theme-tokens active-theme-tokens
                             :tokens tokens
                             :token-type-props token-type-props}])]]))

(mf/defc json-import-button []
  (let []
    [:div

     [:button {:class (stl/css :download-json-button)
               :on-click #(.click (js/document.getElementById "file-input"))}
      download-icon
      "Import JSON"]]))

(mf/defc import-export-button
  {::mf/wrap-props false}
  [{:keys []}]
  (let [show-menu* (mf/use-state false)
        show-menu? (deref show-menu*)

        open-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* true)))

        close-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)))

        input-ref (mf/use-ref)
        on-import
        (fn [event]
          (let [file (-> event .-target .-files (aget 0))]
            (->> (wapi/read-file-as-text file)
                 (sd/process-json-stream)
                 (rx/subs! (fn [lib]
                             (st/emit! (dt/import-tokens-lib lib)))
                           (fn [err]
                             (js/console.error err)
                             (st/emit! (ntf/show {:content (wte/humanize-errors [(ex-data err)])
                                                  :type :toast
                                                  :level :warning
                                                  :timeout 9000})))))
            (set! (.-value (mf/ref-val input-ref)) "")))
        on-export (fn []
                    (let [tokens-blob (some-> (deref refs/tokens-lib)
                                              (ctob/encode-dtcg)
                                              (clj->js)
                                              (js/JSON.stringify nil 2)
                                              (wapi/create-blob "application/json"))]
                      (dom/trigger-download "tokens.json" tokens-blob)))]
    [:div {:class (stl/css :import-export-button-wrapper)}
     [:input {:type "file"
              :ref input-ref
              :style {:display "none"}
              :id "file-input"
              :accept ".json"
              :on-change on-import}]
     [:button {:class (stl/css :import-export-button)
               :on-click open-menu}
      download-icon
      "Tokens"]
     [:& dropdown-menu {:show show-menu?
                        :on-close close-menu
                        :list-class (stl/css :import-export-menu)}
      [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                               :on-click #(.click (mf/ref-val input-ref))}
       "Import"]

      [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                               :on-click on-export}
       "Export"]]]))

(mf/defc tokens-sidebar-tab
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [_props]
  (let [{on-pointer-down-pages :on-pointer-down
         on-lost-pointer-capture-pages :on-lost-pointer-capture
         on-pointer-move-pages :on-pointer-move
         size-pages-opened :size}
        (use-resize-hook :tokens 200 38 400 :y false nil)]
    [:div {:class (stl/css :sidebar-wrapper)}
     [:& themes-sets-tab {:resize-height size-pages-opened}]
     [:article {:class (stl/css :tokens-section-wrapper)}
      [:div {:class (stl/css :resize-area-horiz)
             :on-pointer-down on-pointer-down-pages
             :on-lost-pointer-capture on-lost-pointer-capture-pages
             :on-pointer-move on-pointer-move-pages}]
      [:& tokens-tab]]
     [:& import-export-button]]))
