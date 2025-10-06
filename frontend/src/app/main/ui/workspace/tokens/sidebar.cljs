;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.workspace.tokens.management :refer [tokens-section*]]
   [app.main.ui.workspace.tokens.sets :as tsets]
   [app.main.ui.workspace.tokens.sets.context-menu :refer [token-set-context-menu*]]
   [app.main.ui.workspace.tokens.sets.lists :as tsetslist]
   [app.main.ui.workspace.tokens.themes :refer [themes-header*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]
   [shadow.resource]))

;; Components ------------------------------------------------------------------

(mf/defc token-sets-list*
  {::mf/private true}
  [{:keys [tokens-lib]}]
  (let [token-sets
        (some-> tokens-lib (ctob/get-set-tree))

        selected-token-set-id
        (mf/deref refs/selected-token-set-id)

        {:keys [token-set-edition-id
                token-set-new-path]}
        (mf/deref refs/workspace-tokens)]

    (if (and (empty? token-sets)
             (not token-set-new-path))

      (when-not token-set-new-path
        [:> tsetslist/inline-add-button*])

      [:> h/sortable-container* {}
       [:> tsets/sets-list*
        {:tokens-lib tokens-lib
         :new-path token-set-new-path
         :edition-id token-set-edition-id
         :selected selected-token-set-id}]])))

(mf/defc token-management-section*
  {::mf/private true}
  [{:keys [resize-height] :as props}]

  (let [can-edit?
        (mf/use-ctx ctx/can-edit?)]

    [:*
     [:> token-set-context-menu*]
     [:section {:data-testid "token-management-sidebar"
                :class (stl/css :token-management-section-wrapper)
                :style {"--resize-height" (str resize-height "px")}}
      [:> themes-header*]
      [:div {:class (stl/css :sidebar-header)}
       [:> title-bar* {:title (tr "labels.sets")}
        (when can-edit?
          [:> tsetslist/add-button*])]]

      [:> token-sets-list* props]]]))

(mf/defc import-export-button*
  []
  (let [show-menu* (mf/use-state false)
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

        on-export
        (mf/use-fn
         (fn []
           (modal/show! :tokens/export {})))

        on-modal-show
        (mf/use-fn
         (fn []
           (modal/show! :tokens/import {})))

        open-settings-modal
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (modal/show! :tokens/settings {})))]

    [:div {:class (stl/css :import-export-button-wrapper)}
     [:> button* {:on-click open-menu
                  :icon i/import-export
                  :variant "secondary"}
      (tr "workspace.tokens.tools")]
     [:> dropdown-menu* {:show show-menu?
                         :on-close close-menu
                         :id "tokens-menu"
                         :class (stl/css :import-export-menu)}
      (when can-edit?
        [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                                 :on-click on-modal-show}
         [:div {:class (stl/css :import-menu-item)}
          [:div (tr "labels.import")]]])
      [:> dropdown-menu-item* {:class (stl/css :import-export-menu-item)
                               :on-click on-export}
       (tr "labels.export")]]


     (when (and can-edit? (contains? cf/flags :token-base-font-size))
       [:> icon-button* {:variant "secondary"
                         :icon i/settings
                         :aria-label "Settings"
                         :on-click open-settings-modal}])]))

(mf/defc tokens-sidebar-tab*
  [{:keys [tokens-lib] :as props}]
  (let [{on-pointer-down-pages :on-pointer-down
         on-lost-pointer-capture-pages :on-lost-pointer-capture
         on-pointer-move-pages :on-pointer-move
         size-pages-opened :size}
        (use-resize-hook :tokens 200 38 "0.6" :y false nil)]

    [:div {:class (stl/css :sidebar-wrapper)}
     [:> token-management-section*
      {:resize-height size-pages-opened
       :tokens-lib tokens-lib}]
     [:article {:class (stl/css :tokens-section-wrapper)
                :data-testid "tokens-sidebar"}
      [:div {:class (stl/css :resize-area-horiz)
             :on-pointer-down on-pointer-down-pages
             :on-lost-pointer-capture on-lost-pointer-capture-pages
             :on-pointer-move on-pointer-move-pages}
       [:div {:class (stl/css :resize-handle-horiz)}]]
      [:> tokens-section* props]]
     [:> import-export-button*]]))
