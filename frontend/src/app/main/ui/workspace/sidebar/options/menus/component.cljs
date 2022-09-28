;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.component
  (:require
       [app.common.types.components-list :as ctkl]
    [app.common.types.file :as ctf]
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
    [rumext.v2 :as mf]))

(def component-attrs [:component-id :component-file :shape-ref :main-instance?])

(mf/defc component-menu
  [{:keys [ids values shape-name] :as props}]
  (let [current-file-id    (mf/use-ctx ctx/current-file-id)
        components-v2      (mf/use-ctx ctx/components-v2)

        id                 (first ids)
        local              (mf/use-state {:menu-open false})

        component-id       (:component-id values)
        library-id         (:component-file values)
        show?              (some? component-id)
        main-instance?     (if components-v2
                             (:main-instance? values)
                             true)
        local-component?    (= library-id current-file-id)
        workspace-data      (deref refs/workspace-data)
        workspace-libraries (deref refs/workspace-libraries)
        is-dangling?        (nil? (if local-component?
                                    (ctkl/get-component workspace-data component-id)
                                    (ctf/get-component workspace-libraries library-id component-id)))

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
        #(st/emit! (dwl/detach-component id))

        _do-reset-component
        #(st/emit! (dwl/reset-component id))

        do-update-component
        #(st/emit! (dwl/update-component-sync id library-id))

        do-restore-component
        #(st/emit! (dwl/restore-component component-id))

        _do-update-remote-component
        #(st/emit! (modal/show
                    {:type :confirm
                     :message ""
                     :title (tr "modals.update-remote-component.message")
                     :hint (tr "modals.update-remote-component.hint")
                     :cancel-label (tr "modals.update-remote-component.cancel")
                     :accept-label (tr "modals.update-remote-component.accept")
                     :accept-style :primary
                     :on-accept do-update-component}))

        do-show-component #(st/emit! (dw/go-to-component component-id))
        do-navigate-component-file #(st/emit! (dwl/nav-to-component-file library-id))]
    (when show?
      [:div.element-set
       [:div.element-set-title
        [:span (tr "workspace.options.component")]]
       [:div.element-set-content
        [:div.row-flex.component-row
         (if main-instance?
           i/component
           i/component-copy)
         shape-name
         [:div.row-actions
          {:on-click on-menu-click}
          i/actions
          ;; WARNING: this menu is the same as the shape context menu.
          ;;          If you change it, you must change equally the file
          ;;          app/main/ui/workspace/context_menu.cljs
          [:& context-menu {:on-close on-menu-close
                            :show (:menu-open @local)
                            :options 
                            (if main-instance?
                              [[(tr "workspace.shape.menu.show-in-assets") do-show-component]]
                              (if local-component?
                                (if is-dangling?
                                  [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                   ;; [(tr "workspace.shape.menu.reset-overrides") _do-reset-component]
                                   (when components-v2
                                     [(tr "workspace.shape.menu.restore-main") do-restore-component])]

                                  [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                   ;; [(tr "workspace.shape.menu.reset-overrides") _do-reset-component]
                                   [(tr "workspace.shape.menu.update-main") do-update-component]
                                   [(tr "workspace.shape.menu.show-main") do-show-component]])
                                
                                (if is-dangling?
                                  [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                   ;; [(tr "workspace.shape.menu.reset-overrides") _do-reset-component]
                                   (when components-v2
                                     [(tr "workspace.shape.menu.restore-main") do-restore-component])]
                                  [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                   ;; [(tr "workspace.shape.menu.reset-overrides") _do-reset-component]
                                   ;; [(tr "workspace.shape.menu.update-main") _do-update-remote-component]
                                   [(tr "workspace.shape.menu.go-main") do-navigate-component-file]
                                   ])))}]]]]])))

