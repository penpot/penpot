;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.media :as cm]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.file :as ctf]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.editable-label :refer [editable-label]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.libraries :refer [create-file-library-ref]]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [typography-entry]]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.strings :refer [matches-search]]
   [app.util.timers :as ts]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(def ctx:filters           (mf/create-context nil))
(def ctx:toggle-ordering   (mf/create-context nil))
(def ctx:toggle-list-style (mf/create-context nil))

(def lens:selected
  (-> (l/in [:workspace-assets :selected])
      (l/derived st/state)))

(def lens:open-status
  (l/derived (l/in [:workspace-assets :open-status]) st/state))

(def lens:typography-section-state
  (l/derived (fn [gstate]
               {:rename-typography (:rename-typography gstate)
                :edit-typography (:edit-typography gstate)})
             refs/workspace-global
             =))

;; ---- Group assets management ----

(defn group-assets
  "Convert a list of assets in a nested structure like this:

    {'': [{assetA} {assetB}]
     'group1': {'': [{asset1A} {asset1B}]
                'subgroup11': {'': [{asset11A} {asset11B} {asset11C}]}
                'subgroup12': {'': [{asset12A}]}}
     'group2': {'subgroup21': {'': [{asset21A}}}}
  "
  [assets reverse-sort?]
  (when-not (empty? assets)
    (reduce (fn [groups {:keys [path] :as asset}]
              (let [path (cph/split-path (or path ""))]
                (update-in groups
                           (conj path "")
                           (fn [group]
                             (if group
                               (conj group asset)
                               [asset])))))
            (sorted-map-by (fn [key1 key2]
                             (if reverse-sort?
                               (compare key2 key1)
                               (compare key1 key2))))
            assets)))

(defn add-group
  [asset group-name]
  (-> (:path asset)
      (cph/merge-path-item group-name)
      (cph/merge-path-item (:name asset))))

(defn rename-group
  [asset path last-path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cph/split-path)
      butlast
      (vec)
      (conj last-path)
      (cph/join-path)
      (str (str/slice (:path asset) (count path)))
      (cph/merge-path-item (:name asset))))

(defn ungroup
  [asset path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cph/split-path)
      butlast
      (cph/join-path)
      (str (str/slice (:path asset) (count path)))
      (cph/merge-path-item (:name asset))))

(s/def ::asset-name ::us/not-empty-string)
(s/def ::name-group-form
  (s/keys :req-un [::asset-name]))

(mf/defc name-group-dialog
  {::mf/register modal/components
   ::mf/register-as :name-group-dialog}
  [{:keys [path last-path accept] :as ctx
    :or {path "" last-path ""}}]
  (let [initial (mf/use-memo
                 (mf/deps last-path)
                 (constantly {:asset-name last-path}))
        form  (fm/use-form :spec ::name-group-form
                           :initial initial)

        create? (empty? path)

        on-close (mf/use-fn #(modal/hide!))

        on-accept
        (mf/use-fn
         (mf/deps form)
         (fn [_]
           (let [asset-name (get-in @form [:clean-data :asset-name])]
             (if create?
               (accept asset-name)
               (accept path asset-name))
             (modal/hide!))))]

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (if create?
               (tr "workspace.assets.create-group")
               (tr "workspace.assets.rename-group"))]]
       [:div.modal-close-button
        {:on-click on-close} i/close]]

      [:div.modal-content.generic-form
       [:& fm/form {:form form :on-submit on-accept}
        [:& fm/input {:name :asset-name
                      :auto-focus? true
                      :label (tr "workspace.assets.group-name")
                      :hint (tr "workspace.assets.create-group-hint")}]]]

      [:div.modal-footer
       [:div.action-buttons
        [:input.cancel-button
         {:type "button"
          :value (tr "labels.cancel")
          :on-click on-close}]

        [:input.accept-button.primary
         {:type "button"
          :class (when-not (:valid @form) "btn-disabled")
          :disabled (not (:valid @form))
          :value (if create? (tr "labels.create") (tr "labels.rename"))
          :on-click on-accept}]]]]]))


;; ---- Group assets by drag and drop ----

(defn- create-assets-group
  [rename components-to-group group-name]
  (let [undo-id (js/Symbol)]
    (st/emit! (dwu/start-undo-transaction undo-id))
    (apply st/emit!
           (->> components-to-group
                (map #(rename
                       (:id %)
                       (add-group % group-name)))))
    (st/emit! (dwu/commit-undo-transaction undo-id))))

