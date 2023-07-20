;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.components
  (:require-macros [app.main.style :as stl :refer [css]])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.media :as cm]
   [app.common.pages.helpers :as cph]
   [app.common.types.file :as ctf]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.editable-label :refer [editable-label]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.groups :as grp]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(defn- get-component-root-and-container
  [file-id component components-v2]
  (if (= file-id (:id @refs/workspace-file))
    (let [data @refs/workspace-data]
      [(ctf/get-component-root data component)
       (if components-v2
         (ctf/get-component-page data component)
         component)])
    (let [data (dm/get-in @refs/workspace-libraries [file-id :data])]
      [(ctf/get-component-root data component)
       (if components-v2
         (ctf/get-component-page data component)
         component)])))

(mf/defc components-item
  {::mf/wrap-props false}
  [{:keys [component renaming listing-thumbs? selected
           file-id on-asset-click on-context-menu on-drag-start do-rename
           cancel-rename selected-full selected-paths]}]
  (let [item-ref       (mf/use-ref)

        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        components-v2  (mf/use-ctx ctx/components-v2)
        new-css-system (mf/use-ctx ctx/new-css-system)
        component-id   (:id component)

        ;; NOTE: we don't use reactive deref for it because we don't
        ;; really need rerender on any change on the file change. If
        ;; the component changes, it will trigger rerender anyway.
        [root-shape container]
        (get-component-root-and-container file-id component components-v2)

        unselect-all
        (mf/use-fn
         (fn []
           (st/emit! (dw/unselect-all-assets))))

        on-component-click
        (mf/use-fn
         (mf/deps component selected)
         (fn [event]
           (dom/stop-propagation event)
           (on-asset-click component-id unselect-all event)))

        on-component-double-click
        (mf/use-fn
         (mf/deps file-id component-id)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dw/go-to-main-instance file-id component-id))))

        on-drop
        (mf/use-fn
         (mf/deps component dragging* selected selected-full selected-paths)
         (fn [event]
           (cmm/on-drop-asset event component dragging* selected selected-full
                              selected-paths dwl/rename-component)))

        on-drag-enter
        (mf/use-fn
         (mf/deps component dragging* selected selected-paths)
         (fn [event]
           (cmm/on-drag-enter-asset event component dragging* selected selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (cmm/on-drag-leave-asset event dragging*)))

        on-component-drag-start
        (mf/use-fn
         (mf/deps file-id component selected item-ref on-drag-start read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (cmm/on-asset-drag-start event file-id component selected item-ref :components on-drag-start))))

        on-context-menu
        (mf/use-fn
         (mf/deps component-id)
         (partial on-context-menu component-id))]

    (if ^boolean new-css-system
      [:div {:ref item-ref
             :class (dom/classnames
                     (css :selected) (contains? selected (:id component))
                     (css :grid-cell) listing-thumbs?
                     (css :enum-item) (not listing-thumbs?))
             :id (dm/str "component-shape-id-" (:id component))
             :draggable (not read-only?)
             :on-click on-component-click
             :on-double-click on-component-double-click
             :on-context-menu on-context-menu
             :on-drag-start on-component-drag-start
             :on-drag-enter on-drag-enter
             :on-drag-leave on-drag-leave
             :on-drag-over dom/prevent-default
             :on-drop on-drop}
       (when (and (some? root-shape)
                  (some? container))
         [:*
          [:& component-svg {:root-shape root-shape
                             :objects (:objects container)}]
          (let [renaming? (= renaming (:id component))]
            [:*
             [:& editable-label
              {:class (dom/classnames
                       (css :cell-name) listing-thumbs?
                       (css :item-name) (not listing-thumbs?)
                       (css :editing) renaming?)
               :value (cph/merge-path-item (:path component) (:name component))
               :tooltip (cph/merge-path-item (:path component) (:name component))
               :display-value (:name component)
               :editing renaming?
               :disable-dbl-click true
               :on-change do-rename
               :on-cancel cancel-rename}]

             (when ^boolean dragging?
               [:div {:class (dom/classnames (css :dragging) true)}])])])]

      [:div {:ref item-ref
             :class (dom/classnames
                     :selected (contains? selected (:id component))
                     :grid-cell listing-thumbs?
                     :enum-item (not listing-thumbs?))
             :id (dm/str "component-shape-id-" (:id component))
             :draggable (not read-only?)
             :on-click on-component-click
             :on-double-click on-component-double-click
             :on-context-menu on-context-menu
             :on-drag-start on-component-drag-start
             :on-drag-enter on-drag-enter
             :on-drag-leave on-drag-leave
             :on-drag-over dom/prevent-default
             :on-drop on-drop}

       (when (and (some? root-shape)
                  (some? container))
         [:*
          [:& component-svg {:root-shape root-shape
                             :objects (:objects container)}]
          (let [renaming? (= renaming (:id component))]
            [:*
             [:& editable-label
              {:class (dom/classnames
                       :cell-name listing-thumbs?
                       :item-name (not listing-thumbs?)
                       :editing renaming?)
               :value (cph/merge-path-item (:path component) (:name component))
               :tooltip (cph/merge-path-item (:path component) (:name component))
               :display-value (:name component)
               :editing renaming?
               :disable-dbl-click true
               :on-change do-rename
               :on-cancel cancel-rename}]

             (when ^boolean dragging?
               [:div.dragging])])])])))

