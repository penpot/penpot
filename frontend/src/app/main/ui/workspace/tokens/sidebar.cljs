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
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu
                                                 dropdown-menu-item*]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
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
   [app.main.ui.workspace.tokens.token-pill :refer [token-pill]]
   [app.main.ui.workspace.tokens.token-types :as wtty]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def lens:token-type-open-status
  (l/derived (l/in [:workspace-tokens :open-status]) st/state))

;; Components ------------------------------------------------------------------

(defn token-section-icon
  [type]
  (case type
    :border-radius "corner-radius"
    :color "drop"
    :boolean "boolean-difference"
    :opacity "percentage"
    :rotation "rotation"
    :spacing "padding-extended"
    :string "text-mixed"
    :stroke-width "stroke-size"
    :typography "text"
    :dimensions "expand"
    :sizing "expand"
    "add"))

(defn attribute-actions [token selected-shapes attributes]
  (let [ids-by-attributes (wtt/shapes-ids-by-applied-attributes token selected-shapes attributes)
        shape-ids (into #{} (map :id selected-shapes))]
    {:all-selected? (wtt/shapes-applied-all? ids-by-attributes shape-ids attributes)
     :shape-ids shape-ids
     :selected-pred #(seq (% ids-by-attributes))}))

(mf/defc token-component
  [{:keys [type tokens selected-shapes token-type-props active-theme-tokens]}]
  (let [open? (mf/deref (-> (l/key type)
                            (l/derived lens:token-type-open-status)))
        {:keys [modal attributes all-attributes title]} token-type-props

        on-context-menu (mf/use-fn
                         (fn [event token]
                           (dom/prevent-default event)
                           (dom/stop-propagation event)
                           (st/emit! (dt/show-token-context-menu
                                      {:type :token
                                       :position (dom/get-client-position event)
                                       :errors (:errors token)
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
        tokens-count (count tokens)
        can-edit?  (:can-edit (deref refs/permissions))]
    [:div {:on-click on-toggle-open-click}
     [:& cmm/asset-section {:icon (token-section-icon type)
                            :title title
                            :assets-count tokens-count
                            :open? open?}
      [:& cmm/asset-section-block {:role :title-button}
       (when can-edit?
         [:> icon-button* {:on-click on-popover-open-click
                           :variant "ghost"
                           :icon "add"
                           ;;  TODO: This needs translation
                           :aria-label (str "Add token: " title)}])]
      (when open?
        [:& cmm/asset-section-block {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token (sort-by :name tokens)]
            (let [theme-token (get active-theme-tokens (wtt/token-identifier token))
                  multiple-selection (< 1 (count selected-shapes))
                  full-applied (:all-selected? (attribute-actions token selected-shapes (or all-attributes attributes)))
                  applied (wtt/shapes-token-applied? token selected-shapes (or all-attributes attributes))
                  on-token-click (fn [e]
                                   (on-token-pill-click e token))
                  on-context-menu (fn [e] (on-context-menu e token))]
              [:& token-pill
               {:key (:name token)
                :token-type-props token-type-props
                :token token
                :selected-shapes selected-shapes
                :active-theme-tokens active-theme-tokens
                :theme-token theme-token
                :half-applied (or (and applied multiple-selection)
                                  (and applied (not full-applied)))
                :full-applied (if multiple-selection
                                false
                                applied)
                :on-click on-token-click
                :on-context-menu on-context-menu}]))]])]]))

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
        can-edit?  (:can-edit (deref refs/permissions))
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
        (when can-edit?
          [:button {:on-click open-modal
                    :class (stl/css :create-theme-button)}
           (tr "workspace.token.create-one")])]
       (if can-edit?
         [:div {:class (stl/css :theme-select-wrapper)}
          [:& theme-select]
          [:> button* {:variant "secondary"
                       :class (stl/css :edit-theme-button)
                       :on-click open-modal}
           (tr "labels.edit")]]
         [:div {:title (when-not can-edit?
                         (tr "workspace.token.no-permission-themes"))}
          [:& theme-select]]))]))

(mf/defc add-set-button
  [{:keys [on-open style]}]
  (let [{:keys [on-create new?]} (sets-context/use-context)
        on-click #(do
                    (on-open)
                    (on-create))
        can-edit?  (:can-edit (deref refs/permissions))]
    (if (= style "inline")
      (when-not new?
        (if can-edit?
          [:div {:class (stl/css :empty-sets-wrapper)}
           [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
            (tr "workspace.token.no-sets-yet")]
           [:button {:on-click on-click
                     :class (stl/css :create-theme-button)}
            (tr "workspace.token.create-one")]]
          [:div {:class (stl/css :empty-sets-wrapper)}
           [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
            (tr "workspace.token.no-sets-yet")]]))
      (when can-edit?
        [:> icon-button* {:variant "ghost"
                          :icon "add"
                          :on-click on-click
                          :aria-label (tr "workspace.token.add set")}]))))

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
        on-open (mf/use-fn #(reset! open? true))
        can-edit? (:can-edit (deref refs/permissions))]
    [:& sets-context/provider {}
     [:& sets-context-menu]
     [:article {:data-testid "token-themes-sets-sidebar"
                :class (stl/css :sets-section-wrapper)
                :style {"--resize-height" (str resize-height "px")}}
      [:div {:class (stl/css :sets-sidebar)}
       [:& themes-header]
       [:div {:class (stl/css :sidebar-header)}
        [:& title-bar {:title (tr "labels.sets")}
         (when can-edit?
           [:& add-set-button {:on-open on-open
                               :style "header"}])]]
       [:& theme-sets-list {:on-open on-open}]]]]))

