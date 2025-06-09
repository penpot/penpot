;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.colors
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.groups :as grp]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc color-item
  {::mf/wrap-props false}
  [{:keys [color local? file-id selected multi-colors? multi-assets?
           on-asset-click on-assets-delete on-clear-selection on-group
           selected-full selected-paths move-color]}]

  (let [color        (mf/with-memo [color file-id]
                       (cond-> color
                         (:value color) (assoc :color (:value color) :opacity 1)
                         (:value color) (dissoc :value)))

        color-id    (:id color)

        item-ref    (mf/use-ref)
        dragging*   (mf/use-state false)
        dragging?   (deref dragging*)

        rename?     (= (:color-for-rename @refs/workspace-local) color-id)
        input-ref   (mf/use-ref)

        editing*    (mf/use-state rename?)
        editing?    (deref editing*)

        menu-state  (mf/use-state cmm/initial-context-menu-state)
        read-only?  (mf/use-ctx ctx/workspace-read-only?)

        default-name (cond
                       (:gradient color) (uc/gradient-type->string (dm/get-in color [:gradient :type]))
                       (:color color)    (:color color)
                       :else             (:value color))

        rename-color
        (mf/use-fn
         (mf/deps file-id color-id)
         (fn [name]
           (st/emit! (dwl/rename-color file-id color-id name))))

        edit-color
        (mf/use-fn
         (mf/deps color file-id)
         (fn [attrs]
           (let [name  (cfh/merge-path-item (:path color) (:name color))
                 color (-> attrs
                           (assoc :id (:id color))
                           (assoc :name name))]
             (st/emit! (dwl/update-color color file-id)))))

        delete-color
        (mf/use-fn
         (mf/deps multi-colors? multi-assets? file-id color-id)
         (fn []
           (if (or multi-colors? multi-assets?)
             (on-assets-delete)
             (let [undo-id (js/Symbol)]
               (st/emit! (dwu/start-undo-transaction undo-id)
                         (dwl/delete-color color)
                         (dwl/sync-file file-id file-id :colors color-id)
                         (dwu/commit-undo-transaction undo-id))))))

        rename-color-clicked
        (mf/use-fn
         (mf/deps read-only? local?)
         (fn [event]
           (when (and local? (not read-only?))
             (dom/prevent-default event)
             (reset! editing* true))))

        input-blur
        (mf/use-fn
         (mf/deps rename-color)
         (fn [event]
           (let [name (dom/get-target-val event)]
             (rename-color name)
             (st/emit! dwl/clear-color-for-rename)
             (reset! editing* false))))

        input-key-down
        (mf/use-fn
         (mf/deps input-blur)
         (fn [event]
           (when (kbd/esc? event)
             (st/emit! dwl/clear-color-for-rename)
             (reset! editing* false))
           (when (kbd/enter? event)
             (input-blur event))))

        edit-color-clicked
        (mf/use-fn
         (mf/deps edit-color color)
         (fn [event]
           (modal/show! :colorpicker
                        {:x (.-clientX ^js event)
                         :y (.-clientY ^js event)
                         :on-accept edit-color
                         :data color
                         :position :right})))

        on-context-menu
        (mf/use-fn
         (mf/deps color-id selected on-clear-selection read-only?)
         (fn [event]
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]
             (when (and local? (not read-only?))
               (when-not (contains? selected color-id)
                 (on-clear-selection))
               (swap! menu-state cmm/open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state cmm/close-context-menu)))

        on-drop
        (mf/use-fn
         (mf/deps color dragging* selected selected-full selected-paths move-color)
         (fn [event]
           (cmm/on-drop-asset event color dragging* selected selected-full
                              selected-paths move-color)))

        on-drag-enter
        (mf/use-fn
         (mf/deps color dragging* selected selected-paths)
         (fn [event]
           (cmm/on-drag-enter-asset event color dragging* selected selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (cmm/on-drag-leave-asset event dragging*)))

        on-color-drag-start
        (mf/use-fn
         (mf/deps color file-id selected item-ref read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (cmm/on-asset-drag-start event file-id color selected item-ref :colors identity))))

        on-click
        (mf/use-fn
         (mf/deps color on-asset-click read-only? file-id)
         (fn [event]
           (when-not read-only?
             (st/emit! (ptk/data-event ::ev/event
                                       {::ev/name "use-library-color"
                                        ::ev/origin "sidebar"
                                        :external-library (not local?)}))

             (when-not (on-asset-click event (:id color))
               (st/emit! (dc/apply-color-from-assets file-id color (kbd/alt? event)))))))]

    (mf/with-effect [editing?]
      (when editing?
        (let [input (mf/ref-val input-ref)]
          (dom/select-text! input)
          nil)))

    [:div {:class (stl/css-case :asset-list-item true
                                :selected (contains? selected (:id color))
                                :editing editing?)
           :style #js {"--bullet-size" "16px"}
           :on-context-menu on-context-menu
           :on-click (when-not editing? on-click)
           :ref item-ref
           :draggable (and (not read-only?) (not editing?))
           :on-drag-start on-color-drag-start
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}

     [:div {:class (stl/css :bullet-block)}
      [:& cb/color-bullet {:color color
                           :mini true}]]

     (if ^boolean editing?
       [:input
        {:type "text"
         :class (stl/css :element-name)
         :ref input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :max-length max-input-length
         :default-value (cfh/merge-path-item (:path color) (:name color))}]

       [:div {:title (if (= (:name color) default-name)
                       default-name
                       (dm/str (:name color) " (" default-name ")"))
              :class (stl/css :name-block)
              :on-double-click rename-color-clicked}

        (if (= (:name color) default-name)
          [:span  {:class (stl/css :default-name)} default-name]
          [:*
           (:name color)
           [:span  {:class (stl/css :default-name :default-name-with-color)} default-name]])])

     (when local?
       [:& cmm/assets-context-menu
        {:on-close on-close-menu
         :state @menu-state
         :options [(when-not (or multi-colors? multi-assets?)
                     {:name    (tr "workspace.assets.rename")
                      :id      "assets-rename-color"
                      :handler rename-color-clicked})
                   (when-not (or multi-colors? multi-assets?)
                     {:name    (tr "workspace.assets.edit")
                      :id      "assets-edit-color"
                      :handler edit-color-clicked})

                   {:name    (tr "workspace.assets.delete")
                    :id      "assets-delete-color"
                    :handler delete-color}
                   (when-not multi-assets?
                     {:name   (tr "workspace.assets.group")
                      :id     "assets-group-color"
                      :handler (on-group (:id color))})]}])

     (when ^boolean dragging?
       [:div {:class (stl/css :dragging)}])]))