(mf/defc components-group
  {::mf/wrap-props false}
  [{:keys [file-id prefix groups open-groups renaming listing-thumbs? selected on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-group on-ungroup on-context-menu
           selected-full]}]

  (let [group-open?    (get open-groups prefix true)
        new-css-system (mf/use-ctx ctx/new-css-system)
        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        selected-paths (mf/with-memo [selected-full]
                         (into #{}
                               (comp (map :path) (d/nilv ""))
                               selected-full))
        on-drag-enter
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths)
         (fn [event]
           (cmm/on-drag-enter-asset-group event dragging* prefix selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (cmm/on-drag-leave-asset event dragging*)))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full)
         (fn [event]
           (cmm/on-drop-asset-group event dragging* prefix selected-paths selected-full dwl/rename-component)))]
    (if ^boolean new-css-system
      [:div {:class (dom/classnames (css :component-group) true)
             :on-drag-enter on-drag-enter
             :on-drag-leave on-drag-leave
             :on-drag-over dom/prevent-default
             :on-drop on-drop}
       [:& grp/asset-group-title
        {:file-id file-id
         :section :components
         :path prefix
         :group-open? group-open?
         :on-rename on-rename-group
         :on-ungroup on-ungroup}]


       (when group-open?
         [:*
          (let [components (get groups "" [])]
            [:div {:class-name (dom/classnames
                                (css :asset-grid) listing-thumbs?
                                (css :asset-enum) (not listing-thumbs?)
                                (css :drop-space) (and
                                                   (empty? components)
                                                   (some? groups)
                                                   (not dragging?)))
                   :on-drag-enter on-drag-enter
                   :on-drag-leave on-drag-leave
                   :on-drag-over dom/prevent-default
                   :on-drop on-drop}

             (when ^boolean dragging?
               [:div {:class (dom/classnames (css :grid-placeholder) true)} "\u00A0"])


             (when (and (empty? components)
                        (some? groups))
               [:div {:class (dom/classnames (css :drop-space) true)}])

             (for [component components]
               [:& components-item
                {:component component
                 :key (dm/str "component-" (:id component))
                 :renaming renaming
                 :listing-thumbs? listing-thumbs?
                 :file-id file-id
                 :selected selected
                 :selected-full selected-full
                 :selected-paths selected-paths
                 :on-asset-click on-asset-click
                 :on-context-menu on-context-menu
                 :on-drag-start on-drag-start
                 :on-group on-group
                 :do-rename do-rename
                 :cancel-rename cancel-rename}])])

          (for [[path-item content] groups]
            (when-not (empty? path-item)
              [:& components-group {:file-id file-id
                                    :key path-item
                                    :prefix (cph/merge-path-item prefix path-item)
                                    :groups content
                                    :open-groups open-groups
                                    :renaming renaming
                                    :listing-thumbs? listing-thumbs?
                                    :selected selected
                                    :on-asset-click on-asset-click
                                    :on-drag-start on-drag-start
                                    :do-rename do-rename
                                    :cancel-rename cancel-rename
                                    :on-rename-group on-rename-group
                                    :on-ungroup on-ungroup
                                    :on-context-menu on-context-menu
                                    :selected-full selected-full}]))])]


      [:div {:on-drag-enter on-drag-enter
             :on-drag-leave on-drag-leave
             :on-drag-over dom/prevent-default
             :on-drop on-drop}

       [:& grp/asset-group-title
        {:file-id file-id
         :section :components
         :path prefix
         :group-open? group-open?
         :on-rename on-rename-group
         :on-ungroup on-ungroup}]

       (when group-open?
         [:*
          (let [components (get groups "" [])]
            [:div {:class-name (dom/classnames
                                :asset-grid listing-thumbs?
                                :big listing-thumbs?
                                :asset-enum (not listing-thumbs?)
                                :drop-space (and
                                             (empty? components)
                                             (some? groups)
                                             (not dragging?)))
                   :on-drag-enter on-drag-enter
                   :on-drag-leave on-drag-leave
                   :on-drag-over dom/prevent-default
                   :on-drop on-drop}

             (when ^boolean dragging?
               [:div.grid-placeholder "\u00A0"])

             (when (and (empty? components)
                        (some? groups))
               [:div.drop-space])

             (for [component components]
               [:& components-item
                {:component component
                 :key (dm/str "component-" (:id component))
                 :renaming renaming
                 :listing-thumbs? listing-thumbs?
                 :file-id file-id
                 :selected selected
                 :selected-full selected-full
                 :selected-paths selected-paths
                 :on-asset-click on-asset-click
                 :on-context-menu on-context-menu
                 :on-drag-start on-drag-start
                 :on-group on-group
                 :do-rename do-rename
                 :cancel-rename cancel-rename}])])

          (for [[path-item content] groups]
            (when-not (empty? path-item)
              [:& components-group {:file-id file-id
                                    :key path-item
                                    :prefix (cph/merge-path-item prefix path-item)
                                    :groups content
                                    :open-groups open-groups
                                    :renaming renaming
                                    :listing-thumbs? listing-thumbs?
                                    :selected selected
                                    :on-asset-click on-asset-click
                                    :on-drag-start on-drag-start
                                    :do-rename do-rename
                                    :cancel-rename cancel-rename
                                    :on-rename-group on-rename-group
                                    :on-ungroup on-ungroup
                                    :on-context-menu on-context-menu
                                    :selected-full selected-full}]))])])))

