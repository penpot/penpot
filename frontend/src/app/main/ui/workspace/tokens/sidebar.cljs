;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.json :as json]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu dropdown-menu-item*]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.tokens.context-menu :refer [token-context-menu]]
   [app.main.ui.workspace.tokens.sets :as tsets]
   [app.main.ui.workspace.tokens.sets-context-menu :refer [token-set-context-menu*]]
   [app.main.ui.workspace.tokens.theme-select :refer [theme-select]]
   [app.main.ui.workspace.tokens.token-pill :refer [token-pill*]]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]
   [shadow.resource]))

(def ref:token-type-open-status
  (l/derived (l/key :open-status-by-type) refs/workspace-tokens))

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

(mf/defc token-group*
  {::mf/private true}
  [{:keys [type tokens selected-shapes active-theme-tokens is-open]}]
  (let [{:keys [modal title]}
        (get dwta/token-properties type)

        can-edit?
        (mf/use-ctx ctx/can-edit?)

        tokens
        (mf/with-memo [tokens]
          (vec (sort-by :name tokens)))

        on-context-menu
        (mf/use-fn
         (fn [event token]
           (dom/prevent-default event)
           (st/emit! (dwtl/assign-token-context-menu
                      {:type :token
                       :position (dom/get-client-position event)
                       :errors (:errors token)
                       :token-name (:name token)}))))

        on-toggle-open-click
        (mf/use-fn
         (mf/deps is-open type)
         #(st/emit! (dwtl/set-token-type-section-open type (not is-open))))

        on-popover-open-click
        (mf/use-fn
         (mf/deps type title modal)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dwtl/set-token-type-section-open type true)
                     ;; FIXME: use dom/get-client-position
                     (modal/show (:key modal)
                                 {:x (.-clientX ^js event)
                                  :y (.-clientY ^js event)
                                  :position :right
                                  :fields (:fields modal)
                                  :title title
                                  :action "create"
                                  :token-type type}))))

        on-token-pill-click
        (mf/use-fn
         (mf/deps selected-shapes)
         (fn [event token]
           (dom/stop-propagation event)
           (when (seq selected-shapes)
             (st/emit! (dwta/toggle-token {:token token
                                           :shapes selected-shapes})))))]

    [:div {:on-click on-toggle-open-click :class (stl/css :token-section-wrapper)}
     [:& cmm/asset-section {:icon (token-section-icon type)
                            :title title
                            :section :tokens
                            :assets-count (count tokens)
                            :open? is-open}
      [:& cmm/asset-section-block {:role :title-button}
       (when can-edit?
         [:> icon-button* {:on-click on-popover-open-click
                           :variant "ghost"
                           :icon "add"
                           ;;  TODO: This needs translation
                           :aria-label (str "Add token: " title)}])]
      (when is-open
        [:& cmm/asset-section-block {:role :content}
         [:div {:class (stl/css :token-pills-wrapper)}
          (for [token tokens]
            [:> token-pill*
             {:key (:name token)
              :token token
              :selected-shapes selected-shapes
              :active-theme-tokens active-theme-tokens
              :on-click on-token-pill-click
              :on-context-menu on-context-menu}])]])]]))

(defn- get-sorted-token-groups
  "Separate token-types into groups of `empty` or `filled` depending if
  tokens exist for that type.  Sort each group alphabetically (by
  their type)."
  [tokens-by-type]
  (loop [empty  #js []
         filled #js []
         types  (-> dwta/token-properties keys seq)]
    (if-let [type (first types)]
      (if (not-empty (get tokens-by-type type))
        (recur empty
               (array/conj! filled type)
               (rest types))
        (recur (array/conj! empty type)
               filled
               (rest types)))
      [(seq (array/sort! empty))
       (seq (array/sort! filled))])))

