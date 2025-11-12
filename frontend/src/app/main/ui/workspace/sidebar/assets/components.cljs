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
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctc]
   [app.common.types.file :as ctf]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-label :refer [editable-label*]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.groups :as grp]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def drag-data* (atom {:is-local false}))

(defn set-drag-data! [data]
  (reset! drag-data* data))

(defn- get-component-root-and-container
  [file-id component]
  (let [data       (dm/get-in @refs/files [file-id :data])
        root-shape (ctf/get-component-root data component)
        container  (ctf/get-component-page data component)]
    [root-shape container]))

(mf/defc components-item*
  [{:keys [component renaming is-listing-thumbs selected
           file-id on-asset-click on-context-menu on-drag-start do-rename
           cancel-rename selected-full selected-paths is-local num-variants]}]

  (let [item-ref       (mf/use-ref)

        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        component-id   (:id component)

        visible?       (h/use-visible item-ref :once? true)
        renaming?      (= renaming (:id component))

        ;; NOTE: we don't use reactive deref for it because we don't
        ;; really need rerender on any change on the file change. If
        ;; the component changes, it will trigger rerender anyway.
        [root-shape container]
        (get-component-root-and-container file-id component)

        on-component-click
        (mf/use-fn
         (mf/deps component-id on-asset-click)
         (fn [event]
           (dom/stop-propagation event)
           (when-not (on-asset-click event component-id)
             (st/emit! (dw/unselect-all-assets)))))

        on-component-double-click
        (mf/use-fn
         (mf/deps file-id component is-local renaming?)
         (fn [event]
           (dom/stop-propagation event)
           (when-not renaming?
             (if is-local
               (st/emit! (dwl/go-to-local-component :id component-id))
               (st/emit! (dwl/go-to-component-file file-id component false))))))

        on-drop
        (mf/use-fn
         (mf/deps component dragging* selected selected-full selected-paths is-local drag-data*)
         (fn [event]
           (when (and is-local (:is-local @drag-data*))
             (cmm/on-drop-asset event component dragging* selected selected-full
                                selected-paths dwv/rename-comp-or-variant-and-main))))

        on-drag-enter
        (mf/use-fn
         (mf/deps component dragging* selected selected-paths is-local drag-data*)
         (fn [event]
           (when (and is-local (:is-local @drag-data*))
             (cmm/on-drag-enter-asset event component dragging* selected selected-paths))))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging* is-local drag-data*)
         (fn [event]
           (when (and is-local (:is-local @drag-data*))
             (cmm/on-drag-leave-asset event dragging*))))

        on-component-drag-start
        (mf/use-fn
         (mf/deps file-id component selected item-ref on-drag-start read-only? renaming? is-local)
         (fn [event]
           (if (or read-only? renaming?)
             (dom/prevent-default event)
             (cmm/on-asset-drag-start event file-id component selected item-ref :components on-drag-start))))

        on-context-menu
        (mf/use-fn
         (mf/deps on-context-menu component-id)
         (partial on-context-menu component-id))]

    [:div {:ref item-ref
           :class (stl/css-case :component-item true
                                :component-item-grid is-listing-thumbs
                                :component-item-list (not is-listing-thumbs)
                                :component-item-selected (contains? selected (:id component)))
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
         [:div {:class (stl/css-case :component-item-grid-name is-listing-thumbs
                                     :component-item-list-name (not is-listing-thumbs)
                                     :component-item-editing renaming?)}
          [:> editable-label*
           {:class-input (stl/css-case :component-item-grid-input is-listing-thumbs
                                       :component-item-list-input (not is-listing-thumbs))
            :class-label (stl/css-case :component-item-grid-label is-listing-thumbs
                                       :component-item-list-label (not is-listing-thumbs))
            :value (cpn/merge-path-item (:path component) (:name component))
            :tooltip (cpn/merge-path-item (:path component) (:name component))
            :display-value (:name component)
            :is-editing renaming?
            :on-change do-rename
            :on-cancel cancel-rename}]]

         (when ^boolean dragging?
           [:div {:class (stl/css :component-item-dragging)}])]

        [:> cmm/component-item-thumbnail*
         {:file-id file-id
          :class (stl/css-case :component-item-thumbnail true
                               :component-item-list-thumbnail (not is-listing-thumbs))
          :root-shape root-shape
          :component component
          :container container
          :is-hidden (not visible?)}]

        (when (ctc/is-variant? component)
          [:span {:class (stl/css-case :component-item-variant-mark true
                                       :component-item-grid-variant-mark is-listing-thumbs)
                  :title (tr "workspace.assets.components.num-variants" num-variants)}
           [:> icon* {:icon-id i/variant :size "s"}]])])]))

