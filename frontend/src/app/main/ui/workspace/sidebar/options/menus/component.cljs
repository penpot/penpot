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

(mf/defc component-annotation
  [{:keys [id shape component] :as props}]
  (let [main-instance?        (:main-instance shape)
        component-id          (:component-id shape)
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
  (let [single?             (= 1 (count shapes))
        shape               (first shapes)
        new-css-system      (mf/use-ctx ctx/new-css-system)
        current-file-id     (mf/use-ctx ctx/current-file-id)
        workspace-file      (deref refs/workspace-file)
        workspace-data      (deref refs/workspace-data)
        workspace-libraries (deref refs/workspace-libraries)
        objects             (deref refs/workspace-page-objects)
        libraries           (assoc workspace-libraries current-file-id (assoc workspace-file :data workspace-data))
        every-same-file?    (every? #(= (:component-file shape) (:component-file %)) shapes)
        current-comp-id     (when (every? #(= (:component-id shape) (:component-id %)) shapes)
                              (:component-id shape))

        file-id             (if every-same-file?
                              (:component-file shape)
                              current-file-id)
        paths                (->> shapes
                                  (map :name)
                                  (map cph/split-path)
                                  (map butlast))

        find-common-path    (fn common-path [path n]
                              (let [current (nth (first paths) n nil)]
                                (if (or (nil? current)
                                        (not (every? #(= current (nth % n nil)) paths)))
                                  path
                                  (common-path (conj path current) (inc n)))))

        path                (if single?
                              (cph/butlast-path (:name shape))
                              (cph/join-path (if (not every-same-file?)
                                               ""
                                               (find-common-path [] 0))))

        filters*            (mf/use-state
                             {:term ""
                              :file-id file-id
                              :path path})
        filters             (deref filters*)

        components          (-> (get-in libraries [(:file-id filters) :data :components])
                                vals)

        components          (if (str/empty? (:term filters))
                              components
                              (filter #(str/includes? (str/lower (:name %)) (str/lower (:term filters))) components))

        get-subgroups (fn [path]
                        (let [split-path (cph/split-path path)]
                          (reduce (fn [acc dir]
                                    (conj acc (str (last acc) " / " dir)))
                                  [(first split-path)] (rest split-path))))

        groups    (->> components
                       (map :path)
                       (map get-subgroups)
                       (apply concat)
                       (remove str/empty?)
                       (remove nil?)
                       distinct
                       (filter #(= (cph/butlast-path %) (:path filters)))
                       sort)

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

        parent-components   (->> shapes
                                 (map :parent-id)
                                 (map #(get objects %))
                                 (map #(get-comps-ids % []))
                                 (apply concat)
                                 set)


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
                                (dwl/component-multi-swap shapes (:file-id filters) (:id item))))}
                 [:& cmm/component-item-thumbnail {:file-id (:file-id item)
                                                   :root-shape root-shape
                                                   :component item
                                                   :container container}]
                 [:span.component-name
                  {:class (stl/css-case :selected (= (:id item) current-comp-id))}
                  (:name item)]])
              [:div.component-group {:key (uuid/next) :on-click #(on-enter-group item)}
               [:span (cph/last-path item)]
               [:span i/arrow-slide]]))]]]))

(mf/defc component-ctx-menu
  [{:keys [menu-entries on-close show type] :as props}]
  (case type
    :context-menu
    [:& context-menu {:on-close on-close
                      :show show
                      :options
                      (vec (for [entry menu-entries :when (not (nil? entry))]
                             [(tr (:msg entry)) (:action entry)]))}]
    :dropdown
    [:& dropdown {:show show :on-close on-close}
     [:ul {:class (stl/css :custom-select-dropdown)}
      (for [entry menu-entries :when (not (nil? entry))]
        [:li {:key (uuid/next)
              :class (stl/css :dropdown-element)
              :on-click (:action entry)}
         [:span {:class (stl/css :dropdown-label)}
          (tr (:msg  entry))]])]]))


(mf/defc component-menu
  [{:keys [shapes swap-opened?] :as props}]
  (let [new-css-system      (mf/use-ctx ctx/new-css-system)
        current-file-id     (mf/use-ctx ctx/current-file-id)
        components-v2       (mf/use-ctx ctx/components-v2)
        workspace-data      (deref refs/workspace-data)
        workspace-libraries (deref refs/workspace-libraries)

        state*              (mf/use-state {:show-content true
                                           :menu-open false})
        state               (deref state*)
        open?               (:show-content state)
        menu-open?          (:menu-open state)

        shapes              (filter ctk/instance-head? shapes)
        multi               (> (count shapes) 1)
        copies              (filter ctk/in-component-copy? shapes)
        can-swap?           (and components-v2 (seq copies))

        ;; For when it's only one shape
        shape               (first shapes)
        id                  (:id shape)
        shape-name          (:name shape)
        component           (ctf/resolve-component shape {:id current-file-id :data workspace-data} workspace-libraries)
        main-instance?      (if components-v2 (ctk/main-instance? shape) true)

        toggle-content
        (mf/use-fn #(swap! state* update :show-content not))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (swap! state* update :menu-open not)))

        on-menu-close
        (mf/use-callback
         #(swap! state* assoc :menu-open false))

        menu-entries         (cmm/generate-components-menu-entries shapes components-v2)
        show-menu?           (seq menu-entries)]

    (when (seq shapes)
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

              [:& component-ctx-menu {:show menu-open?
                                      :on-close on-menu-close
                                      :menu-entries menu-entries
                                      :type :dropdown}]]]
            (when (and (not multi) components-v2)
              [:& component-annotation {:id id :shape shape :component component}])])]

        [:div.element-set
         [:div.element-set-title {:class (stl/css-case :back swap-opened?)
                                  :on-click #(when swap-opened? (st/emit! :interrupt))}
          [:div
           (when swap-opened?
             [:span
              i/arrow-slide])
           [:span (tr "workspace.options.component")]]
          (when-not multi
            [:span (if main-instance?
                     (tr "workspace.options.component.main")
                     (tr "workspace.options.component.copy"))])]
         [:div.element-set-content
          [:div.row-flex.component-row
           {:class (stl/css-case :copy can-swap?)
            :on-click #(when can-swap? (st/emit! (dwsp/open-specialized-panel :component-swap shapes)))}
           (if multi
             i/component-copy
             (if main-instance?
               i/component
               i/component-copy))
           [:div.component-name (if multi
                                  (tr "settings.multiple")
                                  shape-name)]
           (when show-menu?
             [:div.row-actions
              {:on-click on-menu-click}
              i/actions
              [:& component-ctx-menu {:on-close on-menu-close
                                      :show menu-open?
                                      :menu-entries menu-entries
                                      :type :context-menu}]])

           (when (and can-swap? (not multi))
             [:div.component-parent-name
              (cph/merge-path-item (:path component) (:name component))])]

          (when swap-opened?
            [:& component-swap {:shapes shapes}])

          (when (and (not swap-opened?) (not multi) components-v2)
            [:& component-annotation {:id id :shape shape :component component}])]]))))