(mf/defc components-section
  {::mf/wrap-props false}
  [{:keys [file-id local? components listing-thumbs? open? reverse-sort? selected
           on-asset-click on-assets-delete on-clear-selection open-status-ref]}]

  (let [input-ref                (mf/use-ref nil)

        state*                   (mf/use-state {})
        state                    (deref state*)

        current-component-id     (:component-id state)
        renaming?                (:renaming state)

        open-groups-ref          (mf/with-memo [open-status-ref]
                                   (-> (l/in [:groups :components])
                                       (l/derived open-status-ref)))

        open-groups              (mf/deref open-groups-ref)

        menu-state               (mf/use-state cmm/initial-context-menu-state)
        read-only?               (mf/use-ctx ctx/workspace-read-only?)
        components-v2            (mf/use-ctx ctx/components-v2)
        new-css-system           (mf/use-ctx ctx/new-css-system)
        toggle-list-style        (mf/use-ctx cmm/assets-toggle-list-style)

        selected                 (:components selected)
        selected-full            (into #{} (filter #(contains? selected (:id %))) components)
        multi-components?        (> (count selected) 1)
        multi-assets?            (or (seq (:graphics selected))
                                     (seq (:colors selected))
                                     (seq (:typographies selected)))

        groups                   (mf/with-memo [components reverse-sort?]
                                   (grp/group-assets components reverse-sort?))

        add-component
        (mf/use-fn
         (fn []
           (st/emit! (dw/set-assets-section-open file-id :components true))
           (dom/click (mf/ref-val input-ref))))

        on-file-selected
        (mf/use-fn
         (mf/deps file-id)
         (fn [blobs]
           (let [params {:file-id file-id
                         :blobs (seq blobs)}]
             (st/emit! (dwm/upload-media-components params)
                       (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                              :asset-type "components"})))))

        on-duplicate
        (mf/use-fn
         (mf/deps current-component-id selected)
         (fn []
           (if (empty? selected)
             (st/emit! (dwl/duplicate-component file-id current-component-id))
             (let [undo-id (js/Symbol)]
               (st/emit! (dwu/start-undo-transaction undo-id))
               (run! st/emit! (map (partial dwl/duplicate-component file-id) selected))
               (st/emit! (dwu/commit-undo-transaction undo-id))))))

        on-delete
        (mf/use-fn
         (mf/deps current-component-id file-id multi-components? multi-assets? on-assets-delete)
         (fn []
           (let [undo-id (js/Symbol)]
             (if (or multi-components? multi-assets?)
               (on-assets-delete)
               (st/emit! (dwu/start-undo-transaction undo-id)
                         (dwl/delete-component {:id current-component-id})
                         (dwl/sync-file file-id file-id :components current-component-id)
                         (dwu/commit-undo-transaction undo-id))))))

        on-close-menu
        (mf/use-fn #(swap! menu-state cmm/close-context-menu))

        on-rename
        (mf/use-fn #(swap! state* assoc :renaming true))

        cancel-rename
        (mf/use-fn #(swap! state* dissoc :renaming))

        do-rename
        (mf/use-fn
         (mf/deps current-component-id)
         (fn [new-name]
           (swap! state* dissoc :renaming)
           (st/emit!
            (dwl/rename-component-and-main-instance current-component-id new-name))))

        on-context-menu
        (mf/use-fn
         (mf/deps selected on-clear-selection read-only?)
         (fn [component-id event]
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]
             (when (and local? (not read-only?))
               (when-not (contains? selected component-id)
                 (on-clear-selection))

               (swap! state* assoc :component-id component-id)
               (swap! menu-state cmm/open-context-menu pos)))))

        create-group
        (mf/use-fn
         (mf/deps current-component-id components selected on-clear-selection)
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> components
                        (filter #(if multi-components?
                                   (contains? selected (:id %))
                                   (= current-component-id (:id %))))
                        (map #(dwl/rename-component
                               (:id %)
                               (cmm/add-group % group-name)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        rename-group
        (mf/use-fn
         (mf/deps components)
         (fn [path last-path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> components
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-component
                               (:id %)
                               (cmm/rename-group % path last-path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps components selected create-group)
         (fn [event]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:accept create-group})))

        on-rename-group
        (mf/use-fn
         (mf/deps components)
         (fn [event path last-path]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:path path
                                            :last-path last-path
                                            :accept rename-group})))

        on-ungroup
        (mf/use-fn
         (mf/deps components)
         (fn [path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> components
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-component (:id %) (cmm/ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-drag-start
        (mf/use-fn
         (mf/deps file-id)
         (fn [component event]
           (dnd/set-data! event "penpot/component" {:file-id file-id
                                                    :component component})
           (dnd/set-allowed-effect! event "move")))

        on-show-main
        (mf/use-fn
         (mf/deps current-component-id file-id)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dw/go-to-main-instance file-id current-component-id))))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))]

    [:& cmm/asset-section {:file-id file-id
                           :title (tr "workspace.assets.components")
                           :section :components
                           :assets-count (count components)
                           :open? open?}
     (if ^boolean new-css-system
       [:& cmm/asset-section-block {:role :title-button}
        [:*
         (when open?
           [:div {:class (stl/css :listing-options)}
            [:& radio-buttons {:selected (if listing-thumbs? "grid" "list")
                               :on-change toggle-list-style
                               :name "listing-style"}
             [:& radio-button {:icon i/view-as-list-refactor
                               :value "list"
                               :id :list}]
             [:& radio-button {:icon i/flex-grid-refactor
                               :value "grid"
                               :id :grid}]]])

         (when (and components-v2 (not read-only?) local?)
           [:div {:on-click add-component
                  :class (dom/classnames (css :add-component) true)}
            i/add-refactor
            [:& file-uploader {:accept cm/str-image-types
                               :multi true
                               :ref input-ref
                               :on-selected on-file-selected}]])]]
       (when local?
         [:& cmm/asset-section-block {:role :title-button}
          (when (and components-v2 (not read-only?))
            [:div.assets-button {:on-click add-component}
             i/plus
             [:& file-uploader {:accept cm/str-image-types
                                :multi true
                                :ref input-ref
                                :on-selected on-file-selected}]])]))
     [:& cmm/asset-section-block {:role :content}
      [:& components-group {:file-id file-id
                            :prefix ""
                            :groups groups
                            :open-groups open-groups
                            :renaming (when ^boolean renaming? current-component-id)
                            :listing-thumbs? listing-thumbs?
                            :selected selected
                            :on-asset-click on-asset-click
                            :on-drag-start on-drag-start
                            :do-rename do-rename
                            :cancel-rename cancel-rename
                            :on-rename-group on-rename-group
                            :on-group on-group
                            :on-ungroup on-ungroup
                            :on-context-menu on-context-menu
                            :selected-full selected-full}]
      (when local?
        [:& cmm/assets-context-menu
         {:on-close on-close-menu
          :state @menu-state
          :options (if new-css-system
                     [(when-not  (or multi-components? multi-assets?)
                        {:option-name    (tr "workspace.assets.rename")
                         :id             "assets-rename-component"
                         :option-handler on-rename})
                      (when-not multi-assets?
                        {:option-name    (if components-v2
                                           (tr "workspace.assets.duplicate-main")
                                           (tr "workspace.assets.duplicate"))
                         :id             "assets-duplicate-component"
                         :option-handler on-duplicate})

                      {:option-name    (tr "workspace.assets.delete")
                       :id             "assets-delete-component"
                       :option-handler on-delete}
                      (when-not multi-assets?
                        {:option-name   (tr "workspace.assets.group")
                         :id             "assets-group-component"
                         :option-handler on-group})


                      (when (and components-v2 (not multi-assets?))
                        {:option-name   (tr "workspace.shape.menu.show-main")
                         :id             "assets-show-main-component"
                         :option-handler on-show-main})]

                     [(when-not (or multi-components? multi-assets?)
                        [(tr "workspace.assets.rename") on-rename])
                      (when-not multi-assets?
                        [(if components-v2
                           (tr "workspace.assets.duplicate-main")
                           (tr "workspace.assets.duplicate")) on-duplicate])
                      [(tr "workspace.assets.delete") on-delete]
                      (when-not multi-assets?
                        [(tr "workspace.assets.group") on-group])
                      (when (and components-v2 (not multi-assets?))
                        [(tr "workspace.shape.menu.show-main") on-show-main])])}])]]))

