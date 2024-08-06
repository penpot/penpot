;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.components
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.media :as cm]
   [app.common.types.file :as ctf]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-label :refer [editable-label]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.groups :as grp]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def drag-data* (atom {:local? false}))

(defn set-drag-data! [data]
  (reset! drag-data* data))

(defn- get-component-root-and-container
  [file-id component components-v2]
  (if (= file-id (:id @refs/workspace-file))
    (let [data @refs/workspace-data]
      [(ctf/get-component-root data component)
       (if components-v2
         (ctf/get-component-page data component)
         component)])
    (let [data (dm/get-in @refs/workspace-libraries [file-id :data])
          root-shape (ctf/get-component-root data component)
          container (if components-v2
                      (ctf/get-component-page data component)
                      component)]
      [root-shape container])))


    ;; NOTE: We don't schedule the thumbnail generation on idle right now
    ;; until we can queue and handle thumbnail batching properly.
#_(mf/with-effect []
    (when-not (some? thumbnail-uri)
      (tm/schedule-on-idle
       #(st/emit! (dwl/update-component-thumbnail (:id component) file-id)))))


(mf/defc components-item
  {::mf/wrap-props false}
  [{:keys [component renaming listing-thumbs? selected
           file-id on-asset-click on-context-menu on-drag-start do-rename
           cancel-rename selected-full selected-paths local]}]
  (let [item-ref       (mf/use-ref)

        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        components-v2  (mf/use-ctx ctx/components-v2)
        component-id   (:id component)

        visible?       (h/use-visible item-ref :once? true)

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
         (mf/deps component-id on-asset-click)
         (fn [event]
           (dom/stop-propagation event)
           (on-asset-click component-id unselect-all event)))

        on-component-double-click
        (mf/use-fn
         (mf/deps file-id component local)
         (fn [event]
           (dom/stop-propagation event)
           (if local
             (st/emit! (dw/go-to-component component-id))
             (st/emit! (dwl/nav-to-component-file file-id component)))))

        on-drop
        (mf/use-fn
         (mf/deps component dragging* selected selected-full selected-paths local drag-data*)
         (fn [event]
           (when (and local (:local? @drag-data*))
             (cmm/on-drop-asset event component dragging* selected selected-full
                                selected-paths dwl/rename-component-and-main-instance))))

        on-drag-enter
        (mf/use-fn
         (mf/deps component dragging* selected selected-paths local drag-data*)
         (fn [event]
           (when (and local (:local? @drag-data*))
             (cmm/on-drag-enter-asset event component dragging* selected selected-paths))))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging* local drag-data*)
         (fn [event]
           (when (and local (:local? @drag-data*))
             (cmm/on-drag-leave-asset event dragging*))))

        on-component-drag-start
        (mf/use-fn
         (mf/deps file-id component selected item-ref on-drag-start read-only? local)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (cmm/on-asset-drag-start event file-id component selected item-ref :components on-drag-start))))

        on-context-menu
        (mf/use-fn
         (mf/deps on-context-menu component-id)
         (partial on-context-menu component-id))

        renaming? (= renaming (:id component))]

    [:div {:ref item-ref
           :class (stl/css-case :selected (contains? selected (:id component))
                                :grid-cell listing-thumbs?
                                :enum-item (not listing-thumbs?))
           :id (dm/str "component-shape-id-" (:id component))
           :draggable (and (not read-only?) (not renaming?))
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
        [:*
         [:& editable-label
          {:class (stl/css-case :cell-name listing-thumbs?
                                :item-name (not listing-thumbs?)
                                :editing renaming?)
           :value (cfh/merge-path-item (:path component) (:name component))
           :tooltip (cfh/merge-path-item (:path component) (:name component))
           :display-value (:name component)
           :editing renaming?
           :disable-dbl-click true
           :on-change do-rename
           :on-cancel cancel-rename}]

         (when ^boolean dragging?
           [:div {:class (stl/css :dragging)}])]

        (when visible?
          [:& cmm/component-item-thumbnail {:file-id file-id
                                            :class (stl/css-case :thumbnail true
                                                                 :asset-list-thumbnail (not listing-thumbs?))
                                            :root-shape root-shape
                                            :component component
                                            :container container}])])]))

(mf/defc components-group
  {::mf/wrap-props false}
  [{:keys [file-id prefix groups open-groups force-open? renaming listing-thumbs? selected on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-group on-ungroup on-context-menu
           selected-full local]}]

  (let [group-open?    (if (false? (get open-groups prefix)) ;; if the user has closed it specifically, respect that
                         false
                         (or ^boolean force-open?
                             ^boolean (get open-groups prefix (if (= prefix "") true false))))
        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)


        selected-paths (mf/with-memo [selected-full]
                         (into #{}
                               (comp (map :path) (d/nilv ""))
                               selected-full))
        on-drag-enter
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths local drag-data*)
         (fn [event]
           (when (and local (:local? @drag-data*))
             (cmm/on-drag-enter-asset-group event dragging* prefix selected-paths))))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging* local drag-data*)
         (fn [event]
           (when (and local (:local? @drag-data*))
             (cmm/on-drag-leave-asset event dragging*))))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full local drag-data*)
         (fn [event]
           (when (and local (:local? @drag-data*))
             (cmm/on-drop-asset-group event dragging* prefix selected-paths selected-full dwl/rename-component-and-main-instance))))]

    [:div {:class (stl/css :component-group)
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
        (let [components (not-empty (get groups "" []))]
          [:div {:class-name (stl/css-case :asset-grid listing-thumbs?
                                           :asset-enum (not listing-thumbs?))
                 :on-drag-enter on-drag-enter
                 :on-drag-leave on-drag-leave
                 :on-drag-over dom/prevent-default
                 :on-drop on-drop}

           (when ^boolean dragging?
             [:div {:class (stl/css :grid-placeholder)} "\u00A0"])


           (when (and (empty? components)
                      (some? groups)
                      local)
             [:div {:class (stl/css-case :drop-space true
                                         :drop-space-small (not dragging?))}])

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
               :cancel-rename cancel-rename
               :local local}])])

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& components-group {:file-id file-id
                                  :key path-item
                                  :prefix (cfh/merge-path-item prefix path-item)
                                  :groups content
                                  :open-groups open-groups
                                  :force-open? force-open?
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
                                  :selected-full selected-full
                                  :local local}]))])]))