(mf/defc colors-group
  [{:keys [file-id prefix groups open-groups force-open? local? selected
           multi-colors? multi-assets? on-asset-click on-assets-delete
           on-clear-selection on-group on-rename-group on-ungroup colors
           selected-full]}]
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

        move-color
        (mf/use-fn (mf/deps file-id) (partial dwl/rename-color file-id))

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
         (mf/deps dragging* prefix selected-paths selected-full move-color)
         (fn [event]
           (cmm/on-drop-asset-group event dragging* prefix selected-paths selected-full move-color)))]

    [:div {:class (stl/css :colors-group)
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:& grp/asset-group-title {:file-id file-id
                                :section :colors
                                :path prefix
                                :group-open? group-open?
                                :on-rename on-rename-group
                                :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [colors (get groups "" [])]
          [:div {:class (stl/css :asset-list)
                 :on-drag-enter on-drag-enter
                 :on-drag-leave on-drag-leave
                 :on-drag-over dom/prevent-default
                 :on-drop on-drop}

           (when ^boolean dragging?
             [:div {:class (stl/css :grid-placeholder)}
              "\u00A0"])

           (when (and (empty? colors)
                      (some? groups))
             [:div {:class (stl/css :drop-space)}])

           (for [color colors]
             [:& color-item {:key (dm/str (:id color))
                             :color color
                             :file-id file-id
                             :local? local?
                             :selected selected
                             :multi-colors? multi-colors?
                             :multi-assets? multi-assets?
                             :on-asset-click on-asset-click
                             :on-assets-delete on-assets-delete
                             :on-clear-selection on-clear-selection
                             :on-group on-group
                             :colors colors
                             :selected-full selected-full
                             :selected-paths selected-paths
                             :move-color move-color}])])

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& colors-group {:file-id file-id
                              :prefix (cfh/merge-path-item prefix path-item)
                              :key (dm/str "group-" path-item)
                              :groups content
                              :open-groups open-groups
                              :force-open? force-open?
                              :local? local?
                              :selected selected
                              :multi-colors? multi-colors?
                              :multi-assets? multi-assets?
                              :on-asset-click on-asset-click
                              :on-assets-delete on-assets-delete
                              :on-clear-selection on-clear-selection
                              :on-group on-group
                              :on-rename-group on-rename-group
                              :on-ungroup on-ungroup
                              :colors colors
                              :selected-full selected-full}]))])]))

