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
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.specialized-panel :as dwsp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
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

(mf/defc component-swap
  [{:keys [shapes] :as props}]
  (let [shape               (first shapes)
        new-css-system      (mf/use-ctx ctx/new-css-system)
        current-file-id     (mf/use-ctx ctx/current-file-id)
        workspace-file      (deref refs/workspace-file)
        workspace-data      (deref refs/workspace-data)
        workspace-libraries (deref refs/workspace-libraries)
        objects             (deref refs/workspace-page-objects)
        libraries           (assoc workspace-libraries current-file-id (assoc workspace-file :data workspace-data))
        filters*            (mf/use-state
                             {:term ""
                              :file-id (:component-file shape)
                              :path (cph/butlast-path (:name shape))})
        filters             (deref filters*)

        components          (-> (get-in libraries [(:file-id filters) :data :components])
                                vals)

        components          (if (str/empty? (:term filters))
                              components
                              (filter #(str/includes? (str/lower (:name %)) (str/lower (:term filters))) components))

        groups              (->> (map :path components)
                                 (filter #(= (cph/butlast-path (:path %)) (:path filters)))
                                 (remove str/empty?)
                                 distinct
                                 (map #(hash-map :name %)))

        components          (filter #(= (:path %) (:path filters)) components)

        items               (->> (concat groups components)
                                 (sort-by :name))

        ;; Get the ids of the components and its root-shapes that are parents of the current shape, to avoid loops
        get-comps-ids       (fn get-comps-ids [shape ids]
                              (if (uuid/zero? (:id shape))
                                ids
                                (let [ids (if (ctk/instance-head? shape)
                                            (conj ids (:id shape) (:component-id shape))
                                            ids)]
                                  (get-comps-ids (get objects (:parent-id shape)) ids))))

        parent-components   (set (get-comps-ids (get objects (:parent-id shape)) []))

        on-library-change
        (mf/use-fn
         (fn [event]
           (let [value (or (-> (dom/get-target event)
                               (dom/get-value))
                           (as-> (dom/get-current-target event) $
                             (dom/get-attribute $ "data-test")))
                 value (uuid/uuid value)]
             (swap! filters* assoc :file-id value :term "" :path ""))))

        on-search-term-change
        (mf/use-fn
         (mf/deps new-css-system)
         (fn [event]
                  ;;  NOTE: When old-css-system is removed this function will recibe value and event
                  ;;  Let won't be necessary any more
           (let [value (if ^boolean new-css-system
                         event
                         (dom/get-target-val event))]
             (swap! filters* assoc :term value))))


        on-search-clear-click
        (mf/use-fn #(swap! filters* assoc :term ""))

        on-go-back
        (mf/use-fn
         (mf/deps (:path filters))
         #(swap! filters* assoc :path (cph/butlast-path (:path filters))))

        on-enter-group
        (mf/use-fn #(swap! filters* assoc :path %))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (dom/event->target event)]

             (when ^boolean enter? (dom/blur! node))
             (when ^boolean esc?   (dom/blur! node)))))]

       [:div.component-swap
        [:div.element-set-title
         [:span (tr "workspace.options.component.swap")]]
        [:div.component-swap-content
         [:div.search-block
          [:input.search-input
           {:placeholder (str (tr "labels.search") " " (get-in libraries [(:file-id filters) :name]))
            :type "text"
            :value (:term filters)
            :on-change on-search-term-change
            :on-key-down handle-key-down}]

          (if ^boolean (str/empty? (:term filters))
            [:div.search-icon
             i/search]
            [:div.search-icon.close
             {:on-click on-search-clear-click}
             i/close])]

         [:select.input-select {:value (:file-id filters)
                                :data-mousetrap-dont-stop true
                                :on-change on-library-change}
          (for [library (vals libraries)]
            [:option {:key (:id library) :value (:id library)} (:name library)])]

         (when-not (str/empty? (:path filters))
           [:div.component-path {:on-click on-go-back}
            [:span i/arrow-slide]
            [:span (-> (cph/split-path (:path filters))
                       last)]])
         [:div.component-list
          (for [item items]
            (if (:id item)
              (let [data       (get-in libraries [(:file-id filters) :data])
                    container  (ctf/get-component-page data item)
                    root-shape (ctf/get-component-root data item)
                    loop?      (or (contains? parent-components (:main-instance-id item))
                                   (contains? parent-components (:id item)))]
                [:div.component-item
                 {:class (stl/css-case :disabled loop?)
                  :key (:id item)
                  :on-click #(when-not loop?
                               (st/emit!
                                (dwl/component-swap shape (:file-id filters) (:id item))))}
                 [:& cmm/component-item-thumbnail {:file-id (:file-id item)
                                                   :root-shape root-shape
                                                   :component item
                                                   :container container}]
                 [:span.component-name
                  {:class (stl/css-case :selected (= (:id item) (:component-id shape)))}
                  (:name item)]])
              [:div.component-group {:key (uuid/next) :on-click #(on-enter-group (:name item))}
               [:span (:name item)]
               [:span i/arrow-slide]]))]]]))



(mf/defc component-menu
  [{:keys [shape swap-opened?] :as props}]
  (let [[ids values]        [[(:id shape)] (select-keys shape component-attrs)]
        new-css-system      (mf/use-ctx ctx/new-css-system)
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
        can-swap?           (and components-v2 (not main-instance?))
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
         [:div.element-set-title {:class (stl/css-case :back swap-opened?)
                                  :on-click #(when swap-opened? (st/emit! :interrupt))}
          [:div
           (when swap-opened?
             [:span
              i/arrow-slide])
           [:span (tr "workspace.options.component")]]
          [:span (if main-instance?
                   (tr "workspace.options.component.main")
                   (tr "workspace.options.component.copy"))]]
         [:div.element-set-content
          [:div.row-flex.component-row
           {:class (stl/css-case :copy can-swap?)
            :on-click #(when can-swap? (st/emit! (dwsp/open-specialized-panel :component-swap [shape])))}
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
                                              [(tr "workspace.shape.menu.go-main") do-navigate-component-file]])))}]]

           (when can-swap?
             [:div.component-parent-name
              (cph/merge-path-item (:path component) (:name component))])]

          (when swap-opened?
            [:& component-swap {:shapes [shape]}])

          (when (and (not swap-opened?) components-v2)
            [:& component-annotation {:id id :values values :shape shape :component component}])]]))))