(defn- count-leaves
  "Counts the total number of leaf elements in a nested map structure.
     A leaf element is considered any element inside a vector."
  [m]
  (reduce-kv (fn [acc _ v]
               (cond
                 (map? v) (+ acc (count-leaves v))
                 (vector? v) (+ acc (count v))
                 :else acc))
             0
             m))

(mf/defc components-group*
  [{:keys [file-id prefix groups open-groups is-force-open renaming is-listing-thumbs selected on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-group on-ungroup on-context-menu
           selected-full is-local count-variants on-group-combine-variants]}]

  (let [group-open?    (if (false? (get open-groups prefix)) ;; if the user has closed it specifically, respect that
                         false
                         (or ^boolean is-force-open
                             ^boolean (get open-groups prefix (if (= prefix "") true false))))
        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        selected-paths (mf/with-memo [selected-full]
                         (into #{}
                               (comp (map :path) (d/nilv ""))
                               selected-full))

        components     (not-empty (get groups "" []))
        can-combine?   (mf/with-memo [is-local groups components]
                         (and is-local
                              (> (count-leaves groups) 1)
                              (not-any? ctc/is-variant? components)
                              (apply = (map :main-instance-page components))))
        on-drag-enter
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths is-local drag-data*)
         (fn [event]
           (when (and is-local (:is-local @drag-data*))
             (cmm/on-drag-enter-asset-group event dragging* prefix selected-paths))))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging* is-local drag-data*)
         (fn [event]
           (when (and is-local (:is-local @drag-data*))
             (cmm/on-drag-leave-asset event dragging*))))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full is-local drag-data*)
         (fn [event]
           (when (and is-local (:is-local @drag-data*))
             (cmm/on-drop-asset-group event dragging* prefix selected-paths selected-full dwv/rename-comp-or-variant-and-main))))]

    [:div {:class (stl/css :component-group)
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:> grp/asset-group-title*
      {:file-id file-id
       :section :components
       :path prefix
       :is-group-open group-open?
       :is-can-combine can-combine?
       :on-rename on-rename-group
       :on-ungroup on-ungroup
       :on-group-combine-variants on-group-combine-variants}]

     (when group-open?
       [:*
        [:div {:class (stl/css-case :component-group-grid is-listing-thumbs
                                    :component-group-list (not is-listing-thumbs))
               :on-drag-enter on-drag-enter
               :on-drag-leave on-drag-leave
               :on-drag-over dom/prevent-default
               :on-drop on-drop}

         (when ^boolean dragging?
           [:div {:class (stl/css :component-group-placeholder)} "\u00A0"])

         (when (and (empty? components)
                    (some? groups)
                    is-local)
           [:div {:class (stl/css-case :component-group-drop-space true
                                       :component-group-drop-space-small (not dragging?))}])

         ;; FIXME: This could be in the thousands. We need to think about paginate this
         (for [component components]
           [:> components-item*
            {:component component
             :key (dm/str "component-" (:id component))
             :renaming renaming
             :is-listing-thumbs is-listing-thumbs
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
             :is-local is-local
             :num-variants (count-variants (:variant-id component))}])]

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:> components-group* {:file-id file-id
                                   :key path-item
                                   :prefix (cpn/merge-path-item prefix path-item)
                                   :groups content
                                   :open-groups open-groups
                                   :is-force-open is-force-open
                                   :renaming renaming
                                   :is-listing-thumbs is-listing-thumbs
                                   :selected selected
                                   :on-asset-click on-asset-click
                                   :on-drag-start on-drag-start
                                   :do-rename do-rename
                                   :cancel-rename cancel-rename
                                   :on-rename-group on-rename-group
                                   :on-ungroup on-ungroup
                                   :on-context-menu on-context-menu
                                   :on-group-combine-variants on-group-combine-variants
                                   :selected-full selected-full
                                   :is-local is-local
                                   :count-variants count-variants}]))])]))

