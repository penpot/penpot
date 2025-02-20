;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.graphics
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.media :as cm]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.components.editable-label :refer [editable-label]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
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

(mf/defc graphics-item
  [{:keys [object renaming listing-thumbs? selected-objects file-id
           on-asset-click on-context-menu on-drag-start do-rename cancel-rename
           selected-full selected-graphics-paths]}]
  (let [item-ref       (mf/use-ref)
        visible?       (h/use-visible item-ref :once? true)
        object-id      (:id object)

        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)

        on-drop
        (mf/use-fn
         (mf/deps object dragging* selected-objects selected-full selected-graphics-paths)
         (fn [event]
           (cmm/on-drop-asset event object dragging* selected-objects selected-full
                              selected-graphics-paths dwl/rename-media)))

        on-drag-enter
        (mf/use-fn
         (mf/deps object dragging* selected-objects selected-graphics-paths)
         (fn [event]
           (cmm/on-drag-enter-asset event object dragging* selected-objects selected-graphics-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (cmm/on-drag-leave-asset event dragging*)))

        on-grahic-drag-start
        (mf/use-fn
         (mf/deps object file-id selected-objects item-ref on-drag-start read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (cmm/on-asset-drag-start event file-id object selected-objects item-ref :graphics on-drag-start))))

        on-context-menu
        (mf/use-fn
         (mf/deps object-id)
         (partial on-context-menu object-id))

        on-asset-click
        (mf/use-fn
         (mf/deps object-id on-asset-click)
         (fn [event]
           (on-asset-click event object-id)))]

    [:div {:ref item-ref
           :class-name (stl/css-case
                        :selected (contains? selected-objects object-id)
                        :grid-cell listing-thumbs?
                        :enum-item (not listing-thumbs?))
           :draggable (not read-only?)
           :on-click on-asset-click
           :on-context-menu on-context-menu
           :on-drag-start on-grahic-drag-start
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}

     (when visible?
       [:*
        [:img {:src (when visible? (cf/resolve-file-media object true))
               :class (stl/css :graphic-image)
               :draggable false}] ;; Also need to add css pointer-events: none

        (let [renaming? (= renaming (:id object))]
          [:*
           [:& editable-label
            {:class (stl/css-case
                     :cell-name listing-thumbs?
                     :item-name (not listing-thumbs?)
                     :editing renaming?)
             :value (cfh/merge-path-item (:path object) (:name object))
             :tooltip (cfh/merge-path-item (:path object) (:name object))
             :display-value (:name object)
             :editing renaming?
             :disable-dbl-click true
             :on-change do-rename
             :on-cancel cancel-rename}]

           (when ^boolean dragging?
             [:div {:class (stl/css :dragging)}])])])]))

(mf/defc graphics-group
  [{:keys [file-id prefix groups open-groups force-open? renaming listing-thumbs? selected-objects on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-ungroup
           on-context-menu selected-full]}]
  (let [group-open?    (get open-groups prefix true)
        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        selected-paths
        (mf/with-memo [selected-full]
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
           (cmm/on-drop-asset-group event dragging* prefix selected-paths selected-full dwl/rename-media)))]
    [:div {:class (stl/css :graphics-group)
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:& grp/asset-group-title
      {:file-id file-id
       :section :graphics
       :path prefix
       :group-open? group-open?
       :on-rename on-rename-group
       :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [objects (get groups "" [])]
          [:div {:class-name (stl/css-case
                              :asset-grid listing-thumbs?
                              :asset-enum (not listing-thumbs?)
                              :drop-space (and
                                           (empty? objects)
                                           (some? groups)
                                           (not dragging?)))
                 :on-drag-enter on-drag-enter
                 :on-drag-leave on-drag-leave
                 :on-drag-over dom/prevent-default
                 :on-drop on-drop}

           (when ^boolean dragging?
             [:div {:class (stl/css :grid-placeholder)} "\u00A0"])

           (when (and (empty? objects)
                      (some? groups))
             [:div {:class (stl/css :drop-space)}])

           (for [object objects]
             [:& graphics-item
              {:key (dm/str "object-" (:id object))
               :file-id file-id
               :object object
               :renaming renaming
               :listing-thumbs? listing-thumbs?
               :selected-objects selected-objects
               :on-asset-click on-asset-click
               :on-context-menu on-context-menu
               :on-drag-start on-drag-start
               :do-rename do-rename
               :cancel-rename cancel-rename
               :selected-full selected-full
               :selected-paths selected-paths}])])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& graphics-group {:file-id file-id
                                :key path-item
                                :prefix (cfh/merge-path-item prefix path-item)
                                :groups content
                                :open-groups open-groups
                                :force-open? force-open?
                                :renaming renaming
                                :listing-thumbs? listing-thumbs?
                                :selected-objects selected-objects
                                :on-asset-click on-asset-click
                                :on-drag-start on-drag-start
                                :do-rename do-rename
                                :cancel-rename cancel-rename
                                :on-rename-group on-rename-group
                                :on-ungroup on-ungroup
                                :on-context-menu on-context-menu
                                :selected-full selected-full
                                :selected-paths selected-paths}]))])]))

