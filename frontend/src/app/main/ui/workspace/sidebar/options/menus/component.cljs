;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.component
  (:require
   [app.common.pages.helpers :as cph]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(def component-attrs [:component-id :component-file :shape-ref])

(mf/defc component-menu
  [{:keys [ids values] :as props}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)

        id              (first ids)
        local           (mf/use-state {:menu-open false})

        component-id    (:component-id values)
        library-id      (:component-file values)

        local-file      (deref refs/workspace-local-library)
        libraries       (deref refs/workspace-libraries)

        ;; NOTE: this is necessary because the `cph/get-component`
        ;; expects a map of all libraries, including the local one.
        libraries       (assoc libraries (:id local-file) local-file)

        component       (cph/get-component libraries library-id component-id)
        show?           (some? component-id)

        on-menu-click
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (swap! local assoc :menu-open true)))

        on-menu-close
        (mf/use-callback
         #(swap! local assoc :menu-open false))

        do-detach-component
        (st/emitf (dwl/detach-component id))

        do-reset-component
        (st/emitf (dwl/reset-component id))

        do-update-component
        (st/emitf (dwl/update-component-sync id library-id))

        do-update-remote-component
        (st/emitf (modal/show
                   {:type :confirm
                    :message ""
                    :title (tr "modals.update-remote-component.message")
                    :hint (tr "modals.update-remote-component.hint")
                    :cancel-label (tr "modals.update-remote-component.cancel")
                    :accept-label (tr "modals.update-remote-component.accept")
                    :accept-style :primary
                    :on-accept do-update-component}))

        do-show-component (st/emitf (dw/go-to-component component-id))
        do-navigate-component-file (st/emitf (dwl/nav-to-component-file library-id))]
    (when show?
      [:div.element-set
       [:div.element-set-title
        [:span (tr "workspace.options.component")]]
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
                            :options (if (= library-id current-file-id)
                                       [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                        [(tr "workspace.shape.menu.reset-overrides") do-reset-component]
                                        [(tr "workspace.shape.menu.update-main") do-update-component]
                                        [(tr "workspace.shape.menu.show-main") do-show-component]]

                                       [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                        [(tr "workspace.shape.menu.reset-overrides") do-reset-component]
                                        [(tr "workspace.shape.menu.go-main") do-navigate-component-file]
                                        [(tr "workspace.shape.menu.update-main") do-update-remote-component]])}]]]]])))

