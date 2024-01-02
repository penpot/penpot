;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.component
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.specialized-panel :as dwsp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc component-annotation
  [{:keys [id shape component] :as props}]
  (let [main-instance?        (:main-instance shape)
        component-id          (:component-id shape)
        annotation            (:annotation component)
        editing?              (mf/use-state false)
        invalid-text?         (mf/use-state (or (nil? annotation) (str/blank? annotation)))
        size                  (mf/use-state (count annotation))
        textarea-ref          (mf/use-ref)

        ;; hack to create an autogrowing textarea
        ;; based on https://css-tricks.com/the-cleanest-trick-for-autogrowing-textareas/
        autogrow              #(let [textarea (mf/ref-val textarea-ref)
                                     text (when textarea (.-value textarea))]
                                 (reset! invalid-text? (str/blank? text))
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
                                  (st/emit! (dw/set-annotations-id-for-create nil))
                                  (autogrow)))
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

(mf/defc component-swap-item
  {::mf/wrap-props false}
  [{:keys [item loop shapes file-id root-shape container component-id is-search listing-thumbs] :as props}]
  (let [on-select-component
        (mf/use-fn
         (mf/deps shapes file-id item)
         #(when-not loop
            (st/emit! (dwl/component-multi-swap shapes file-id (:id item)))))
        item-ref       (mf/use-ref)
        visible?       (h/use-visible item-ref :once? true)]
    [:div
     {:ref item-ref
      :title (if is-search (:full-name item) (:name item))
      :class (stl/css-case :component-item (not listing-thumbs)
                           :grid-cell listing-thumbs
                           :selected (= (:id item) component-id)
                           :disabled loop)
      :key (str "swap-item-" (:id item))
      :on-click on-select-component}
     (when visible?
       [:& cmm/component-item-thumbnail {:file-id (:file-id item)
                                         :root-shape root-shape
                                         :component item
                                         :container container}])
     [:span
      {:class (stl/css-case :component-name true :selected (= (:id item) component-id))}
      (if is-search (:full-name item) (:name item))]]))