(mf/defc themes-header*
  {::mf/private true}
  []
  (let [ordered-themes
        (mf/deref refs/workspace-token-themes-no-hidden)

        can-edit?
        (mf/use-ctx ctx/can-edit?)

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

(mf/defc token-sets-list*
  {::mf/private true}
  [{:keys [tokens-lib]}]
  (let [;; FIXME: This is an inneficient operation just for being
        ;; ability to check if there are some sets and lookup the
        ;; first one when no set is selected, should be REFACTORED; is
        ;; inneficient because instead of return the sets as-is (tree)
        ;; it firstly makes it a plain seq from tree.
        token-sets
        (some-> tokens-lib (ctob/get-sets))

        selected-token-set-name
        (mf/deref refs/selected-token-set-name)

        {:keys [token-set-edition-id
                token-set-new-path]}
        (mf/deref refs/workspace-tokens)]

    (if (and (empty? token-sets)
             (not token-set-new-path))

      (when-not token-set-new-path
        [:> tsets/inline-add-button*])

      [:> h/sortable-container {}
       [:> tsets/sets-list*
        {:tokens-lib tokens-lib
         :new-path token-set-new-path
         :edition-id token-set-edition-id
         :selected selected-token-set-name}]])))

(mf/defc token-sets-section*
  {::mf/private true}
  [{:keys [resize-height] :as props}]

  (let [can-edit?
        (mf/use-ctx ctx/can-edit?)]

    [:*
     [:> token-set-context-menu*]
     [:article {:data-testid "token-themes-sets-sidebar"
                :class (stl/css :sets-section-wrapper)
                :style {"--resize-height" (str resize-height "px")}}
      [:div {:class (stl/css :sets-sidebar)}
       [:> themes-header*]
       [:div {:class (stl/css :sidebar-header)}
        [:& title-bar {:title (tr "labels.sets")}
         (when can-edit?
           [:> tsets/add-button*])]]

       [:> token-sets-list* props]]]]))

(mf/defc tokens-section*
  [{:keys [tokens-lib]}]
  (let [objects         (mf/deref refs/workspace-page-objects)
        selected        (mf/deref refs/selected-shapes)
        open-status     (mf/deref ref:token-type-open-status)

        selected-shapes
        (mf/with-memo [selected objects]
          (into [] (keep (d/getf objects)) selected))

        active-theme-tokens
        (mf/with-memo [tokens-lib]
          (if tokens-lib
            (ctob/get-active-themes-set-tokens tokens-lib)
            {}))

        ;; Resolve tokens as second step
        active-theme-tokens'
        (sd/use-resolved-tokens* active-theme-tokens)

        ;; This only checks for the currently explicitly selected set
        ;; name, it is ephimeral and can be nil
        selected-token-set-name
        (mf/deref refs/selected-token-set-name)

        selected-token-set
        (when selected-token-set-name
          (some-> tokens-lib (ctob/get-set selected-token-set-name)))

        ;; If we have not selected any set explicitly we just
        ;; select the first one from the list of sets
        selected-token-set-tokens
        (when selected-token-set
          (get selected-token-set :tokens))

        tokens
        (mf/with-memo [active-theme-tokens selected-token-set-tokens]
          (merge active-theme-tokens selected-token-set-tokens))

        tokens
        (sd/use-resolved-tokens* tokens)

        tokens-by-type
        (mf/with-memo [tokens selected-token-set-tokens]
          (let [tokens (reduce-kv (fn [tokens k _]
                                    (if (contains? selected-token-set-tokens k)
                                      tokens
                                      (dissoc tokens k)))
                                  tokens
                                  tokens)]
            (ctob/group-by-type tokens)))

        active-token-sets-names
        (mf/with-memo [tokens-lib]
          (some-> tokens-lib (ctob/get-active-themes-set-names)))

        token-set-active?
        (mf/use-fn
         (mf/deps active-token-sets-names)
         (fn [name]
           (contains? active-token-sets-names name)))

        [empty-group filled-group]
        (mf/with-memo [tokens-by-type]
          (get-sorted-token-groups tokens-by-type))]

    (mf/with-effect [tokens-lib selected-token-set-name]
      (when (and tokens-lib
                 (or (nil? selected-token-set-name)
                     (and selected-token-set-name
                          (not (ctob/get-set tokens-lib selected-token-set-name)))))
        (let [match (->> (ctob/get-sets tokens-lib)
                         (first)
                         (:name))]
          (st/emit! (dwtl/set-selected-token-set-name match)))))

    [:*
     [:& token-context-menu]
     [:div {:class (stl/css :sets-header-container)}
      [:span {:class (stl/css :sets-header)} (tr "workspace.token.tokens-section-title" selected-token-set-name)]
      [:div {:class (stl/css :sets-header-status) :title (tr "workspace.token.inactive-set-description")}
       ;; NOTE: when no set in tokens-lib, the selected-token-set-name
       ;; will be `nil`, so for properly hide the inactive message we
       ;; check that at least `selected-token-set-name` has a value
       (when (and (some? selected-token-set-name)
                  (not (token-set-active? selected-token-set-name)))
         [:*
          [:> i/icon* {:class (stl/css :sets-header-status-icon) :icon-id i/eye-off}]
          [:span {:class (stl/css :sets-header-status-text)}
           (tr "workspace.token.inactive-set")]])]]

     (for [type filled-group]
       (let [tokens (get tokens-by-type type)]
         [:> token-group* {:key (name type)
                           :is-open (get open-status type false)
                           :type type
                           :selected-shapes selected-shapes
                           :active-theme-tokens active-theme-tokens'
                           :tokens tokens}]))

     (for [type empty-group]
       [:> token-group* {:key (name type)
                         :type type
                         :selected-shapes selected-shapes
                         :active-theme-tokens active-theme-tokens'
                         :tokens []}])]))

(mf/defc import-export-button*
  []
  (let [input-ref  (mf/use-ref)

        show-menu* (mf/use-state false)
        show-menu? (deref show-menu*)

        can-edit?
        (mf/use-ctx ctx/can-edit?)

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

        on-display-file-explorer
        (mf/use-fn #(dom/click (mf/ref-val input-ref)))

        on-import
        (mf/use-fn
         (fn [event]
           (let [file (-> (dom/get-target event)
                          (dom/get-files)
                          (first))
                 file-name (str/replace (.-name file) ".json" "")]
             (->> (wapi/read-file-as-text file)
                  (sd/process-json-stream {:file-name file-name})
                  (rx/subs! (fn [lib]
                              (st/emit! (ptk/data-event ::ev/event {::ev/name "import-tokens"})
                                        (dwtl/import-tokens-lib lib)))
                            (fn [err]
                              (js/console.error err)
                              (st/emit! (ntf/show {:content (wte/humanize-errors [(ex-data err)])
                                                   :detail (wte/detail-errors [(ex-data err)])
                                                   :type :toast
                                                   :level :error})))))
             (-> (mf/ref-val input-ref)
                 (dom/set-value! "")))))

        on-export
        (mf/use-fn
         (fn []
           (st/emit! (ptk/data-event ::ev/event {::ev/name "export-tokens"}))
           (let [tokens-json (some-> (deref refs/tokens-lib)
                                     (ctob/encode-dtcg)
                                     (json/encode :key-fn identity))]
             (->> (wapi/create-blob (or tokens-json "{}") "application/json")
                  (dom/trigger-download "tokens.json")))))]

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
         [:div {:class (stl/css :import-menu-item)}
          [:div (tr "labels.import")]
          [:div {:class (stl/css :import-export-menu-item-icon) :title (tr "workspace.token.import-tooltip")}
           [:> i/icon* {:icon-id i/info :aria-label (tr "workspace.token.import-tooltip")}]]]])
      [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                               :on-click on-export}
       (tr "labels.export")]]]))

(mf/defc tokens-sidebar-tab*
  {::mf/wrap [mf/memo]}
  []
  (let [{on-pointer-down-pages :on-pointer-down
         on-lost-pointer-capture-pages :on-lost-pointer-capture
         on-pointer-move-pages :on-pointer-move
         size-pages-opened :size}
        (use-resize-hook :tokens 200 38 "0.6" :y false nil)

        tokens-lib
        (mf/deref refs/tokens-lib)]

    [:div {:class (stl/css :sidebar-wrapper)}
     [:> token-sets-section*
      {:resize-height size-pages-opened
       :tokens-lib tokens-lib}]
     [:article {:class (stl/css :tokens-section-wrapper)
                :data-testid "tokens-sidebar"}
      [:div {:class (stl/css :resize-area-horiz)
             :on-pointer-down on-pointer-down-pages
             :on-lost-pointer-capture on-lost-pointer-capture-pages
             :on-pointer-move on-pointer-move-pages}
       [:div {:class (stl/css :resize-handle-horiz)}]]
      [:> tokens-section* {:tokens-lib tokens-lib}]]
     [:> import-export-button*]]))