(mf/defc components-section*
  [{:keys [file-id components selected open-status-ref
           is-local is-listing-thumbs is-open is-force-open is-reverse-sort
           on-asset-click on-assets-delete on-clear-selection
           delete-component count-variants]}]

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
        toggle-list-style        (mf/use-ctx cmm/assets-toggle-list-style)

        selected                 (:components selected)

        selected-full            (into #{} (filter #(contains? selected (:id %))) components)
        multi-components?        (> (count selected) 1)
        multi-assets?            (or (seq (:graphics selected))
                                     (seq (:colors selected))
                                     (seq (:typographies selected)))

        selected-and-current     (mf/with-memo [selected components current-component-id]
                                   (-> (d/nilv selected [])
                                       (conj current-component-id)
                                       set))

        selected-and-current-full (mf/with-memo [selected-and-current]
                                    (->> components
                                         (filter #(contains? selected-and-current (:id %)))))

        any-variant?             (mf/with-memo [selected-and-current]
                                   (some ctc/is-variant? selected-and-current-full))

        all-same-page?           (mf/with-memo [selected-and-current]
                                   (let [page (:main-instance-page (first selected-and-current-full))]
                                     (every? #(= page (:main-instance-page %)) selected-and-current-full)))

        groups                   (mf/with-memo [components is-reverse-sort]
                                   (grp/group-assets components is-reverse-sort))

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
                         (delete-component current-component-id)
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
              (dwv/rename-comp-or-variant-and-main current-component-id new-name)))))

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
                        (map #(dwv/rename-comp-or-variant-and-main
                               (:id %)
                               (cmm/add-group % group-name)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        rename-group
        (mf/use-fn
         (mf/deps components on-clear-selection)
         (fn [path last-path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> components
                        (filter #(cpn/inside-path? (:path %) path))
                        (map #(dwv/rename-comp-or-variant-and-main
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
         (mf/deps components on-clear-selection)
         (fn [path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> components
                        (filter #(cpn/inside-path? (:path %) path))
                        (map #(dwv/rename-comp-or-variant-and-main (:id %) (cmm/ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group-combine-variants
        (mf/use-fn
         (mf/deps components on-clear-selection)
         (fn [path]
           (on-clear-selection)
           (let [comps   (->> components
                              (filter #(cpn/inside-path? (:path %) path)))
                 ids     (into #{} (map :main-instance-id comps))
                 page-id (->> comps first :main-instance-page)]

             (st/emit!
              (dwv/combine-as-variants ids {:page-id page-id :trigger "workspace:context-menu-assets-group"})))))

        on-drag-start
        (mf/use-fn
         (mf/deps file-id)
         (fn [component event]

           (let [file-data
                 (dm/get-in @refs/files [file-id :data])

                 shape-main
                 (ctf/get-component-root file-data component)]

             ;; dnd api only allow to acces to the dataTransfer data on on-drop (https://html.spec.whatwg.org/dev/dnd.html#concept-dnd-p)
             ;; We need to know if the dragged element is from the local library on on-drag-enter, so we need to keep the info elsewhere
             (set-drag-data! {:file-id file-id
                              :component component
                              :shape shape-main
                              :is-local is-local})

             (dnd/set-data! event "penpot/component" true)

             ;; Remove the ghost image for componentes because we're going to instantiate it on the viewport
             (dnd/set-drag-image! event (dnd/invisible-image))

             (dnd/set-allowed-effect! event "move"))))

        on-show-main
        (mf/use-fn
         (mf/deps current-component-id file-id is-local)
         (fn [event]
           (dom/stop-propagation event)
           (if is-local
             (st/emit! (dwl/go-to-local-component :id current-component-id))
             (let [component (d/seek #(= (:id %) current-component-id) components)]
               (st/emit! (dwl/go-to-component-file file-id component false))))))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))

        on-combine-as-variants
        (mf/use-fn
         (mf/deps selected-and-current-full)
         (fn [event]
           (dom/stop-propagation event)
           (let [page-id (->> selected-and-current-full first :main-instance-page)
                 ids (into #{} (map :main-instance-id selected-full))]

             (st/emit!
              (dwv/combine-as-variants ids {:page-id page-id :trigger "workspace:context-menu-assets"})))))]

    [:> cmm/asset-section* {:file-id file-id
                            :title (tr "workspace.assets.components")
                            :section :components
                            :assets-count (count components)
                            :is-open is-open}
     [:> cmm/asset-section-block* {:role :title-button}
      (when ^boolean is-open
        [:div
         [:& radio-buttons {:selected (if is-listing-thumbs "grid" "list")
                            :on-change toggle-list-style
                            :name "listing-style"}
          [:& radio-button {:icon deprecated-icon/view-as-list
                            :value "list"
                            :title (tr "workspace.assets.list-view")
                            :id "opt-list"}]
          [:& radio-button {:icon deprecated-icon/flex-grid
                            :value "grid"
                            :title (tr "workspace.assets.grid-view")
                            :id "opt-grid"}]]])

      (when (and (not read-only?) is-local)
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.assets.components.add-component")
                          :on-click add-component
                          :icon i/add}
         [:& file-uploader {:accept dwm/accept-image-types
                            :multi true
                            :ref input-ref
                            :on-selected on-file-selected}]])]

     [:> cmm/asset-section-block* {:role :content}
      (when ^boolean is-open
        [:> components-group* {:file-id file-id
                               :prefix ""
                               :groups groups
                               :open-groups open-groups
                               :is-force-open is-force-open
                               :renaming (when ^boolean renaming? current-component-id)
                               :is-listing-thumbs is-listing-thumbs
                               :selected selected
                               :on-asset-click on-asset-click
                               :on-drag-start on-drag-start
                               :do-rename do-rename
                               :cancel-rename cancel-rename
                               :on-rename-group on-rename-group
                               :on-group on-group
                               :on-ungroup on-ungroup
                               :on-group-combine-variants on-group-combine-variants
                               :on-context-menu on-context-menu
                               :selected-full selected-full
                               :is-local ^boolean is-local
                               :count-variants count-variants}])

      [:> cmm/assets-context-menu*
       {:on-close on-close-menu
        :state @menu-state
        :options [(when (and is-local (not (or multi-components? multi-assets? read-only?)))
                    {:name    (tr "workspace.assets.rename")
                     :id      "assets-rename-component"
                     :handler on-rename})
                  (when (and is-local (not (or multi-assets? read-only? any-variant?)))
                    {:name    (tr "workspace.assets.duplicate-main")
                     :id     "assets-duplicate-component"
                     :handler on-duplicate})

                  (when (and is-local (not (or multi-assets? read-only?)))
                    {:name   (tr "workspace.assets.group")
                     :id     "assets-group-component"
                     :handler on-group})

                  (when (not multi-assets?)
                    {:name   (tr "workspace.shape.menu.show-main")
                     :id     "assets-show-main-component"
                     :handler on-show-main})
                  (when (and is-local multi-components? (not any-variant?))
                    {:name   (tr "workspace.shape.menu.combine-as-variants")
                     :id     "assets-combine-as-variants"
                     :title (when-not all-same-page? (tr "workspace.shape.menu.combine-as-variants-error"))
                     :disabled (not all-same-page?)
                     :handler on-combine-as-variants})

                  (when (and is-local (not read-only?))
                    {:name    (tr "workspace.assets.delete")
                     :id      "assets-delete-component"
                     :handler on-delete})]}]]]))