(mf/defc components-section
  {::mf/wrap-props false}
  [{:keys [file-id local? components listing-thumbs? open? force-open?
           reverse-sort? selected on-asset-click on-assets-delete
           on-clear-selection open-status-ref]}]

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
           (when (not (str/blank? new-name))
             (st/emit!
              (dwl/rename-component-and-main-instance current-component-id new-name)))))

        on-context-menu
        (mf/use-fn
         (mf/deps selected on-clear-selection read-only?)
         (fn [component-id event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]

             (when (not read-only?)
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
                        (map #(dwl/rename-component-and-main-instance
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
                        (map #(dwl/rename-component-and-main-instance
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
                        (map #(dwl/rename-component-and-main-instance (:id %) (cmm/ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-drag-start
        (mf/use-fn
         (mf/deps file-id)
         (fn [component event]

           (let [file-data
                 (d/nilv (dm/get-in @refs/workspace-libraries [file-id :data]) @refs/workspace-data)

                 shape-main
                 (ctf/get-component-root file-data component)]

             ;; dnd api only allow to acces to the dataTransfer data on on-drop (https://html.spec.whatwg.org/dev/dnd.html#concept-dnd-p)
             ;; We need to know if the dragged element is from the local library on on-drag-enter, so we need to keep the info elsewhere
             (set-drag-data! {:file-id file-id
                              :component component
                              :shape shape-main
                              :local? local?})

             (dnd/set-data! event "penpot/component" true)

             ;; Remove the ghost image for componentes because we're going to instantiate it on the viewport
             (dnd/set-drag-image! event (dnd/invisible-image))

             (dnd/set-allowed-effect! event "move"))))

        on-show-main
        (mf/use-fn
         (mf/deps current-component-id file-id local?)
         (fn [event]
           (dom/stop-propagation event)
           (if local?
             (st/emit! (dw/go-to-component current-component-id))
             (let [component (d/seek #(= (:id %) current-component-id) components)]
               (st/emit! (dwl/nav-to-component-file file-id component))))))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))]

    [:& cmm/asset-section {:file-id file-id
                           :title (tr "workspace.assets.components")
                           :section :components
                           :assets-count (count components)
                           :open? open?}
     [:& cmm/asset-section-block {:role :title-button}
      (when ^boolean open?
        [:div {:class (stl/css :listing-options)}
         [:& radio-buttons {:selected (if listing-thumbs? "grid" "list")
                            :on-change toggle-list-style
                            :name "listing-style"}
          [:& radio-button {:icon i/view-as-list
                            :value "list"
                            :title (tr "workspace.assets.list-view")
                            :id "opt-list"}]
          [:& radio-button {:icon i/flex-grid
                            :value "grid"
                            :title (tr "workspace.assets.grid-view")
                            :id "opt-grid"}]]])

      (when (and components-v2 (not read-only?) local?)
        [:div {:on-click add-component
               :class (stl/css :add-component)}
         i/add
         [:& file-uploader {:accept cm/str-image-types
                            :multi true
                            :ref input-ref
                            :on-selected on-file-selected}]])]

     [:& cmm/asset-section-block {:role :content}
      (when ^boolean open?
        [:& components-group {:file-id file-id
                              :prefix ""
                              :groups groups
                              :open-groups open-groups
                              :force-open? force-open?
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
                              :selected-full selected-full
                              :local ^boolean local?}])

      [:& cmm/assets-context-menu
       {:on-close on-close-menu
        :state @menu-state
        :options [(when (and local? (not (or multi-components? multi-assets? read-only?)))
                    {:option-name    (tr "workspace.assets.rename")
                     :id             "assets-rename-component"
                     :option-handler on-rename})
                  (when (and local? (not (or multi-assets? read-only?)))
                    {:option-name    (if components-v2
                                       (tr "workspace.assets.duplicate-main")
                                       (tr "workspace.assets.duplicate"))
                     :id             "assets-duplicate-component"
                     :option-handler on-duplicate})

                  (when (and local? (not read-only?))
                    {:option-name    (tr "workspace.assets.delete")
                     :id             "assets-delete-component"
                     :option-handler on-delete})
                  (when (and local? (not (or multi-assets? read-only?)))
                    {:option-name   (tr "workspace.assets.group")
                     :id             "assets-group-component"
                     :option-handler on-group})

                  (when (and components-v2 (not multi-assets?))
                    {:option-name   (tr "workspace.shape.menu.show-main")
                     :id             "assets-show-main-component"
                     :option-handler on-show-main})]}]]]))
