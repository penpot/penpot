;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.component
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def component-attrs [:component-id :component-file :shape-ref :main-instance :annotation])


(mf/defc component-annotation
  [{:keys [id values shape component] :as props}]
  (let [main-instance?        (:main-instance values)
        component-id          (:component-id values)
        annotation            (:annotation component)
        editing?              (mf/use-state false)
        invalid-text?         (mf/use-state (or (nil? annotation)(str/empty? annotation)))
        size                  (mf/use-state (count annotation))
        textarea-ref          (mf/use-ref)

        ;; hack to create an autogrowing textarea
        ;; based on https://css-tricks.com/the-cleanest-trick-for-autogrowing-textareas/
        autogrow              #(let [textarea (mf/ref-val textarea-ref)
                                     text (when textarea (.-value textarea))]
                                 (reset! invalid-text? (str/empty? text))
                                 (when textarea
                                   (reset! size (count text))
                                   (aset (.-dataset (.-parentNode textarea)) "replicatedValue" text)))
        initialize            #(let [textarea (mf/ref-val textarea-ref)]
                                 (when textarea
                                   (aset textarea "value" annotation)
                                   (autogrow)))

        discard               (fn [event]
                                (dom/stop-propagation event)
                                (let [textarea (mf/ref-val textarea-ref)]
                                  (aset textarea "value" annotation)
                                  (reset! editing? false)
                                  (st/emit! (dw/set-annotations-id-for-create nil))))
        save                  (fn [event]
                                (dom/stop-propagation event)
                                (let [textarea (mf/ref-val textarea-ref)
                                      text (.-value textarea)]
                                  (when-not (str/blank? text)
                                    (reset! editing? false)
                                    (st/emit!
                                     (dw/set-annotations-id-for-create nil)
                                     (dw/update-component-annotation component-id text)))))
        workspace-annotations (mf/deref refs/workspace-annotations)
        annotations-expanded? (:expanded? workspace-annotations)
        creating?             (= id (:id-for-create workspace-annotations))

        expand                #(when-not (or @editing? creating?)
                                 (st/emit! (dw/set-annotations-expanded %)))
        edit                  (fn [event]
                                (dom/stop-propagation event)
                                (when main-instance?
                                  (let [textarea (mf/ref-val textarea-ref)]
                                    (reset! editing? true)
                                    (dom/focus! textarea))))
        on-delete-annotation
        (mf/use-callback
         (mf/deps shape)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-component-annotation.title")
                       :message (tr "modals.delete-component-annotation.message")
                       :accept-label (tr "ds.confirm-ok")
                       :on-accept (fn []
                                    (st/emit!
                                     (dw/set-annotations-id-for-create nil)
                                     (dw/update-component-annotation component-id nil)))}))))]

    (mf/use-effect
     (mf/deps shape)
     (fn []
       (initialize)
       (when (and (not creating?) (:id-for-create workspace-annotations)) ;; cleanup set-annotations-id-for-create if we aren't on the marked component
         (st/emit! (dw/set-annotations-id-for-create nil)))
       (fn [] (st/emit! (dw/set-annotations-id-for-create nil))))) ;; cleanup set-annotationsid-for-create on unload

    (when (or creating? annotation)
      [:div.component-annotation {:class (dom/classnames :editing @editing? :creating creating?)}
       [:div.title {:class (dom/classnames :expandeable (not (or @editing? creating?)))
                    :on-click #(expand (not annotations-expanded?))}
        [:div (if (or @editing? creating?)
                (if @editing?
                  (tr "workspace.options.component.edit-annotation")
                  (tr "workspace.options.component.create-annotation"))
                [:* (if annotations-expanded?
                      [:div.expand i/arrow-down]
                      [:div.expand i/arrow-slide])
                 (tr "workspace.options.component.annotation")])]
        [:div
         (when (and main-instance? annotations-expanded?)
           (if (or @editing? creating?)
             [:*
              [:div.icon {:title (if creating? (tr "labels.create") (tr "labels.save"))
                          :on-click save
                          :class (dom/classnames :hidden @invalid-text?)} i/tick]
              [:div.icon {:title (tr "labels.discard")
                          :on-click discard} i/cross]]
             [:*
              [:div.icon {:title (tr "labels.edit")
                          :on-click edit} i/pencil]
              [:div.icon {:title (tr "labels.delete")
                          :on-click on-delete-annotation} i/trash]]))]]

       [:div {:class (dom/classnames :hidden (not annotations-expanded?))}
        [:div.grow-wrap
         [:div.texarea-copy]
         [:textarea
          {:ref textarea-ref
           :id "annotation-textarea"
           :data-debug annotation
           :auto-focus true
           :maxLength 300
           :on-input autogrow
           :default-value annotation
           :read-only (not (or creating? @editing?))}]]
        (when (or @editing? creating?)
          [:div.counter (str @size "/300")])]])))