(mf/defc colors-section
  [{:keys [file-id local? colors open? force-open? open-status-ref selected reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]

  (let [selected        (:colors selected)
        selected-full   (mf/with-memo [selected colors]
                          (into #{} (filter #(contains? selected (:id %))) colors))

        open-groups-ref (mf/with-memo [open-status-ref]
                          (-> (l/in [:groups :colors])
                              (l/derived open-status-ref)))
        open-groups     (mf/deref open-groups-ref)

        multi-colors?   (> (count selected) 1)
        multi-assets?   (or (seq (:components selected))
                            (seq (:graphics selected))
                            (seq (:typographies selected)))

        groups          (mf/with-memo [colors reverse-sort?]
                          (grp/group-assets colors reverse-sort?))

        read-only?      (mf/use-ctx ctx/workspace-read-only?)

        add-color
        (mf/use-fn
         (fn [value _]
           (st/emit! (dwl/add-color value))))

        add-color-clicked
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [bounds     (-> event
                                (dom/get-current-target)
                                (dom/get-bounding-rect))
                 x-position (:right bounds)
                 y-position (:top bounds)]

             (st/emit! (dw/set-assets-section-open file-id :colors true)
                       (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                              :asset-type "color"})
                       (modal/show :colorpicker
                                   {:x x-position
                                    :y y-position
                                    :on-accept add-color
                                    :data {:color "#406280"
                                           :opacity 1}
                                    :position :right})))))

        create-group
        (mf/use-fn
         (mf/deps colors selected on-clear-selection file-id)
         (fn [color-id]
           (fn [group-name]
             (on-clear-selection)
             (let [undo-id (js/Symbol)]
               (st/emit! (dwu/start-undo-transaction undo-id))
               (run! st/emit!
                     (->> colors
                          (filter #(if multi-colors?
                                     (contains? selected (:id %))
                                     (= color-id (:id %))))
                          (map #(dwl/update-color
                                 (assoc % :name
                                        (cmm/add-group % group-name))
                                 file-id))))
               (st/emit! (dwu/commit-undo-transaction undo-id))))))

        rename-group
        (mf/use-fn
         (mf/deps colors)
         (fn [path last-path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> colors
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/update-color
                               (assoc % :name
                                      (cmm/rename-group % path last-path))
                               file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps colors selected)
         (fn [color-id]
           (fn [event]
             (dom/stop-propagation event)
             (modal/show! :name-group-dialog {:accept (create-group color-id)}))))

        on-rename-group
        (mf/use-fn
         (mf/deps colors)
         (fn [event path last-path]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:path path
                                            :last-path last-path
                                            :accept rename-group})))
        on-ungroup
        (mf/use-fn
         (mf/deps colors)
         (fn [path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> colors
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/update-color
                                (assoc % :name
                                       (cmm/ungroup % path))
                                file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))]


    [:& cmm/asset-section {:file-id file-id
                           :title (tr "workspace.assets.colors")
                           :section :colors
                           :assets-count (count colors)
                           :open? open?}
     (when local?
       [:& cmm/asset-section-block {:role :title-button}
        (when-not read-only?
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.assets.colors.add-color")
                            :on-click add-color-clicked
                            :icon "add"}])])


     [:& cmm/asset-section-block {:role :content}
      [:& colors-group {:file-id file-id
                        :prefix ""
                        :groups groups
                        :open-groups open-groups
                        :force-open? force-open?
                        :local? local?
                        :selected selected
                        :multi-colors? multi-colors?
                        :multi-assets? multi-assets?
                        :on-asset-click on-asset-click
                        :on-assets-delete on-assets-delete
                        :on-clear-selection on-clear-selection
                        :on-group on-group
                        :on-rename-group on-rename-group
                        :on-ungroup on-ungroup
                        :colors colors
                        :selected-full selected-full}]]]))