(defn- on-drop-asset
  [event asset dragging* selected selected-full selected-paths rename]
  (let [create-typed-assets-group (partial create-assets-group rename)]
    (when (not (dnd/from-child? event))
      (reset! dragging* false)
      (when
          (and (not (contains? selected (:id asset)))
               (every? #(= % (:path asset)) selected-paths))
        (let [components-to-group (conj selected-full asset)
              create-typed-assets-group (partial create-typed-assets-group components-to-group)]
          (modal/show! :name-group-dialog {:accept create-typed-assets-group}))))))

(defn- on-drag-enter-asset
  [event asset dragging* selected selected-paths]
  (when (and
         (not (dnd/from-child? event))
         (every? #(= % (:path asset)) selected-paths)
         (not (contains? selected (:id asset))))
    (reset! dragging* true)))

(defn- on-drag-leave-asset
  [event dragging*]
  (when (not (dnd/from-child? event))
    (reset! dragging* false)))

(defn- create-counter-element
  [asset-count]
  (let [counter-el (dom/create-element "div")]
    (dom/set-property! counter-el "class" "drag-counter")
    (dom/set-text! counter-el (str asset-count))
    counter-el))

(defn- set-drag-image
  [event item-ref num-selected]
  (let [offset          (dom/get-offset-position (.-nativeEvent event))
        item-el         (mf/ref-val item-ref)
        counter-el      (create-counter-element num-selected)]

    ;; set-drag-image requires that the element is rendered and
    ;; visible to the user at the moment of creating the ghost
    ;; image (to make a snapshot), but you may remove it right
    ;; afterwards, in the next render cycle.
    (dom/append-child! item-el counter-el)
    (dnd/set-drag-image! event item-el (:x offset) (:y offset))
    (ts/raf #(.removeChild ^js item-el counter-el))))

(defn- on-asset-drag-start
  [event file-id asset selected item-ref asset-type on-drag-start]
  (let [id-asset     (:id asset)
        num-selected (if (contains? selected id-asset)
                       (count selected)
                       1)]
    (when (not (contains? selected id-asset))
      (st/emit! (dw/unselect-all-assets file-id)
                (dw/toggle-selected-assets file-id id-asset asset-type)))
    (on-drag-start asset event)
    (when (> num-selected 1)
      (set-drag-image event item-ref num-selected))))

(defn- on-drag-enter-asset-group
  [event dragging* prefix selected-paths]
  (dom/stop-propagation event)
  (when (and (not (dnd/from-child? event))
             (not (every? #(= % prefix) selected-paths)))
    (reset! dragging* true)))

(defn- on-drop-asset-group
  [event dragging* prefix selected-paths selected-full rename]
  (dom/stop-propagation event)
  (when (not (dnd/from-child? event))
    (reset! dragging* false)
    (when (not (every? #(= % prefix) selected-paths))
      (doseq [target-asset selected-full]
        (st/emit!
         (rename
          (:id target-asset)
          (cph/merge-path-item prefix (:name target-asset))))))))

;; ---- Common blocks ----

(def ^:private initial-context-menu-state
  {:open? false :top nil :left nil})

(defn- open-context-menu
  [state pos]
  (let [top (:y pos)
        left (+ (:x pos) 10)]
    (assoc state
           :open? true
           :top top
           :left left)))

(defn- close-context-menu
  [state]
  (assoc state :open? false))

(mf/defc assets-context-menu
  {::mf/wrap-props false}
  [{:keys [options state on-close]}]
  [:& context-menu
   {:selectable false
    :show (:open? state)
    :on-close on-close
    :top (:top state)
    :left (:left state)
    :options options}])

(mf/defc asset-section
  {::mf/wrap-props false}
  [{:keys [children file-id title section assets-count open?]}]
  (let [children (->> (if (array? children) children [children])
                      (filter some?))
        get-role #(.. % -props -role)
        title-buttons (filter #(= (get-role %) :title-button) children)
        content       (filter #(= (get-role %) :content) children)]
    [:div.asset-section
     [:div.asset-title {:class (when (not ^boolean open?) "closed")}
      [:span {:on-click #(st/emit! (dw/set-assets-section-open file-id section (not open?)))}
       i/arrow-slide title]
      [:span.num-assets (str "\u00A0(") assets-count ")"] ;; Unicode 00A0 is non-breaking space
      title-buttons]
     (when ^boolean open?
       content)]))

(mf/defc asset-section-block
  [{:keys [children]}]
  [:* children])

(mf/defc asset-group-title
  [{:keys [file-id section path group-open? on-rename on-ungroup]}]
  (when-not (empty? path)
    (let [[other-path last-path truncated] (cph/compact-path path 35)
          menu-state (mf/use-state initial-context-menu-state)

          on-fold-group
          (mf/use-fn
           (mf/deps file-id section path group-open?)
           (fn [event]
             (dom/stop-propagation event)
             (st/emit! (dw/set-assets-group-open file-id
                                                 section
                                                 path
                                                 (not group-open?)))))
          on-context-menu
          (mf/use-fn
           (fn [event]
             (dom/prevent-default event)
             (let [pos (dom/get-client-position event)]
               (swap! menu-state open-context-menu pos))))

          on-close-menu
          (mf/use-fn #(swap! menu-state close-context-menu))]

      [:div.group-title {:class (when-not group-open? "closed")
                         :on-click on-fold-group
                         :on-context-menu on-context-menu}
       [:span i/arrow-slide]
       (when-not (empty? other-path)
         [:span.dim {:title (when truncated path)}
          other-path "\u00A0/\u00A0"])
       [:span {:title (when truncated path)}
        last-path]
       [:& assets-context-menu
        {:on-close on-close-menu
         :state @menu-state
         :options [[(tr "workspace.assets.rename") #(on-rename % path last-path)]
                   [(tr "workspace.assets.ungroup") #(on-ungroup path)]]}]])))


;;---- Components section ----


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
         (mf/deps component selected)
         (fn [event]
           (dom/stop-propagation event)
           (let [main-instance-id (:main-instance-id component)
                 main-instance-page (:main-instance-page component)]
             (when (and main-instance-id main-instance-page) ;; Only when :components-v2 is enabled
               (st/emit! (dw/go-to-main-instance main-instance-page main-instance-id))))))

        on-drop
        (mf/use-fn
         (mf/deps component dragging* selected selected-full selected-paths)
         (fn [event]
           (on-drop-asset event component dragging* selected selected-full
                          selected-paths dwl/rename-component)))

        on-drag-enter
        (mf/use-fn
         (mf/deps component dragging* selected selected-paths)
         (fn [event]
           (on-drag-enter-asset event component dragging* selected selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-component-drag-start
        (mf/use-fn
         (mf/deps file-id component selected item-ref on-drag-start read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event file-id component selected item-ref :components on-drag-start))))

        on-context-menu
        (mf/use-fn
         (mf/deps component-id)
         (partial on-context-menu component-id))]

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
            {:class-name (dom/classnames
                          :cell-name listing-thumbs?
                          :item-name (not listing-thumbs?)
                          :editing renaming?)
             :value (cph/merge-path-item (:path component) (:name component))
             :tooltip (cph/merge-path-item (:path component) (:name component))
             :display-value (:name component)
             :editing? renaming?
             :disable-dbl-click? true
             :on-change do-rename
             :on-cancel cancel-rename}]

           (when ^boolean dragging?
             [:div.dragging])])])]))

(mf/defc components-group
  {::mf/wrap-props false}
  [{:keys [file-id prefix groups open-groups renaming listing-thumbs? selected on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-group on-ungroup on-context-menu
           selected-full]}]

  (let [group-open?    (get open-groups prefix true)

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
           (on-drag-enter-asset-group event dragging* prefix selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full)
         (fn [event]
           (on-drop-asset-group event dragging* prefix selected-paths selected-full dwl/rename-component)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}

     [:& asset-group-title
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
                                  :selected-full selected-full}]))])]))

(mf/defc components-section
  {::mf/wrap-props false}
  [{:keys [file-id local? components listing-thumbs? open? reverse-sort? selected
           on-asset-click on-assets-delete on-clear-selection open-status-ref]}]

  (let [input-ref                (mf/use-ref nil)
        state                    (mf/use-state {:renaming nil
                                                :component-id nil})

        open-groups-ref          (mf/with-memo [open-status-ref]
                                   (-> (l/in [:groups :components])
                                       (l/derived open-status-ref)))

        open-groups              (mf/deref open-groups-ref)

        menu-state               (mf/use-state initial-context-menu-state)
        read-only?               (mf/use-ctx ctx/workspace-read-only?)

        selected                 (:components selected)
        selected-full            (into #{} (filter #(contains? selected (:id %))) components)
        multi-components?        (> (count selected) 1)
        multi-assets?            (or (seq (:graphics selected))
                                     (seq (:colors selected))
                                     (seq (:typographies selected)))

        groups                   (mf/with-memo [components reverse-sort?]
                                   (group-assets components reverse-sort?))

        components-v2            (mf/use-ctx ctx/components-v2)

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
         (mf/deps @state)
         (fn []
           (let [undo-id (js/Symbol)]
             (if (empty? selected)
               (st/emit! (dwl/duplicate-component file-id (:component-id @state)))
               (do
                 (st/emit! (dwu/start-undo-transaction undo-id))
                 (apply st/emit! (map (partial dwl/duplicate-component file-id) selected))
                 (st/emit! (dwu/commit-undo-transaction undo-id)))))))

        on-delete
        (mf/use-fn
         (mf/deps @state file-id multi-components? multi-assets?)
         (fn []
           (let [undo-id (js/Symbol)]
             (if (or multi-components? multi-assets?)
               (on-assets-delete)
               (st/emit! (dwu/start-undo-transaction undo-id)
                         (dwl/delete-component {:id (:component-id @state)})
                         (dwl/sync-file file-id file-id :components (:component-id @state))
                         (dwu/commit-undo-transaction undo-id))))))

        on-rename
        (mf/use-fn
         (fn []
           (swap! state (fn [state]
                          (assoc state :renaming (:component-id state))))))

        do-rename
        (mf/use-fn
         (mf/deps @state)
         (fn [new-name]
           (let [component-id (:renaming @state)
                 component    (dm/get-in file [:components component-id])
                 main-instance-id   (:main-instance-id component)
                 main-instance-page (:main-instance-page component)]

             (dwl/rename-component-and-main-instance component-id main-instance-id new-name main-instance-page)
             (swap! state assoc :renaming nil))))

        cancel-rename
        (mf/use-fn
         (fn []
           (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-fn
         (mf/deps selected on-clear-selection read-only?)
         (fn [component-id event]
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]
             (when (and local? (not read-only?))
               (when-not (contains? selected component-id)
                 (on-clear-selection))
               (swap! state assoc :component-id component-id)
               (swap! menu-state open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-context-menu)))

        create-group
        (mf/use-fn
         (mf/deps components selected on-clear-selection)
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> components
                        (filter #(if multi-components?
                                   (contains? selected (:id %))
                                    (= (:component-id @state) (:id %))))
                        (map #(dwl/rename-component
                               (:id %)
                               (add-group % group-name)))))
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
                               (rename-group % path last-path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps components selected)
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
                        (map #(dwl/rename-component (:id %) (ungroup % path)))))
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
         (mf/deps @state components)
         (fn [event]
           (dom/stop-propagation event)
           (let [component-id (:component-id @state)
                 component (->> components
                                (filter #(= (:id %) component-id))
                                first)
                 main-instance-id (:main-instance-id component)
                 main-instance-page (:main-instance-page component)]
             (when (and main-instance-id main-instance-page) ;; Only when :components-v2 is enabled
               (st/emit! (dw/go-to-main-instance main-instance-page main-instance-id))))))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))]

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.components")
                       :section :components
                       :assets-count (count components)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when (and components-v2 (not read-only?))
          [:div.assets-button {:on-click add-component}
           i/plus
           [:& file-uploader {:accept cm/str-image-types
                              :multi true
                              :ref input-ref
                              :on-selected on-file-selected}]])])

     [:& asset-section-block {:role :content}
      [:& components-group {:file-id file-id
                            :prefix ""
                            :groups groups
                            :open-groups open-groups
                            :renaming (:renaming @state)
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
        [:& assets-context-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-components? multi-assets?)
                      [(tr "workspace.assets.rename") on-rename])
                    (when-not multi-assets?
                      [(if components-v2
                         (tr "workspace.assets.duplicate-main")
                         (tr "workspace.assets.duplicate")) on-duplicate])
                    [(tr "workspace.assets.delete") on-delete]
                    (when-not multi-assets?
                      [(tr "workspace.assets.group") on-group])
                    (when (and components-v2 (not multi-assets?))
                        [(tr "workspace.shape.menu.show-main") on-show-main])]}])]]))


;; ---- Graphics section ----

(mf/defc graphics-item
  [{:keys [object renaming listing-thumbs? selected-objects file-id
           on-asset-click on-context-menu on-drag-start do-rename cancel-rename
           selected-full selected-graphics-paths]}]
  (let [item-ref   (mf/use-ref)
        visible?   (h/use-visible item-ref :once? true)
        object-id  (:id object)

        dragging*  (mf/use-state false)
        dragging?  (deref dragging*)

        read-only? (mf/use-ctx ctx/workspace-read-only?)

        on-drop
        (mf/use-fn
         (mf/deps object dragging* selected-objects selected-full selected-graphics-paths)
         (fn [event]
           (on-drop-asset event object dragging* selected-objects selected-full
                          selected-graphics-paths dwl/rename-media)))

        on-drag-enter
        (mf/use-fn
         (mf/deps object dragging* selected-objects selected-graphics-paths)
         (fn [event]
           (on-drag-enter-asset event object dragging* selected-objects selected-graphics-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-grahic-drag-start
        (mf/use-fn
         (mf/deps object file-id selected-objects item-ref on-drag-start read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event file-id object selected-objects item-ref :graphics on-drag-start))))

        on-context-menu
        (mf/use-fn
         (mf/deps object-id)
         (partial on-context-menu object-id))

        on-asset-click
        (mf/use-fn
         (mf/deps object-id on-asset-click)
         (partial on-asset-click object-id nil))

        ]

    [:div {:ref item-ref
           :class-name (dom/classnames
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
               :draggable false}] ;; Also need to add css pointer-events: none

        (let [renaming? (= renaming (:id object))]
          [:*
           [:& editable-label
            {:class-name (dom/classnames
                          :cell-name listing-thumbs?
                          :item-name (not listing-thumbs?)
                          :editing renaming?)
             :value (cph/merge-path-item (:path object) (:name object))
             :tooltip (cph/merge-path-item (:path object) (:name object))
             :display-value (:name object)
             :editing? renaming?
             :disable-dbl-click? true
             :on-change do-rename
             :on-cancel cancel-rename}]

           (when ^boolean dragging?
             [:div.dragging])])])]))

(mf/defc graphics-group
  [{:keys [file-id prefix groups open-groups renaming listing-thumbs? selected-objects on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-ungroup
           on-context-menu selected-full]}]
  (let [group-open? (get open-groups prefix true)

        dragging*   (mf/use-state false)
        dragging?   (deref dragging*)

        selected-paths
        (mf/with-memo [selected-full]
          (into #{}
                (comp (map :path) (d/nilv ""))
                selected-full))

        on-drag-enter
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths)
         (fn [event]
           (on-drag-enter-asset-group event dragging* prefix selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full)
         (fn [event]
           (on-drop-asset-group event dragging* prefix selected-paths selected-full dwl/rename-media)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:& asset-group-title {:file-id file-id
                            :section :graphics
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [objects (get groups "" [])]
          [:div {:class-name (dom/classnames
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
             [:div.grid-placeholder "\u00A0"])

           (when (and (empty? objects)
                      (some? groups))
             [:div.drop-space])

           (for [object objects]
             [:& graphics-item {:key (dm/str "object-" (:id object))
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
                                :prefix (cph/merge-path-item prefix path-item)
                                :groups content
                                :open-groups open-groups
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
  [{:keys [file-id project-id local? objects listing-thumbs? open? open-status-ref selected reverse-sort?
           on-asset-click on-assets-delete on-clear-selection]}]
  (let [input-ref         (mf/use-ref nil)
        state             (mf/use-state {:renaming nil :object-id nil})

        menu-state        (mf/use-state initial-context-menu-state)
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
                            (group-assets objects reverse-sort?))

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
                          (assoc state :renaming (:component-id state))))))
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
               (swap! menu-state open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-context-menu)))

        create-group
        (mf/use-fn
         (mf/deps objects selected on-clear-selection)
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit!
                   (->> objects
                        (filter #(if multi-objects?
                                   (contains? selected (:id %))
                                   (= (:object-id @state) (:id %))))
                        (map #(dwl/rename-media (:id %) (add-group % group-name)))))
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
                        (map #(dwl/rename-media (:id %) (rename-group % path last-path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps objects selected)
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
                        (map #(dwl/rename-media (:id %) (ungroup % path)))))
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

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.graphics")
                       :section :graphics
                       :assets-count (count objects)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when (and (not components-v2) (not read-only?))
          [:div.assets-button {:on-click add-graphic}
           i/plus
           [:& file-uploader {:accept cm/str-image-types
                              :multi true
                              :ref input-ref
                              :on-selected on-file-selected}]])])

     [:& asset-section-block {:role :content}
      [:& graphics-group {:file-id file-id
                          :prefix ""
                          :groups groups
                          :open-groups open-groups
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
        [:& assets-context-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-objects? multi-assets?)
                      [(tr "workspace.assets.rename") on-rename])
                    [(tr "workspace.assets.delete") on-delete]
                    (when-not multi-assets?
                      [(tr "workspace.assets.group") on-group])]}])]]))


;; ---- Colors section ----

(mf/defc color-item
  {::mf/wrap-props false}
  [{:keys [color local? file-id selected multi-colors? multi-assets?
           on-asset-click on-assets-delete on-clear-selection on-group
           selected-full selected-paths move-color]}]

  (let [color        (mf/with-memo [color file-id]
                       (cond-> color
                         (:value color) (assoc :color (:value color) :opacity 1)
                         (:value color) (dissoc :value)
                         true           (assoc :file-id file-id)))


        color-id    (:id color)

        item-ref    (mf/use-ref)
        dragging*   (mf/use-state false)
        dragging?   (deref dragging*)

        rename?     (= (:color-for-rename @refs/workspace-local) color-id)
        input-ref   (mf/use-ref)

        editing*    (mf/use-state rename?)
        editing?    (deref editing*)

        menu-state  (mf/use-state initial-context-menu-state)
        read-only?  (mf/use-ctx ctx/workspace-read-only?)

        default-name (cond
                       (:gradient color) (uc/gradient-type->string (dm/get-in color [:gradient :type]))
                       (:color color)    (:color color)
                       :else             (:value color))

        apply-color
        (mf/use-fn
         (mf/deps color)
         (fn [event]
           (st/emit! (dc/apply-color-from-palette (merge uc/empty-color color) (kbd/alt? event)))))

        rename-color
        (mf/use-fn
         (mf/deps file-id color-id)
         (fn [name]
           (st/emit! (dwl/rename-color file-id color-id name))))

        edit-color
        (mf/use-fn
         (mf/deps color file-id)
         (fn [attrs]
           (let [name  (cph/merge-path-item (:path color) (:name color))
                 color (-> attrs
                           (assoc :id (:id color))
                           (assoc :file-id file-id)
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
           (let [target (dom/event->target event)
                 name   (dom/get-value target)]
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
               (swap! menu-state open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-context-menu)))

        on-drop
        (mf/use-fn
         (mf/deps color dragging* selected selected-full selected-paths move-color)
         (fn [event]
           (on-drop-asset event color dragging* selected selected-full
                          selected-paths move-color)))

        on-drag-enter
        (mf/use-fn
         (mf/deps color dragging* selected selected-paths)
         (fn [event]
           (on-drag-enter-asset event color dragging* selected selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-color-drag-start
        (mf/use-fn
         (mf/deps color file-id selected item-ref read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event file-id color selected item-ref :colors identity))))

        on-click
        (mf/use-fn
         (mf/deps color-id apply-color on-asset-click)
         (partial on-asset-click color-id apply-color))]

    (mf/with-effect [editing?]
      (when editing?
        (let [input (mf/ref-val input-ref)]
          (dom/select-text! input)
          nil)))

    [:div.asset-list-item
     {:class-name (dom/classnames
                   :selected (contains? selected (:id color)))
      :on-context-menu on-context-menu
      :on-click (when-not editing? on-click)
      :ref item-ref
      :draggable (and (not read-only?) (not editing?))
      :on-drag-start on-color-drag-start
      :on-drag-enter on-drag-enter
      :on-drag-leave on-drag-leave
      :on-drag-over dom/prevent-default
      :on-drop on-drop}

     [:& bc/color-bullet {:color color}]

     (if ^boolean editing?
       [:input.element-name
        {:type "text"
         :ref input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :default-value (cph/merge-path-item (:path color) (:name color))}]

       [:div.name-block {:title (:name color)
                         :on-double-click rename-color-clicked}
        (:name color)
        (when-not (= (:name color) default-name)
          [:span default-name])])

     (when local?
       [:& assets-context-menu
        {:on-close on-close-menu
         :state @menu-state
         :options [(when-not (or multi-colors? multi-assets?)
                     [(tr "workspace.assets.rename") rename-color-clicked])
                   (when-not (or multi-colors? multi-assets?)
                     [(tr "workspace.assets.edit") edit-color-clicked])
                   [(tr "workspace.assets.delete") delete-color]
                   (when-not multi-assets?
                     [(tr "workspace.assets.group") (on-group (:id color))])]}])

     (when ^boolean dragging?
       [:div.dragging])]))

(mf/defc colors-group
  [{:keys [file-id prefix groups open-groups local? selected
           multi-colors? multi-assets? on-asset-click on-assets-delete
           on-clear-selection on-group on-rename-group on-ungroup colors
           selected-full]}]
  (let [group-open?    (get open-groups prefix true)

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
           (on-drag-enter-asset-group event dragging* prefix selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full move-color)
         (fn [event]
           (on-drop-asset-group event dragging* prefix selected-paths selected-full move-color)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:& asset-group-title {:file-id file-id
                            :section :colors
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [colors (get groups "" [])]
          [:div.asset-list {:on-drag-enter on-drag-enter
                            :on-drag-leave on-drag-leave
                            :on-drag-over dom/prevent-default
                            :on-drop on-drop}

           (when ^boolean dragging?
             [:div.grid-placeholder "\u00A0"])

           (when (and (empty? colors)
                      (some? groups))
             [:div.drop-space])

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
                              :prefix (cph/merge-path-item prefix path-item)
                              :key (dm/str "group-" path-item)
                              :groups content
                              :open-groups open-groups
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
  [{:keys [file-id local? colors open? open-status-ref selected reverse-sort?
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
                          (group-assets colors reverse-sort?))

        read-only?      (mf/use-ctx ctx/workspace-read-only?)

        add-color
        (mf/use-fn
         (fn [value _]
           (st/emit! (dwl/add-color value))))

        add-color-clicked
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (st/emit! (dw/set-assets-section-open file-id :colors true)
                     (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                            :asset-type "color"}))
           (modal/show! :colorpicker
                        {:x (.-clientX event)
                         :y (.-clientY event)
                         :on-accept add-color
                         :data {:color "#406280"
                                :opacity 1}
                         :position :right})))

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
                                      (add-group % group-name))
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
                                      (rename-group % path last-path))
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
                                       (ungroup % path))
                                file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-asset-click
        (mf/use-fn (mf/deps groups on-asset-click) (partial on-asset-click groups))]

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.colors")
                       :section :colors
                       :assets-count (count colors)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when-not read-only?
          [:div.assets-button {:on-click add-color-clicked}
           i/plus])])

     [:& asset-section-block {:role :content}
      [:& colors-group {:file-id file-id
                        :prefix ""
                        :groups groups
                        :open-groups open-groups
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

;; ---- Typography section ----

(mf/defc typography-item
  {::mf/wrap-props false}
  [{:keys [typography file-id local? handle-change selected apply-typography editing-id on-asset-click
           on-context-menu selected-full selected-paths move-typography rename?]}]
  (let [item-ref      (mf/use-ref)
        typography-id (:id typography)

        dragging*     (mf/use-state false)
        dragging?     (deref dragging*)

        read-only?    (mf/use-ctx ctx/workspace-read-only?)
        editing?      (= editing-id (:id typography))

        open*         (mf/use-state editing?)
        open?         (deref open*)

        on-drop
        (mf/use-fn
         (mf/deps typography dragging* selected selected-full selected-paths move-typography)
         (fn [event]
           (on-drop-asset event typography dragging* selected selected-full
                          selected-paths move-typography)))

        on-drag-enter
        (mf/use-fn
         (mf/deps typography dragging* selected selected-paths)
         (fn [event]
           (on-drag-enter-asset event typography dragging* selected selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-typography-drag-start
        (mf/use-fn
         (mf/deps typography file-id selected item-ref read-only?)
         (fn [event]
           (if read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event file-id typography selected item-ref :typographies identity))))

        on-context-menu
        (mf/use-fn
         (mf/deps on-context-menu typography-id)
         (partial on-context-menu typography-id))

        handle-change
        (mf/use-fn
         (mf/deps typography)
         (partial handle-change typography))

        apply-typography
        (mf/use-fn
         (mf/deps typography)
         (partial apply-typography typography))

        on-asset-click
        (mf/use-fn
         (mf/deps typography apply-typography on-asset-click)
         (partial on-asset-click typography-id apply-typography))

        ]

    [:div.typography-container {:ref item-ref
                                :draggable (and (not read-only?) (not open?))
                                :on-drag-start on-typography-drag-start
                                :on-drag-enter on-drag-enter
                                :on-drag-leave on-drag-leave
                                :on-drag-over dom/prevent-default
                                :on-drop on-drop}
     [:& typography-entry
      {:typography typography
       :local? local?
       :on-context-menu on-context-menu
       :on-change handle-change
       :selected? (contains? selected typography-id)
       :on-click on-asset-click
       :editing? editing?
       :focus-name? rename?
       :external-open* open*
       :file-id file-id
       }]

     (when ^boolean dragging?
       [:div.dragging])]))

(mf/defc typographies-group
  {::mf/wrap-props false}
  [{:keys [file-id prefix groups open-groups file local? selected local-data
           editing-id on-asset-click handle-change apply-typography on-rename-group
           on-ungroup on-context-menu selected-full]}]
  (let [group-open?   (get open-groups prefix true)
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
           (on-drag-enter-asset-group event dragging* prefix selected-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging*)
         (fn [event]
           (on-drag-leave-asset event dragging*)))

        on-drop
        (mf/use-fn
         (mf/deps dragging* prefix selected-paths selected-full move-typography)
         (fn [event]
           (on-drop-asset-group event dragging* prefix selected-paths selected-full move-typography)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over dom/prevent-default
           :on-drop on-drop}
     [:& asset-group-title {:file-id file-id
                            :section :typographies
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [typographies (get groups "" [])]
          [:div.asset-list {:on-drag-enter on-drag-enter
                            :on-drag-leave on-drag-leave
                            :on-drag-over dom/prevent-default
                            :on-drop on-drop}

           (when ^boolean dragging?
             [:div.grid-placeholder "\u00A0"])

           (when (and
                  (empty? typographies)
                  (some? groups))
             [:div.drop-space])
           (for [{:keys [id] :as typography} typographies]
             [:& typography-item {:typography typography
                                  :key (dm/str "typography-" id)
                                  :file-id file-id
                                  :local? local?
                                  :handle-change handle-change
                                  :selected selected
                                  :apply-typography apply-typography
                                  :editing-id editing-id
                                  :rename? (= (:rename-typography local-data) id)
                                  :on-asset-click on-asset-click
                                  :on-context-menu on-context-menu
                                  :selected-full selected-full
                                  :selected-paths selected-paths
                                  :move-typography move-typography}])])

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& typographies-group {:file-id file-id
                                    :prefix (cph/merge-path-item prefix path-item)
                                    :key (dm/str "group-" path-item)
                                    :groups content
                                    :open-groups open-groups
                                    :file file
                                    :local? local?
                                    :selected selected
                                    :editing-id editing-id
                                    :local-data local-data
                                    :on-asset-click on-asset-click
                                    :handle-change handle-change
                                    :apply-typography apply-typography
                                    :on-rename-group on-rename-group
                                    :on-ungroup on-ungroup
                                    :on-context-menu on-context-menu
                                    :selected-full selected-full}]))])]))

(mf/defc typographies-section
  {::mf/wrap-props false}
  [{:keys [file file-id local? typographies open? open-status-ref selected reverse-sort?
           on-asset-click on-assets-delete on-clear-selection]}]
  (let [state         (mf/use-state {:detail-open? false :id nil})
        local-data    (mf/deref lens:typography-section-state)

        read-only?    (mf/use-ctx ctx/workspace-read-only?)
        menu-state    (mf/use-state initial-context-menu-state)
        typographies  (mf/with-memo [typographies]
                        (mapv dwl/extract-path-if-missing typographies))

        groups        (mf/with-memo [typographies reverse-sort?]
                        (group-assets typographies reverse-sort?))

        selected      (:typographies selected)
        selected-full (mf/with-memo [selected typographies]
                        (into #{} (filter #(contains? selected (:id %))) typographies))

        multi-typographies?  (> (count selected) 1)
        multi-assets?        (or (seq (:components selected))
                                 (seq (:graphics selected))
                                 (seq (:colors selected)))

        open-groups-ref      (mf/with-memo [open-status-ref]
                               (-> (l/in [:groups :components])
                                   (l/derived open-status-ref)))

        open-groups          (mf/deref open-groups-ref)

        add-typography
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (st/emit! (dwt/add-typography file-id))))

        handle-change
        (mf/use-fn
         (mf/deps file-id)
         (fn [typography changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        apply-typography
        (mf/use-fn
         (mf/deps file-id)
         (fn [typography _event]
           (st/emit! (dwt/apply-typography typography file-id))))

        create-group
        (mf/use-fn
         (mf/deps typographies selected on-clear-selection file-id)
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
                                      (add-group % group-name))
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
                                      (rename-group % path last-path))
                               file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps typographies selected)
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
                                (ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-context-menu
        (mf/use-fn
         (mf/deps selected on-clear-selection read-only?)
         (fn [id event]
           (dom/prevent-default event)
           (let [pos (dom/get-client-position event)]
             (when (and local? (not read-only?))
               (when-not (contains? selected id)
                 (on-clear-selection))
               (swap! state assoc :id id)
               (swap! menu-state open-context-menu pos)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-context-menu)))

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

        editing-id (or (:rename-typography local-data)
                       (:edit-typography local-data))

        on-asset-click
        (mf/use-fn
         (mf/deps groups on-asset-click)
         (partial on-asset-click groups))]

    (mf/use-effect
     (mf/deps local-data)
     (fn []
       (when (:rename-typography local-data)
         (st/emit! #(update % :workspace-global dissoc :rename-typography)))
       (when (:edit-typography local-data)
         (st/emit! #(update % :workspace-global dissoc :edit-typography)))))

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.typography")
                       :section :typographies
                       :assets-count (count typographies)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when-not read-only?
          [:div.assets-button {:on-click add-typography}
           i/plus])])

     [:& asset-section-block {:role :content}
      [:& typographies-group {:file-id file-id
                              :prefix ""
                              :groups groups
                              :open-groups open-groups
                              :state state
                              :file file
                              :local? local?
                              :selected selected
                              :editing-id editing-id
                              :local-data local-data
                              :on-asset-click on-asset-click
                              :handle-change handle-change
                              :apply-typography apply-typography
                              :on-rename-group on-rename-group
                              :on-ungroup on-ungroup
                              :on-context-menu on-context-menu
                              :selected-full selected-full}]

      (when local?
        [:& assets-context-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-typographies? multi-assets?)
                      [(tr "workspace.assets.rename") handle-rename-typography-clicked])
                    (when-not (or multi-typographies? multi-assets?)
                      [(tr "workspace.assets.edit") handle-edit-typography-clicked])
                    [(tr "workspace.assets.delete") handle-delete-typography]
                    (when-not multi-assets?
                      [(tr "workspace.assets.group") on-group])]}])]]))


;; --- Assets toolsection ----

(defn- apply-filters
  [coll {:keys [ordering term] :as filters}]
  (let [reverse? (= :desc ordering)
        comp-fn  (if ^boolean reverse? > <)]
    (->> coll
         (filter (fn [item]
                   (or (matches-search (:name item "!$!") term)
                       (matches-search (:value item "!$!") term))))
                                        ; Sort by folder order, but
                                        ; putting all "root" items
                                        ; always first, independently
                                        ; of sort order.
         (sort-by #(str/lower (cph/merge-path-item (if (empty? (:path %))
                                                     (if reverse? "z" "a")
                                                     (:path %))
                                                   (:name %)))
                  comp-fn))))


(mf/defc file-library-title
  {::mf/wrap-props false}
  [{:keys [open? local? shared? project-id file-id page-id file-name]}]
  (let [router     (mf/deref refs/router)
        url        (rt/resolve router :workspace
                               {:project-id project-id
                                :file-id file-id}
                               {:page-id page-id})

        toggle-open
        (mf/use-fn
         (mf/deps file-id open?)
         (fn []
           (st/emit! (dw/set-assets-section-open file-id :library (not open?)))))
        ]

    [:div.tool-window-bar.library-bar
     {:on-click toggle-open}
     [:div.collapse-library
      {:class (dom/classnames :open open?)}
      i/arrow-slide]

     (if local?
       [:*
        [:span file-name " (" (tr "workspace.assets.local-library") ")"]
        (when shared?
          [:span.tool-badge (tr "workspace.assets.shared")])]
       [:*
        [:span file-name]
        [:span.tool-link.tooltip.tooltip-left {:alt "Open library file"}
         [:a {:href (str "#" url)
              :target "_blank"
              :on-click dom/stop-propagation}
          i/chain]]])]))

(mf/defc file-library-content
  {::mf/wrap-props false}
  [{:keys [file local? open-status-ref on-clear-selection]}]
  (let [components-v2      (mf/use-ctx ctx/components-v2)
        open-status        (mf/deref open-status-ref)

        file-id            (:id file)
        project-id         (:project-id file)

        filters            (mf/use-ctx ctx:filters)
        filters-section    (:section filters)
        filters-term       (:term filters)
        filters-ordering   (:ordering filters)
        filters-list-style (:list-style filters)

        reverse-sort?      (= :desc filters-ordering)
        listing-thumbs?    (= :thumbs filters-list-style)

        toggle-ordering    (mf/use-ctx ctx:toggle-ordering)
        toggle-list-style  (mf/use-ctx ctx:toggle-list-style)

        library-ref        (mf/with-memo [file-id]
                             (create-file-library-ref file-id))

        library            (mf/deref library-ref)
        colors             (:colors library)
        components         (:components library)
        media              (:media library)
        typographies       (:typographies library)

        colors             (mf/with-memo [filters colors]
                             (apply-filters colors filters))
        components         (mf/with-memo [filters components]
                             (apply-filters components filters))
        media              (mf/with-memo [filters media]
                             (apply-filters media filters))
        typographies       (mf/with-memo [filters typographies]
                             (apply-filters typographies filters))

        show-components?   (and (or (= filters-section :all)
                                    (= filters-section :components))
                                (or (pos? (count components))
                                    (str/empty? filters-term)))
        show-graphics?     (and (or (= filters-section :all)
                                    (= filters-section :graphics))
                                (or (pos? (count media))
                                    (and (str/empty? filters-term)
                                         (not components-v2))))
        show-colors?       (and (or (= filters-section :all)
                                    (= filters-section :colors))
                                (or (> (count colors) 0)
                                         (str/empty? filters-term)))
        show-typography?   (and (or (= filters-section :all)
                                    (= filters-section :typographies))
                                (or (pos? (count typographies))
                                    (str/empty? filters-term)))


        selected-lens      (mf/with-memo [file-id]
                             (-> (l/key file-id)
                                 (l/derived lens:selected)))
        selected           (mf/deref selected-lens)
        selected-count     (+ (count (get selected :components))
                              (count (get selected :graphics))
                              (count (get selected :colors))
                              (count (get selected :typographies)))

        extend-selected
        (fn [type asset-groups asset-id]
          (letfn [(flatten-groups [groups]
                    (reduce concat [(get groups "" [])
                                    (into []
                                          (->> (filter #(seq (first %)) groups)
                                               (map second)
                                               (mapcat flatten-groups)))]))]

            (let [selected' (get selected type)]
              (if (zero? (count selected'))
                (st/emit! (dw/select-single-asset file-id asset-id type))
                (let [all-assets  (flatten-groups asset-groups)
                      click-index (d/index-of-pred all-assets #(= (:id %) asset-id))
                      first-index (->> (get selected type)
                                       (map (fn [asset] (d/index-of-pred all-assets #(= (:id %) asset))))
                                       (sort)
                                       (first))

                      min-index   (min first-index click-index)
                      max-index   (max first-index click-index)
                      ids         (->> (d/enumerate all-assets)
                                       (into #{} (comp (filter #(<= min-index (first %) max-index))
                                                       (map (comp :id second)))))]

                  (st/emit! (dw/select-assets file-id ids type)))))))

        on-asset-click
        (mf/use-fn
         (mf/deps file-id extend-selected)
         (fn [asset-type asset-groups asset-id default-click event]
           (cond
             (kbd/mod? event)
             (do
               (dom/stop-propagation event)
               (st/emit! (dw/toggle-selected-assets file-id asset-id asset-type)))

             (kbd/shift? event)
             (do
               (dom/stop-propagation event)
               (extend-selected asset-type asset-groups asset-id))

             :else
             (when default-click
               (default-click event)))))

        on-component-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :components))

        on-graphics-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :graphics))

        on-colors-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :colors))

        on-typography-click
        (mf/use-fn (mf/deps on-asset-click) (partial on-asset-click :typographies))

        on-assets-delete
        (mf/use-fn
         (mf/deps selected file-id)
         (fn []
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (run! st/emit! (map #(dwl/delete-component {:id %})
                                 (:components selected)))
             (run! st/emit! (map #(dwl/delete-media {:id %})
                                 (:graphics selected)))
             (run! st/emit! (map #(dwl/delete-color {:id %})
                                 (:colors selected)))
             (run! st/emit! (map #(dwl/delete-typography %)
                                 (:typographies selected)))

             (when (or (seq (:components selected))
                       (seq (:colors selected))
                       (seq (:typographies selected)))
               (st/emit! (dwl/sync-file file-id file-id)))

             (st/emit! (dwu/commit-undo-transaction undo-id)))))]

    [:div.tool-window-content
     [:div.listing-options
      (when (> selected-count 0)
        [:span.selected-count
         (tr "workspace.assets.selected-count" (i18n/c selected-count))])
      [:div.listing-option-btn.first {:on-click toggle-ordering}
       (if reverse-sort?
         i/sort-ascending
         i/sort-descending)]
      [:div.listing-option-btn {:on-click toggle-list-style}
       (if listing-thumbs?
         i/listing-enum
         i/listing-thumbs)]]

     (when ^boolean show-components?
       [:& components-section
        {:file-id file-id
         :local? local?
         :components components
         :listing-thumbs? listing-thumbs?
         :open? (get open-status :components true)
         :open-status-ref open-status-ref
         :reverse-sort? reverse-sort?
         :selected selected
         :on-asset-click on-component-click
         :on-assets-delete on-assets-delete
         :on-clear-selection on-clear-selection}])

     (when ^boolean show-graphics?
       [:& graphics-section
        {:file-id file-id
         :project-id project-id
         :local? local?
         :objects media
         :listing-thumbs? listing-thumbs?
         :open? (get open-status :graphics true)
         :open-status-ref open-status-ref
         :reverse-sort? reverse-sort?
         :selected selected
         :on-asset-click on-graphics-click
         :on-assets-delete on-assets-delete
         :on-clear-selection on-clear-selection}])

     (when ^boolean show-colors?
       [:& colors-section
        {:file-id file-id
         :local? local?
         :colors colors
         :open? (get open-status :colors true)
         :open-status-ref open-status-ref
         :reverse-sort? reverse-sort?
         :selected selected
         :on-asset-click on-colors-click
         :on-assets-delete on-assets-delete
         :on-clear-selection on-clear-selection}])

     (when ^boolean show-typography?
       [:& typographies-section
        {:file file
         :file-id (:id file)
         :local? local?
         :typographies typographies
         :open? (get open-status :typographies true)
         :open-status-ref open-status-ref
         :reverse-sort? reverse-sort?
         :selected selected
         :on-asset-click on-typography-click
         :on-assets-delete on-assets-delete
         :on-clear-selection on-clear-selection}])

     (when (and (not ^boolean show-components?)
                (not ^boolean show-graphics?)
                (not ^boolean show-colors?)
                (not ^boolean show-typography?))
       [:div.asset-section
        [:div.asset-title (tr "workspace.assets.not-found")]])]))

(mf/defc file-library
  {::mf/wrap-props false}
  [{:keys [file local? default-open? filters]}]
  (let [file-id         (:id file)
        file-name       (:name file)
        shared?         (:is-shared file)
        project-id      (:project-id file)
        page-id         (dm/get-in file [:data :pages 0])

        open-status-ref (mf/with-memo [file-id]
                          (-> (l/key file-id)
                              (l/derived lens:open-status)))
        open-status      (mf/deref open-status-ref)
        open?            (d/nilv (:library open-status) default-open?)

        unselect-all
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (st/emit! (dw/unselect-all-assets file-id))))

        ]

    [:div.tool-window {:on-context-menu dom/prevent-default
                       :on-click unselect-all}
     [:& file-library-title
      {:project-id project-id
       :file-id file-id
       :page-id page-id
       :file-name file-name
       :open? open?
       :local? local?
       :shared? shared?}]
     (when ^boolean open?
       [:& file-library-content
        {:file file
         :local? local?
         :filters filters
         :on-clear-selection unselect-all
         :open-status-ref open-status-ref}])]))

(mf/defc assets-libraries
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [filters]}]
  (let [libraries (mf/deref refs/workspace-libraries)
        libraries (mf/with-memo [libraries]
                    (->> (vals libraries)
                         (remove :is-indirect)
                         (map (fn [file]
                                (update file :data dissoc :pages-index)))
                         (sort-by #(str/lower (:name %)))))]
    (for [file libraries]
      [:& file-library
       {:key (dm/str (:id file))
        :file file
        :local? false
        :default-open? false
        :filters filters}])))

(mf/defc assets-local-library
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [filters]}]
  ;; NOTE: as workspace-file is an incomplete view of file (it do not
  ;; contain :data), we need to reconstruct it using workspace-data
  (let [file   (mf/deref refs/workspace-file)
        data   (mf/deref refs/workspace-data)
        data   (mf/with-memo [data]
                 (dissoc data :pages-index))
        file   (mf/with-memo [file data]
                 (assoc file :data data))]

    [:& file-library
     {:file file
      :local? true
      :default-open? true
      :filters filters}]))

(defn- toggle-values
  [v [a b]]
  (if (= v a) b a))

(mf/defc assets-toolbox
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  []
  (let [read-only? (mf/use-ctx ctx/workspace-read-only?)
        filters*   (mf/use-state
                    {:term ""
                     :section :all
                     :ordering :asc
                     :list-style :thumbs})
        filters    (deref filters*)


        toggle-ordering
        (mf/use-fn #(swap! filters* update :ordering toggle-values [:asc :desc]))

        toggle-list-style
        (mf/use-fn #(swap! filters* update :list-style toggle-values [:thumbs :list]))

        on-search-term-change
        (mf/use-fn
         (fn [event]
           (let [value (dom/get-target-val event)]
             (swap! filters* assoc :term value))))

        on-search-clear-click
        (mf/use-fn #(swap! filters* assoc :term ""))

        on-section-filter-change
        (mf/use-fn
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value)
                           (d/read-string))]
             (swap! filters* assoc :section value))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (dom/event->target event)]

             (when ^boolean enter? (dom/blur! node))
             (when ^boolean esc?   (dom/blur! node)))))

        show-libraries-dialog
        (mf/use-fn #(modal/show! :libraries-dialog {}))]

    [:div.assets-bar
     [:div.tool-window
      [:div.tool-window-content
       [:div.assets-bar-title
        (tr "workspace.assets.assets")

        (when-not ^boolean read-only?
          [:div.libraries-button {:on-click show-libraries-dialog}
           i/text-align-justify
           (tr "workspace.assets.libraries")])]

       [:div.search-block
        [:input.search-input
         {:placeholder (tr "workspace.assets.search")
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

       [:select.input-select {:value (:section filters)
                              :on-change on-section-filter-change}
        [:option {:value ":all"} (tr "workspace.assets.box-filter-all")]
        [:option {:value ":components"} (tr "workspace.assets.components")]
        [:option {:value ":graphics"} (tr "workspace.assets.graphics")]
        [:option {:value ":colors"} (tr "workspace.assets.colors")]
        [:option {:value ":typographies"} (tr "workspace.assets.typography")]]]]

     [:& (mf/provider ctx:filters) {:value filters}
      [:& (mf/provider ctx:toggle-ordering) {:value toggle-ordering}
       [:& (mf/provider ctx:toggle-list-style) {:value toggle-list-style}
        [:div.libraries-wrapper
         [:& assets-local-library {:filters filters}]
         [:& assets-libraries {:filters filters}]]]]]]))