(mf/defc component-menu
  [{:keys [ids values shape] :as props}]
  (let [new-css-system      (mf/use-ctx ctx/new-css-system)
        current-file-id     (mf/use-ctx ctx/current-file-id)
        components-v2       (mf/use-ctx ctx/components-v2)

        objects             (deref refs/workspace-page-objects)
        touched?            (cph/component-touched? objects (:id shape))
        can-update-main?    (or (not components-v2) touched?)

        id                  (first ids)
        state*              (mf/use-state {:show-content true
                                           :menu-open false})
        state               (deref state*)
        open?               (:show-content state)
        menu-open?          (:menu-open state)

        toggle-content
        (mf/use-fn #(swap! state* update :show-content not))

        shape-name          (:name shape)
        component-id        (:component-id values)
        library-id          (:component-file values)
        show?               (some? component-id)
        main-instance?      (if components-v2
                              (ctk/main-instance? values)
                              true)
        main-component?     (:main-instance values)
        lacks-annotation?   (nil? (:annotation values))

        local-component?    (= library-id current-file-id)

        workspace-data      (deref refs/workspace-data)
        workspace-libraries (deref refs/workspace-libraries)
        component           (if local-component?
                              (ctkl/get-component workspace-data component-id)
                              (ctf/get-component workspace-libraries library-id component-id))
        is-dangling?        (nil? component)
        lib-exists?         (and (not local-component?)
                                 (some? (get workspace-libraries library-id)))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (swap! state* update :menu-open not)))

        on-menu-close
        (mf/use-callback
         #(swap! state* assoc :menu-open false))

        do-detach-component
        #(st/emit! (dwl/detach-component id))

        do-reset-component
        #(st/emit! (dwl/reset-component id))

        do-update-component
        #(st/emit! (dwl/update-component-sync id library-id))

        do-restore-component
        #(st/emit! (dwl/restore-component library-id component-id)
                   (dw/go-to-main-instance nil component-id))

        do-update-remote-component
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
        do-show-in-assets #(st/emit! (if components-v2
                                       (dw/show-component-in-assets component-id)
                                       (dw/go-to-component component-id)))
        do-create-annotation #(st/emit! (dw/set-annotations-id-for-create id))
        do-navigate-component-file #(st/emit! (dwl/nav-to-component-file library-id))]
    (when show?
      (if new-css-system
        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:& title-bar {:collapsable? true
                         :collapsed?   (not open?)
                         :on-collapsed toggle-content
                         :title        (tr "workspace.options.component")
                         :class        (stl/css :title-spacing-component)}]]

         (when open?
           [:div {:class (stl/css :element-content)}
            [:div {:class (stl/css :component-wrapper)}
             [:div {:class (stl/css :component-name-wrapper)}
              [:span {:class (stl/css :component-icon)}
               (if main-instance?
                 i/component-refactor
                 i/copy-refactor)]

              [:div {:class (stl/css :component-name)} shape-name]]

             [:div {:class (stl/css :component-actions)}
              [:button {:class (stl/css :menu-btn)
                        :on-click on-menu-click}
               i/menu-refactor]

              [:& dropdown {:show menu-open?
                            :on-close on-menu-close}
               [:ul {:class (stl/css :custom-select-dropdown)}
                (if main-component?
                  [:*
                   [:li {:class (stl/css :dropdown-element)
                         :on-click do-show-in-assets}
                    [:span {:class (stl/css :dropdown-label)}
                     (tr "workspace.shape.menu.show-in-assets")]]
                   (when (and components-v2 local-component? lacks-annotation?)
                     [:li {:class (stl/css :dropdown-element)
                           :on-click do-create-annotation}
                      [:span {:class (stl/css :dropdown-label)}
                       (tr "workspace.shape.menu.create-annotation")]])]

                  (if local-component?
                    (if is-dangling?
                      [:*
                       [:li {:class (stl/css :dropdown-element)
                             :on-click do-detach-component}
                        [:span {:class (stl/css :dropdown-label)}
                         (tr "workspace.shape.menu.detach-instance")]]
                       (when can-update-main?
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-reset-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.reset-overrides")]])
                       (when components-v2
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-restore-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.restore-main")]])]

                      [:*
                       [:li {:class (stl/css :dropdown-element)
                             :on-click do-detach-component}
                        [:span {:class (stl/css :dropdown-label)}
                         (tr "workspace.shape.menu.detach-instance")]]
                       (when can-update-main?
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-reset-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.reset-overrides")]]
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-update-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.update-main")]])
                       [:li {:class (stl/css :dropdown-element)
                             :on-click do-show-component}
                        [:span {:class (stl/css :dropdown-label)}
                         (tr "workspace.shape.menu.show-main")]]])
                    (if is-dangling?
                      [:*
                       [:li {:class (stl/css :dropdown-element)
                             :on-click do-detach-component}
                        [:span {:class (stl/css :dropdown-label)}
                         (tr "workspace.shape.menu.detach-instance")]]
                       (when can-update-main?
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-reset-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.reset-overrides")]])

                       (when (and components-v2 lib-exists?)
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-restore-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.restore-main")]])]
                      [:*
                       [:li {:class (stl/css :dropdown-element)
                             :on-click do-detach-component}
                        [:span {:class (stl/css :dropdown-label)}
                         (tr "workspace.shape.menu.detach-instance")]]
                       (when can-update-main?
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-reset-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.reset-overrides")]]
                         [:li {:class (stl/css :dropdown-element)
                               :on-click do-update-remote-component}
                          [:span {:class (stl/css :dropdown-label)}
                           (tr "workspace.shape.menu.update-main")]])
                       [:li {:class (stl/css :dropdown-element)
                             :on-click do-navigate-component-file}
                        [:span {:class (stl/css :dropdown-label)}
                         (tr "workspace.shape.menu.go-main")]]])))]]]]
            (when components-v2
              [:& component-annotation {:id id :values values :shape shape :component component}])])]

        [:div.element-set
         [:div.element-set-title
          [:span (tr "workspace.options.component")]
          [:span (if main-instance?
                   (tr "workspace.options.component.main")
                   (tr "workspace.options.component.copy"))]]
         [:div.element-set-content
          [:div.row-flex.component-row
           (if main-instance?
             i/component
             i/component-copy)
           [:div.component-name shape-name]
           [:div.row-actions
            {:on-click on-menu-click}
            i/actions
                  ;; WARNING: this menu is the same as the shape context menu.
                  ;;          If you change it, you must change equally the file
                  ;;          app/main/ui/workspace/context_menu.cljs
            [:& context-menu {:on-close on-menu-close
                              :show menu-open?
                              :options (if main-component?
                                         [[(tr "workspace.shape.menu.show-in-assets") do-show-in-assets]
                                          (when (and components-v2 local-component? lacks-annotation?)
                                            [(tr "workspace.shape.menu.create-annotation") do-create-annotation])]
                                         (if local-component?
                                           (if is-dangling?
                                             [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                              (when can-update-main?
                                                [(tr "workspace.shape.menu.reset-overrides") do-reset-component])
                                              (when components-v2
                                                [(tr "workspace.shape.menu.restore-main") do-restore-component])]

                                             [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                              (when can-update-main?
                                                [(tr "workspace.shape.menu.reset-overrides") do-reset-component])
                                              (when can-update-main?
                                                [(tr "workspace.shape.menu.update-main") do-update-component])
                                              [(tr "workspace.shape.menu.show-main") do-show-component]])

                                           (if is-dangling?
                                             [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                              (when can-update-main?
                                                [(tr "workspace.shape.menu.reset-overrides") do-reset-component])
                                              (when (and components-v2 lib-exists?)
                                                [(tr "workspace.shape.menu.restore-main") do-restore-component])]
                                             [[(tr "workspace.shape.menu.detach-instance") do-detach-component]
                                              (when can-update-main?
                                                [(tr "workspace.shape.menu.reset-overrides") do-reset-component])
                                              (when can-update-main?
                                                [(tr "workspace.shape.menu.update-main") do-update-remote-component])
                                              [(tr "workspace.shape.menu.go-main") do-navigate-component-file]])))}]]]

          (when components-v2
            [:& component-annotation {:id id :values values :shape shape :component component}])]]))))