(mf/defc component-group-item
  [{:keys [item on-enter-group] :as props}]
  (let [group-name (:name item)
        path (cfh/butlast-path group-name)
        on-group-click #(on-enter-group group-name)]
    [:div {:class (stl/css :component-group)
           :key (uuid/next) :on-click on-group-click
           :title group-name}
     [:div
      (when-not (str/blank? path)
        [:span {:class (stl/css :component-group-path)} (str "\u00A0/\u00A0" path)])
      [:span {:class (stl/css :component-group-name)} (cfh/last-path group-name)]]
     [:span i/arrow-slide]]))

(mf/defc component-swap
  [{:keys [shapes] :as props}]
  (let [single?             (= 1 (count shapes))
        shape               (first shapes)
        current-file-id     (mf/use-ctx ctx/current-file-id)
        workspace-file      (mf/deref refs/workspace-file)
        workspace-data      (mf/deref refs/workspace-data)
        workspace-libraries (mf/deref refs/workspace-libraries)
        objects             (mf/deref refs/workspace-page-objects)
        libraries           (assoc workspace-libraries current-file-id (assoc workspace-file :data workspace-data))
        single-comp         (ctf/get-component libraries (:component-file shape) (:component-id shape))
        every-same-file?    (every? #(= (:component-file shape) (:component-file %)) shapes)
        current-comp-id     (when (every? #(= (:component-id shape) (:component-id %)) shapes)
                              (:component-id shape))

        file-id             (if every-same-file?
                              (:component-file shape)
                              current-file-id)
        orig-components     (map #(ctf/get-component libraries (:component-file %) (:component-id %)) shapes)
        paths                (->> orig-components
                                  (map :path)
                                  (map cfh/split-path))

        find-common-path    (fn common-path [path n]
                              (let [current (nth (first paths) n nil)]
                                (if (or (nil? current)
                                        (not (every? #(= current (nth % n nil)) paths)))
                                  path
                                  (common-path (conj path current) (inc n)))))

        path                (if single?
                              (:path single-comp)
                              (cfh/join-path (if (not every-same-file?)
                                               ""
                                               (find-common-path [] 0))))

        filters*            (mf/use-state
                             {:term ""
                              :file-id file-id
                              :path path
                              :listing-thumbs? false})

        filters             (deref filters*)
        is-search?          (not (str/blank? (:term filters)))
        current-library-id    (if (contains? libraries (:file-id filters))
                                (:file-id filters)
                                current-file-id)

        current-library-name  (if (= current-library-id current-file-id)
                                (str/upper (tr "workspace.assets.local-library"))
                                (get-in libraries [current-library-id :name]))

        components          (->> (get-in libraries [current-library-id :data :components])
                                 vals
                                 (remove #(true? (:deleted %)))
                                 (map #(assoc % :full-name (cfh/merge-path-item (:path %) (:name %)))))

        get-subgroups       (fn [path]
                              (let [split-path (cfh/split-path path)]
                                (reduce (fn [acc dir]
                                          (conj acc (str (last acc) " / " dir)))
                                        [(first split-path)] (rest split-path))))

        xform               (comp
                             (map :path)
                             (mapcat get-subgroups)
                             (remove str/empty?)
                             (remove nil?)
                             (distinct)
                             (filter #(= (cfh/butlast-path %) (:path filters))))

        groups              (when-not is-search?
                              (->> (sort (sequence xform components))
                                   (map #(assoc {} :name %))))

        components          (if is-search?
                              (filter #(str/includes? (str/lower (:full-name %)) (str/lower (:term filters))) components)
                              (filter #(= (:path %) (:path filters)) components))

        items               (if (or is-search? (:listing-thumbs? filters))
                              (sort-by :full-name components)
                              (->> (concat groups components)
                                   (sort-by :name)))

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
                                 (mapcat #(get-comps-ids % []))
                                 set)

        libraries-options  (map (fn [library] {:value (:id library) :label (:name library)}) (vals libraries))

        on-library-change
        (mf/use-fn
         (fn [id]
           (swap! filters* assoc :file-id id :term "" :path "")))

        on-search-term-change
        (mf/use-fn
         (fn [term]
           (swap! filters* assoc :term term)))


        on-search-clear-click
        (mf/use-fn #(swap! filters* assoc :term ""))

        on-go-back
        (mf/use-fn
         (mf/deps (:path filters))
         #(swap! filters* assoc :path (cfh/butlast-path (:path filters))))

        on-enter-group
        (mf/use-fn #(swap! filters* assoc :path %))

        toggle-list-style
        (mf/use-fn
         (fn [style]
           (swap! filters* assoc :listing-thumbs? (= style "grid"))))]

       [:div {:class (stl/css :component-swap)}
        [:div {:class (stl/css :element-set-title)}
         [:span (tr "workspace.options.component.swap")]]
        [:div {:class (stl/css :component-swap-content)}
         [:div {:class (stl/css :search-field)}
          [:& search-bar {:on-change on-search-term-change
                          :clear-action on-search-clear-click
                          :value (:term filters)
                          :placeholder (str (tr "labels.search") " " (get-in libraries [current-library-id :name]))
                          :icon (mf/html [:span {:class (stl/css :search-icon)} i/search-refactor])}]]

         [:div {:class (stl/css :select-field)}
          [:& select
           {:class (stl/css :select-library)
            :default-value current-library-id
            :options libraries-options
            :on-change on-library-change}]]

         [:div {:class (stl/css :library-name)} current-library-name]

         [:div {:class (stl/css :listing-options-wrapper)}
          [:& radio-buttons {:class (stl/css :listing-options)
                             :selected (if (:listing-thumbs? filters) "grid" "list")
                             :on-change toggle-list-style
                             :name "swap-listing-style"}
           [:& radio-button {:icon i/view-as-list-refactor
                             :value "list"
                             :id "swap-opt-list"}]
           [:& radio-button {:icon i/flex-grid-refactor
                             :value "grid"
                             :id "swap-opt-grid"}]]]


         (if (or is-search? (str/empty? (:path filters)))
           [:div {:class (stl/css :component-path-empty)}]
           [:button {:class (stl/css :component-path)
                     :on-click on-go-back
                     :title (:path filters)}
            [:span i/arrow-slide]
            [:span (:path filters)]])

         (when (empty? items)
           [:div {:class (stl/css :component-list-empty)}
            (tr "workspace.options.component.swap.empty")])

         (when (:listing-thumbs? filters)
           [:div {:class (stl/css :component-list)}
            (for [item groups]
              [:& component-group-item {:item item :on-enter-group on-enter-group}])])

         [:div {:class (stl/css-case :component-grid (:listing-thumbs? filters)
                                     :component-list (not (:listing-thumbs? filters)))}
          (for [item items]
            (if (:id item)
              (let [data       (get-in libraries [current-library-id :data])
                    container  (ctf/get-component-page data item)
                    root-shape (ctf/get-component-root data item)
                    loop?      (or (contains? parent-components (:main-instance-id item))
                                   (contains? parent-components (:id item)))]
                [:& component-swap-item {:item item
                                         :loop loop?
                                         :shapes shapes
                                         :file-id current-library-id
                                         :root-shape root-shape
                                         :container container
                                         :component-id current-comp-id
                                         :is-search is-search?
                                         :listing-thumbs (:listing-thumbs? filters)}])
              [:& component-group-item {:item item :on-enter-group on-enter-group}]))]]]))

(mf/defc component-ctx-menu
  [{:keys [menu-entries on-close show] :as props}]
  (let [do-action
        (fn [action event]
          (dom/stop-propagation event)
          (action)
          (on-close))]
  [:& dropdown {:show show :on-close on-close}
   [:ul {:class (stl/css :custom-select-dropdown)}
    (for [entry menu-entries :when (not (nil? entry))]
      [:li {:key (uuid/next)
            :class (stl/css :dropdown-element)
            :on-click (partial do-action (:action entry))}
       [:span {:class (stl/css :dropdown-label)}
        (tr (:msg  entry))]])]]))

(mf/defc component-menu
  [{:keys [shapes swap-opened?] :as props}]
  (let [current-file-id     (mf/use-ctx ctx/current-file-id)
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
        component           (ctf/resolve-component shape {:id current-file-id :data workspace-data} workspace-libraries {:include-deleted? true})
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
        (mf/use-fn
         #(swap! state* assoc :menu-open false))

        on-component-back
        (mf/use-fn
         #(st/emit! ::dwsp/interrupt))

        open-component-panel
        (mf/use-fn
         (mf/deps can-swap? shapes)
         #(when can-swap? (st/emit! (dwsp/open-specialized-panel :component-swap))))

        menu-entries         (cmm/generate-components-menu-entries shapes components-v2)
        show-menu?           (seq menu-entries)]

    (when (seq shapes)
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        (if swap-opened?
          [:button {:class (stl/css :title-back)
                    :on-click on-component-back}
           [:span i/arrow-slide]
           [:span (tr "workspace.options.component")]]
          [:& title-bar {:collapsable? true
                         :collapsed?   (not open?)
                         :on-collapsed toggle-content
                         :title        (tr "workspace.options.component")
                         :class        (stl/css :title-spacing-component)}])]

       (when open?
         [:div {:class (stl/css :element-content)}
          [:div {:class (stl/css :component-wrapper)}
           [:div {:class (stl/css-case :component-name-wrapper true :with-main (and can-swap? (not multi)) :swappeable (and can-swap? (not swap-opened?)))
                  :on-click open-component-panel}
            [:span {:class (stl/css :component-icon)}
             (if main-instance?
               i/component-refactor
               i/copy-refactor)]

            [:div {:class (stl/css :component-name)} (if multi
                                                       (tr "settings.multiple")
                                                       shape-name)]
            (when show-menu?
              [:div {:class (stl/css :component-actions)}
               [:button {:class (stl/css :menu-btn)
                         :on-click on-menu-click}
                i/menu-refactor]

               [:& component-ctx-menu {:show menu-open?
                                       :on-close on-menu-close
                                       :menu-entries menu-entries}]])
            (when (and can-swap? (not multi))
              [:div {:class (stl/css :component-parent-name)}
               (cfh/merge-path-item (:path component) (:name component))])]]
          (when swap-opened?
            [:& component-swap {:shapes copies}])

          (when (and (not swap-opened?) (not multi) components-v2)
            [:& component-annotation {:id id :shape shape :component component}])])])))