(mf/defc tokens-tab
  [_props]
  (let [objects (mf/deref refs/workspace-page-objects)

        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)

        active-theme-tokens (sd/use-active-theme-sets-tokens)

        tokens (sd/use-resolved-workspace-tokens)

        selected-token-set-tokens (mf/deref refs/workspace-selected-token-set-tokens)

        selected-token-set-name (mf/deref refs/workspace-selected-token-set-name)

        token-groups (mf/with-memo [tokens selected-token-set-tokens]
                       (-> (select-keys tokens (keys selected-token-set-tokens))
                           (sorted-token-groups)))]
    [:*
     [:& token-context-menu]
     [:& title-bar {:all-clickable true
                    :title (tr "workspace.token.tokens-section-title" selected-token-set-name)}]

     (for [{:keys [token-key token-type-props tokens]} (concat (:filled token-groups)
                                                               (:empty token-groups))]
       [:& token-component {:key token-key
                            :type token-key
                            :selected-shapes selected-shapes
                            :active-theme-tokens active-theme-tokens
                            :tokens tokens
                            :token-type-props token-type-props}])]))

(mf/defc import-export-button
  {::mf/wrap-props false}
  [{:keys []}]
  (let [show-menu* (mf/use-state false)
        show-menu? (deref show-menu*)
        can-edit? (:can-edit (deref refs/permissions))

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
        on-display-file-explorer
        (mf/use-fn
         #(.click (mf/ref-val input-ref)))

        on-import
        (fn [event]
          (let [file (-> event .-target .-files (aget 0))]
            (->> (wapi/read-file-as-text file)
                 (sd/process-json-stream)
                 (rx/subs! (fn [lib]
                             (st/emit! (ptk/event ::ev/event {::ev/name "import-tokens"}))
                             (st/emit! (dt/import-tokens-lib lib)))
                           (fn [err]
                             (js/console.error err)
                             (st/emit! (ntf/show {:content (wte/humanize-errors [(ex-data err)])
                                                  :type :toast
                                                  :level :error})))))
            (set! (.-value (mf/ref-val input-ref)) "")))
        on-export (fn []
                    (st/emit! (ptk/event ::ev/event {::ev/name "export-tokens"}))
                    (let [tokens-json (some-> (deref refs/tokens-lib)
                                              (ctob/encode-dtcg)
                                              (clj->js)
                                              (js/JSON.stringify nil 2))]
                      (->> (wapi/create-blob (or tokens-json "{}") "application/json")
                           (dom/trigger-download "tokens.json"))))]

    [:div {:class (stl/css :import-export-button-wrapper)}
     (when can-edit?
       [:input {:type "file"
                :ref input-ref
                :style {:display "none"}
                :id "file-input"
                :accept ".json"
                :on-change on-import}])
     [:> button* {:on-click open-menu
                  :icon "import-export"
                  :variant "secondary"}
      (tr "workspace.token.tools")]
     [:& dropdown-menu {:show show-menu?
                        :on-close close-menu
                        :list-class (stl/css :import-export-menu)}
      (when can-edit?
        [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                                 :on-click on-display-file-explorer}
         (tr "labels.import")])

      [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                               :on-click on-export}
       (tr "labels.export")]]]))

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
     [:article {:class (stl/css :tokens-section-wrapper)
                :data-testid "tokens-sidebar"}
      [:div {:class (stl/css :resize-area-horiz)
             :on-pointer-down on-pointer-down-pages
             :on-lost-pointer-capture on-lost-pointer-capture-pages
             :on-pointer-move on-pointer-move-pages}]
      [:& tokens-tab]]
     [:& import-export-button]]))
