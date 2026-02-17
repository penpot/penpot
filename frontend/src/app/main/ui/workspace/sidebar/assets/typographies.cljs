;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.typographies
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.path-names :as cpn]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.groups :as grp]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [typography-entry]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def lens:typography-section-state
  (l/derived (fn [gstate]
               {:rename-typography (:rename-typography gstate)
                :edit-typography (:edit-typography gstate)})
             refs/workspace-global
             =))

(mf/defc typography-item
  {::mf/wrap-props false}
  [{:keys [typography file-id local? handle-change selected editing-id renaming-id on-asset-click
           on-context-menu selected-full selected-paths move-typography rename?]}]
  (let [item-ref       (mf/use-ref)
        typography-id  (:id typography)

        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        editing?       (= editing-id (:id typography))
        renaming?      (= renaming-id (:id typography))

        open*          (mf/use-state editing?)
        open?          (deref open*)

        on-drop
        (mf/use-fn
         (mf/deps typography dragging* selected selected-full selected-paths move-typography)
         (fn [event]
           (cmm/on-drop-asset event typography dragging* selected selected-full
                              selected-paths move-typography)))

        on-drag-enter
        (mf/use-fn
         (mf/deps typography dragging* selected selected-paths)
         (fn [event]
           (cmm/on-drag-enter-asset event typography dragging* selected selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (cmm/on-drag-leave-asset event dragging*)))

        on-typography-drag-start
        (mf/use-fn
         (mf/deps typography file-id selected item-ref read-only? renaming? open?)
         (fn [event]
           (if (or read-only? renaming? open?)
             (dom/prevent-default event)
             (cmm/on-asset-drag-start event file-id typography selected item-ref :typographies identity))))

        on-context-menu
        (mf/use-fn
         (mf/deps on-context-menu typography-id)
         (partial on-context-menu typography-id))

        handle-change
        (mf/use-fn
         (mf/deps typography)
         (partial handle-change typography))

        on-asset-click
        (mf/use-fn
         (mf/deps typography on-asset-click read-only? local?)
         (fn [event]
           (when-not read-only?
             (st/emit! (ptk/data-event ::ev/event
                                       {::ev/name "use-library-typography"
                                        ::ev/origin "sidebar"
                                        :external-library (not local?)}))
             (when-not (on-asset-click event (:id typography))
               (st/emit! (dwt/apply-typography typography file-id))))))]

    [:div {:class (stl/css :typography-item)
           :ref item-ref
           :draggable (and (not read-only?) (not open?))
           :on-drag-start on-typography-drag-start
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}

     [:& typography-entry
      {:file-id file-id
       :typography typography
       :local? local?
       :selected? (contains? selected typography-id)
       :on-click on-asset-click
       :on-change handle-change
       :on-context-menu on-context-menu
       :editing? editing?
       :renaming? renaming?
       :focus-name? rename?
       :external-open* open*}]
     (when ^boolean dragging?
       [:div {:class (stl/css :dragging)}])]))

(mf/defc typographies-group
  {::mf/wrap-props false}
  [{:keys [file-id prefix groups open-groups force-open? file local? selected local-data
           editing-id renaming-id on-asset-click handle-change on-rename-group
           on-ungroup on-context-menu selected-full]}]
  (let [group-open?    (if (false? (get open-groups prefix)) ;; if the user has closed it specifically, respect that
                         false
                         (get open-groups prefix true))
        dragging*      (mf/use-state false)
        dragging?      (deref dragging*)
        selected-paths (mf/with-memo [selected-full]
                         (into #{}
                               (comp (map :path) (d/nilv ""))
                               selected-full))
        move-typography
        (mf/use-fn
         (mf/deps file-id)
         (partial dwl/rename-typography file-id))

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
         (mf/deps dragging* prefix selected-paths selected-full move-typography)
         (fn [event]
           (cmm/on-drop-asset-group event dragging* prefix selected-paths selected-full move-typography)))]

    [:div {:class (stl/css :typographies-group)
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:> grp/asset-group-title* {:file-id file-id
                                 :section :typographies
                                 :path prefix
                                 :is-group-open group-open?
                                 :on-rename on-rename-group
                                 :on-ungroup on-ungroup}]

     (when group-open?
       [:*
        (let [typographies (get groups "" [])]
          [:div {:class (stl/css :assets-list)
                 :on-drag-enter on-drag-enter
                 :on-drag-leave on-drag-leave
                 :on-drag-over dom/prevent-default
                 :on-drop on-drop}

           (when ^boolean dragging?
             [:div {:class  (stl/css :grid-placeholder)} "\u00A0"])

           (when (and
                  (empty? typographies)
                  (some? groups))
             [:div  {:class (stl/css :drop-space)}])
           (for [{:keys [id] :as typography} typographies]
             [:& typography-item {:typography typography
                                  :key (dm/str "typography-" id)
                                  :file-id file-id
                                  :local? local?
                                  :handle-change handle-change
                                  :selected selected
                                  :editing-id editing-id
                                  :renaming-id renaming-id
                                  :rename? (= (:rename-typography local-data) id)
                                  :on-asset-click on-asset-click
                                  :on-context-menu on-context-menu
                                  :selected-full selected-full
                                  :selected-paths selected-paths
                                  :move-typography move-typography}])])

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& typographies-group {:file-id file-id
                                    :prefix (cpn/merge-path-item prefix path-item)
                                    :key (dm/str "group-" path-item)
                                    :groups content
                                    :open-groups open-groups
                                    :force-open? force-open?
                                    :file file
                                    :local? local?
                                    :selected selected
                                    :editing-id editing-id
                                    :renaming-id renaming-id
                                    :local-data local-data
                                    :on-asset-click on-asset-click
                                    :handle-change handle-change
                                    :on-rename-group on-rename-group
                                    :on-ungroup on-ungroup
                                    :on-context-menu on-context-menu
                                    :selected-full selected-full}]))])]))