(mf/defc graphics-section
  {::mf/wrap-props false}
  [{:keys [file-id project-id local? objects listing-thumbs? open? force-open? open-status-ref selected reverse-sort?
           on-asset-click on-assets-delete on-clear-selection]}]
  (let [input-ref         (mf/use-ref nil)
        state             (mf/use-state {:renaming nil :object-id nil})

        menu-state        (mf/use-state cmm/initial-context-menu-state)
        read-only?        (mf/use-ctx ctx/workspace-read-only?)

        open-groups-ref   (mf/with-memo [open-status-ref]
                            (-> (l/in [:groups :graphics])
                                (l/derived open-status-ref)))
        open-groups       (mf/deref open-groups-ref)

        selected          (:graphics selected)
        selected-full     (into #{} (filter #(contains? selected (:id %))) objects)
        multi-objects?    (> (count selected) 1)
        multi-assets?     (or (seq (:components selected))
                              (seq (:colors selected))
                              (seq (:typographies selected)))

        objects           (mf/with-memo [objects]
                            (mapv dwl/extract-path-if-missing objects))

        groups            (mf/with-memo [objects reverse-sort?]
                            (grp/group-assets objects reverse-sort?))

        components-v2     (mf/use-ctx ctx/components-v2)
        team-id           (mf/use-ctx ctx/current-team-id)

        add-graphic
        (mf/use-fn
         (fn []
           (st/emit! (dw/set-assets-section-open file-id :graphics true))
           (dom/click (mf/ref-val input-ref))))

        on-file-selected
        (mf/use-fn
         (mf/deps file-id project-id team-id)
         (fn [blobs]
           (let [params {:file-id file-id
                         :blobs (seq blobs)}]
             (st/emit! (dwm/upload-media-asset params)
                       (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                              :asset-type "graphics"
                                              :file-id file-id
                                              :project-id project-id
                                              :team-id team-id})))))
        on-delete
        (mf/use-fn
         (mf/deps @state multi-objects? multi-assets?)
         (fn []
           (if (or multi-objects? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwl/delete-media {:id (:object-id @state)})))))

        on-rename
        (mf/use-fn
         (fn []
           (swap! state (fn [state]
                          (assoc state :renaming (:object-id state))))))
        cancel-rename
        (mf/use-fn
         (fn []
           (swap! state assoc :renaming nil)))

        do-rename
        (mf/use-fn
         (mf/deps @state)
         (fn [new-name]
           (st/emit! (dwl/rename-media (:renaming @state) new-name))
           (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-fn
         (mf/deps selected on-clear-selection read-only?)
         (fn [object-id event]
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]
             (when (and local? (not read-only?))
               (when-not (contains? selected object-id)
                 (on-clear-selection))
               (swap! state assoc :object-id object-id)
               (swap! menu-state cmm/open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state cmm/close-context-menu)))

        create-group
        (mf/use-fn
         (mf/deps objects selected on-clear-selection (:object-id @state))
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> objects
                        (filter #(if multi-objects?
                                   (contains? selected (:id %))
                                   (= (:object-id @state) (:id %))))
                        (map #(dwl/rename-media (:id %) (cmm/add-group % group-name)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        rename-group
        (mf/use-fn
         (mf/deps objects)
         (fn [path last-path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> objects
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-media (:id %) (cmm/rename-group % path last-path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps objects selected create-group)
         (fn [event]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:accept create-group})))

        on-rename-group
        (mf/use-fn
         (mf/deps objects)
         (fn [event path last-path]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:path path
                                            :last-path last-path
                                            :accept rename-group})))
        on-ungroup
        (mf/use-fn
         (mf/deps objects)
         (fn [path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> objects
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-media (:id %) (cmm/ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-drag-start
        (mf/use-fn
         (fn [{:keys [name id mtype]} event]
           (dnd/set-data! event "text/asset-id" (str id))
           (dnd/set-data! event "text/asset-name" name)
           (dnd/set-data! event "text/asset-type" mtype)
           (dnd/set-allowed-effect! event "move")))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))]

    [:& cmm/asset-section {:file-id file-id
                           :title (tr "workspace.assets.graphics")
                           :section :graphics
                           :assets-count (count objects)
                           :open? open?}
     (when local?
       [:& cmm/asset-section-block {:role :title-button}
        (when (and (not components-v2) (not read-only?))
          [:button {:class (stl/css :assets-btn)
                    :on-click add-graphic}
           i/add
           [:& file-uploader {:accept cm/str-image-types
                              :multi true
                              :ref input-ref
                              :on-selected on-file-selected}]])])

     [:& cmm/asset-section-block {:role :content}
      [:& graphics-group {:file-id file-id
                          :prefix ""
                          :groups groups
                          :open-groups open-groups
                          :force-open? force-open?
                          :renaming (:renaming @state)
                          :listing-thumbs? listing-thumbs?
                          :selected selected
                          :on-asset-click on-asset-click
                          :on-drag-start on-drag-start
                          :do-rename do-rename
                          :cancel-rename cancel-rename
                          :on-rename-group on-rename-group
                          :on-ungroup on-ungroup
                          :on-context-menu on-context-menu
                          :selected-full selected-full}]
      (when local?
        [:& cmm/assets-context-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-objects? multi-assets?)
                      {:name    (tr "workspace.assets.rename")
                       :id      "assets-rename-graphics"
                       :handler on-rename})
                    {:name    (tr "workspace.assets.delete")
                     :id       "assets-delete-graphics"
                     :handler on-delete}
                    (when-not multi-assets?
                      {:name    (tr "workspace.assets.group")
                       :id      "assets-group-graphics"
                       :handler on-group})]}])]]))
