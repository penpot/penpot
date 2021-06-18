;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.component
  (:require
   [app.common.pages :as cp]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]
   [rumext.alpha :as mf]))

(def component-attrs [:component-id :component-file :shape-ref])

(mf/defc component-menu
  [{:keys [ids values] :as props}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)

        id     (first ids)
        locale (mf/deref i18n/locale)
        local  (mf/use-state {:menu-open false})

        show?         (some? (:component-id values))
        local-library (mf/deref refs/workspace-local-library)
        libraries     (mf/deref refs/workspace-libraries)
        component     (cp/get-component (:component-id values)
                                        (:component-file values)
                                        local-library
                                        libraries)

        on-menu-click (mf/use-callback
                        (fn [event]
                          (dom/prevent-default event)
                          (dom/stop-propagation event)
                          (swap! local assoc :menu-open true)))

        on-menu-close (mf/use-callback
                        #(swap! local assoc :menu-open false))

        do-detach-component (st/emitf (dwl/detach-component id))
        do-reset-component (st/emitf (dwl/reset-component id))
        do-update-component (st/emitf
                               (dwu/start-undo-transaction)
                               (dwl/update-component id)
                               (dwl/sync-file current-file-id current-file-id)
                               (dwu/commit-undo-transaction))
        confirm-update-remote-component (st/emitf
                                          (dwl/update-component id)
                                          (dwl/sync-file current-file-id
                                                         (:component-file values))
                                          (dwl/sync-file (:component-file values)
                                                         (:component-file values)))
        do-update-remote-component (st/emitf (modal/show
                                                {:type :confirm
                                                 :message ""
                                                 :title (t locale "modals.update-remote-component.message")
                                                 :hint (t locale "modals.update-remote-component.hint")
                                                 :cancel-label (t locale "modals.update-remote-component.cancel")
                                                 :accept-label (t locale "modals.update-remote-component.accept")
                                                 :accept-style :primary
                                                 :on-accept confirm-update-remote-component}))
        do-show-component (st/emitf (dw/go-to-layout :assets))
        do-navigate-component-file (st/emitf (dwl/nav-to-component-file
                                                (:component-file values)))]
    (when show?
      [:div.element-set
       [:div.element-set-title
        [:span (t locale "workspace.options.component")]]
       [:div.element-set-content
        [:div.row-flex.component-row
         i/component
         (:name component)
         [:div.row-actions
          {:on-click on-menu-click}
          i/actions
          ;; WARNING: this menu is the same as the shape context menu.
          ;;          If you change it, you must change equally the file
          ;;          app/main/ui/workspace/context_menu.cljs
          [:& context-menu {:on-close on-menu-close
                            :show (:menu-open @local)
                            :options (if (= (:component-file values) current-file-id)
                                       [[(t locale "workspace.shape.menu.detach-instance") do-detach-component]
                                        [(t locale "workspace.shape.menu.reset-overrides") do-reset-component]
                                        [(t locale "workspace.shape.menu.update-main") do-update-component]
                                        [(t locale "workspace.shape.menu.show-main") do-show-component]]

                                       [[(t locale "workspace.shape.menu.detach-instance") do-detach-component]
                                        [(t locale "workspace.shape.menu.reset-overrides") do-reset-component]
                                        [(t locale "workspace.shape.menu.go-main") do-navigate-component-file]
                                        [(t locale "workspace.shape.menu.update-main") do-update-remote-component]])}]]]]])))