(mf/defc typographies-section*
  [{:keys [file file-id typographies open-status-ref selected
           is-local is-open is-force-open is-reverse-sort
           on-asset-click on-assets-delete on-clear-selection]}]
  (let [state          (mf/use-state {:detail-open? false :id nil})
        local-data     (mf/deref lens:typography-section-state)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        menu-state     (mf/use-state cmm/initial-context-menu-state)

        typographies   (mf/with-memo [typographies]
                         (mapv dwl/extract-path-if-missing typographies))

        groups         (mf/with-memo [typographies is-reverse-sort]
                         (grp/group-assets typographies is-reverse-sort))

        selected       (:typographies selected)
        selected-full  (mf/with-memo [selected typographies]
                         (into #{} (filter #(contains? selected (:id %))) typographies))

        multi-typographies?  (> (count selected) 1)
        multi-assets?        (or (seq (:components selected))
                                 (seq (:graphics selected))
                                 (seq (:colors selected)))

        open-groups-ref      (mf/with-memo [open-status-ref]
                               (-> (l/in [:groups :typographies])
                                   (l/derived open-status-ref)))

        open-groups          (mf/deref open-groups-ref)

        add-typography
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (st/emit! (dw/set-assets-section-open file-id :typographies true))
           (st/emit! (dwt/add-typography file-id))))

        handle-change
        (mf/use-fn
         (mf/deps file-id)
         (fn [typography changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        create-group
        (mf/use-fn
         (mf/deps typographies selected on-clear-selection file-id (:id @state))
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> typographies
                        (filter #(if multi-typographies?
                                   (contains? selected (:id %))
                                   (= (:id @state) (:id %))))
                        (map #(dwl/update-typography
                               (assoc % :name
                                      (cmm/add-group % group-name))
                               file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        rename-group
        (mf/use-fn
         (mf/deps typographies)
         (fn [path last-path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> typographies
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/update-typography
                               (assoc % :name
                                      (cmm/rename-group % path last-path))
                               file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps typographies selected create-group)
         (fn [event]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:accept create-group})))

        on-rename-group
        (mf/use-fn
         (mf/deps typographies)
         (fn [event path last-path]
           (dom/stop-propagation event)
           (modal/show! :name-group-dialog {:path path
                                            :last-path last-path
                                            :accept rename-group})))

        on-ungroup
        (mf/use-fn
         (mf/deps typographies)
         (fn [path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> typographies
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/rename-typography
                                file-id
                                (:id %)
                                (cmm/ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-context-menu
        (mf/use-fn
         (mf/deps selected on-clear-selection read-only?)
         (fn [id event]
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]
             (when (not read-only?)
               (when-not (contains? selected id)
                 (on-clear-selection))
               (swap! state assoc :id id)
               (swap! menu-state cmm/open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state cmm/close-context-menu)))

        handle-rename-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-global :rename-typography] (:id @state))))

        handle-edit-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-global :edit-typography] (:id @state))))

        handle-delete-typography
        (mf/use-fn
         (mf/deps @state multi-typographies? multi-assets?)
         (fn []
           (let [undo-id (js/Symbol)]
             (if (or multi-typographies? multi-assets?)
               (on-assets-delete)
               (st/emit! (dwu/start-undo-transaction undo-id)
                         (dwl/delete-typography (:id @state))
                         (dwl/sync-file file-id file-id :typographies (:id @state))
                         (dwu/commit-undo-transaction undo-id))))))

        editing-id (:edit-typography local-data)

        renaming-id (:rename-typography local-data)

        on-asset-click
        (mf/use-fn
         (mf/deps groups on-asset-click)
         (partial on-asset-click groups))]

    (mf/use-effect
     (mf/deps local-data)
     (fn []
       (when (:edit-typography local-data)
         (st/emit! #(update % :workspace-global dissoc :edit-typography)))))

    [:*
     [:> cmm/asset-section* {:file-id file-id
                             :title (tr "workspace.assets.typography")
                             :section :typographies
                             :assets-count (count typographies)
                             :is-open is-open}
      (when is-local
        [:> cmm/asset-section-block* {:role :title-button}
         (when-not read-only?
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.assets.typography.add-typography")
                             :on-click add-typography
                             :icon i/add}])])

      [:> cmm/asset-section-block* {:role :content}
       [:& typographies-group {:file-id file-id
                               :prefix ""
                               :groups groups
                               :open-groups open-groups
                               :force-open? is-force-open
                               :state state
                               :file file
                               :local? is-local
                               :selected selected
                               :editing-id editing-id
                               :renaming-id renaming-id
                               :local-data local-data
                               :on-asset-click on-asset-click
                               :handle-change handle-change
                               :on-rename-group on-rename-group
                               :on-ungroup on-ungroup
                               :on-context-menu on-context-menu
                               :selected-full selected-full}]

       (if is-local
         [:> cmm/assets-context-menu*
          {:on-close on-close-menu
           :state @menu-state
           :options [(when-not (or multi-typographies? multi-assets?)
                       {:name    (tr "workspace.assets.rename")
                        :id      "assets-rename-typography"
                        :handler handle-rename-typography-clicked})

                     (when-not (or multi-typographies? multi-assets?)
                       {:name    (tr "workspace.assets.edit")
                        :id      "assets-edit-typography"
                        :handler handle-edit-typography-clicked})

                     {:name    (tr "workspace.assets.delete")
                      :id      "assets-delete-typography"
                      :handler handle-delete-typography}

                     (when-not multi-assets?
                       {:name    (tr "workspace.assets.group")
                        :id      "assets-group-typography"
                        :handler on-group})]}]

         [:> cmm/assets-context-menu*
          {:on-close on-close-menu
           :state @menu-state
           :options [{:name   "show info"
                      :id     "assets-rename-typography"
                      :handler handle-edit-typography-clicked}]}])]]]))
