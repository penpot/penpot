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
   [app.common.text :as txt]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.state-helpers :as wsh]
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
   [app.main.ui.workspace.sidebar.options.menus.text :refer [generate-typography-name]]
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

;; NOTE: TODO: for avoid too many arguments, I think we can use react
;; context variables for pass to the down tree all the common
;; variables that are defined on the MAIN container/box component.

;; TODO: change update operations to admit multiple ids, thus avoiding the need of
;;       emitting many events and opening an undo transaction. Also move the logic
;;       of grouping, deleting, etc. to events in the data module, since now the
;;       selection info is in the global state.

(def typography-data
  (l/derived #(dm/select-keys % [:rename-typography :edit-typography])
             refs/workspace-global =))

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
  (letfn [(sort-key [key1 key2]
            (if reverse-sort?
              (compare (d/name key2) (d/name key1))
              (compare (d/name key1) (d/name key2))))]
    (when-not (empty? assets)
      (reduce (fn [groups asset]
                (let [path-vector (cph/split-path (or (:path asset) ""))]
                  (update-in groups (conj path-vector "")
                             (fn [group]
                               (if-not group
                                 [asset]
                                 (conj group asset))))))
              (sorted-map-by sort-key)
              assets))))

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

        on-close #(modal/hide!)

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
  [event asset dragging? selected-assets selected-assets-full selected-assets-paths rename]
  (let [create-typed-assets-group (partial create-assets-group rename)]
    (when (not (dnd/from-child? event))
      (reset! dragging? false)
      (when
       (and (not (contains? selected-assets (:id asset)))
            (every? #(= % (:path asset)) selected-assets-paths))
        (let [components-to-group (conj selected-assets-full asset)
              create-typed-assets-group (partial create-typed-assets-group components-to-group)]
          (modal/show! :name-group-dialog {:accept create-typed-assets-group}))))))

(defn- on-drag-enter-asset
  [event asset dragging? selected-assets selected-assets-paths]
  (when (and
         (not (dnd/from-child? event))
         (every? #(= % (:path asset)) selected-assets-paths)
         (not (contains? selected-assets (:id asset))))
    (reset! dragging? true)))

(defn- on-drag-leave-asset
  [event dragging?]
  (when (not (dnd/from-child? event))
    (reset! dragging? false)))

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
  [event asset selected-assets item-ref asset-type on-drag-start]
  (let [id-asset (:id asset)
        num-selected (if (contains? selected-assets id-asset)
                       (count selected-assets)
                       1)]
    (when (not (contains? selected-assets id-asset))
      (st/emit! (dw/unselect-all-assets)
                (dw/toggle-selected-assets id-asset asset-type)))
    (on-drag-start asset event)
    (when (> num-selected 1)
      (set-drag-image event item-ref num-selected))))

(defn- on-drag-enter-asset-group
  [event dragging? prefix selected-assets-paths]
  (dom/stop-propagation event)
  (when (and (not (dnd/from-child? event))
             (not (every? #(= % prefix) selected-assets-paths)))
    (reset! dragging? true)))

(defn- on-drop-asset-group
  [event dragging? prefix selected-assets-paths selected-assets-full rename]
  (dom/stop-propagation event)
  (when (not (dnd/from-child? event))
    (reset! dragging? false)
    (when (not (every? #(= % prefix) selected-assets-paths))
      (doseq [target-asset selected-assets-full]
        (st/emit!
         (rename
          (:id target-asset)
          (cph/merge-path-item prefix (:name target-asset))))))))

;; ---- Common blocks ----

(def auto-pos-menu-state
  {:open? false
   :top nil
   :left nil})

(defn- open-auto-pos-menu
  [state event]
  (let [pos (dom/get-client-position event)
        top (:y pos)
        left (+ (:x pos) 10)]
    (dom/prevent-default event)
    (assoc state
           :open? true
           :top top
           :left left)))

(defn- close-auto-pos-menu
  [state]
  (assoc state :open? false))

(mf/defc auto-pos-menu
  [{:keys [options state on-close]}]
  [:& context-menu
   {:selectable false
    :show (:open? state)
    :on-close on-close
    :top (:top state)
    :left (:left state)
    :options options}])

(mf/defc asset-section
  [{:keys [children file-id title box assets-count open?]}]
  (let [children (->> (if (array? children) children [children])
                      (filter some?))
        get-role #(.. % -props -role)
        title-buttons (filter #(= (get-role %) :title-button) children)
        content       (filter #(= (get-role %) :content) children)]
    [:div.asset-section
     [:div.asset-title {:class (when (not open?) "closed")}
      [:span {:on-click #(st/emit! (dwl/set-assets-box-open file-id box (not open?)))}
       i/arrow-slide title]
      [:span.num-assets (str "\u00A0(") assets-count ")"] ;; Unicode 00A0 is non-breaking space
      title-buttons]
     (when open?
       content)]))

(mf/defc asset-section-block
  [{:keys [children]}]
  [:* children])

(mf/defc asset-group-title
  [{:keys [file-id box path group-open? on-rename on-ungroup]}]
  (when-not (empty? path)
    (let [[other-path last-path truncated] (cph/compact-path path 35)
          menu-state (mf/use-state auto-pos-menu-state)

          on-fold-group
          (mf/use-fn
           (mf/deps file-id box path group-open?)
           (fn [event]
             (dom/stop-propagation event)
             (st/emit! (dwl/set-assets-group-open file-id
                                                  box
                                                  path
                                                  (not group-open?)))))
          on-context-menu
          (mf/use-fn
           (fn [event]
             (swap! menu-state #(open-auto-pos-menu % event))))

          on-close-menu
          (mf/use-fn
           (fn []
             (swap! menu-state close-auto-pos-menu)))]

      [:div.group-title {:class (when-not group-open? "closed")
                         :on-click on-fold-group
                         :on-context-menu on-context-menu}
       [:span i/arrow-slide]
       (when-not (empty? other-path)
         [:span.dim {:title (when truncated path)}
          other-path "\u00A0/\u00A0"])
       [:span {:title (when truncated path)}
        last-path]
       [:& auto-pos-menu
        {:on-close on-close-menu
         :state @menu-state
         :options [[(tr "workspace.assets.rename") #(on-rename % path last-path)]
                   [(tr "workspace.assets.ungroup") #(on-ungroup path)]]}]])))


;;---- Components box ----

(mf/defc components-item
  [{:keys [component renaming listing-thumbs? selected-components file
           on-asset-click on-context-menu on-drag-start do-rename cancel-rename
           selected-components-full selected-components-paths]}]
  (let [item-ref             (mf/use-ref)
        dragging?            (mf/use-state false)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        components-v2        (mf/use-ctx ctx/components-v2)

        file                 (or (:data file) file)
        root-shape           (ctf/get-component-root file component)
        component-container  (if components-v2
                               (ctf/get-component-page file component)
                               component)
        unselect-all
        (mf/use-fn
         (fn []
           (st/emit! (dw/unselect-all-assets))))

        on-component-click
        (mf/use-fn
         (mf/deps component selected-components)
         (fn [event]
           (dom/stop-propagation event)
           (on-asset-click event (:id component) unselect-all)))

        on-component-double-click
        (mf/use-fn
          (mf/deps component selected-components)
          (fn [event]
            (dom/stop-propagation event)
            (let [main-instance-id (:main-instance-id component)
                  main-instance-page (:main-instance-page component)]
              (when (and main-instance-id main-instance-page) ;; Only when :components-v2 is enabled
                (st/emit! (dw/go-to-main-instance main-instance-page main-instance-id))))))

        on-drop
        (mf/use-fn
         (mf/deps component dragging? selected-components selected-components-full selected-components-paths)
         (fn [event]
           (on-drop-asset event component dragging? selected-components selected-components-full
                          selected-components-paths dwl/rename-component)))

        on-drag-over
        (mf/use-fn #(dom/prevent-default %))

        on-drag-enter
        (mf/use-fn
         (mf/deps component dragging? selected-components selected-components-paths)
         (fn [event]
           (on-drag-enter-asset event component dragging? selected-components selected-components-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-component-drag-start
        (mf/use-fn
         (mf/deps component selected-components item-ref on-drag-start workspace-read-only?)
         (fn [event]
           (if workspace-read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event component selected-components item-ref :components on-drag-start))))]

    [:div {:ref item-ref
           :class (dom/classnames
                   :selected (contains? selected-components (:id component))
                   :grid-cell listing-thumbs?
                   :enum-item (not listing-thumbs?))
           :id (str "component-shape-id-" (:id component))
           :draggable (not workspace-read-only?)
           :on-click on-component-click
           :on-double-click on-component-double-click
           :on-context-menu (on-context-menu (:id component))
           :on-drag-start on-component-drag-start
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over on-drag-over
           :on-drop on-drop}

     (when (and (some? root-shape) (some? component-container))
       [:*
        [:& component-svg {:root-shape root-shape
                           :objects (:objects component-container)}]
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
           (when @dragging?
             [:div.dragging])])])]))

(mf/defc components-group
  [{:keys [file prefix groups open-groups renaming listing-thumbs? selected-components on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-group on-ungroup on-context-menu
           selected-components-full]}]
  (let [group-open? (get open-groups prefix true)

        dragging? (mf/use-state false)

        selected-components-paths (->> selected-components-full
                                       (map #(:path %))
                                       (map #(if (nil? %) "" %)))

        on-drag-enter
        (mf/use-fn
         (mf/deps dragging? prefix selected-components-paths)
         (fn [event]
           (on-drag-enter-asset-group event dragging? prefix selected-components-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drop
        (mf/use-fn
         (mf/deps dragging? prefix selected-components-paths selected-components-full)
         (fn [event]
           (on-drop-asset-group event dragging? prefix selected-components-paths selected-components-full dwl/rename-component)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over on-drag-over
           :on-drop on-drop}
     [:& asset-group-title {:file-id (:id file)
                            :box :components
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
                                           (not @dragging?)))
                 :on-drag-enter on-drag-enter
                 :on-drag-leave on-drag-leave
                 :on-drag-over on-drag-over
                 :on-drop on-drop}
           (when @dragging?
             [:div.grid-placeholder "\u00A0"])
           (when (and
                  (empty? components)
                  (some? groups))
             [:div.drop-space])
           (for [component components]
             [:& components-item {:component component
                                  :key (:id component)
                                  :renaming renaming
                                  :listing-thumbs? listing-thumbs?
                                  :file file
                                  :selected-components selected-components
                                  :on-asset-click on-asset-click
                                  :on-context-menu on-context-menu
                                  :on-drag-start on-drag-start
                                  :on-group on-group
                                  :do-rename do-rename
                                  :cancel-rename cancel-rename
                                  :selected-components-full selected-components-full
                                  :selected-components-paths selected-components-paths}])])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& components-group {:file file
                                  :prefix (cph/merge-path-item prefix path-item)
                                  :groups content
                                  :open-groups open-groups
                                  :renaming renaming
                                  :listing-thumbs? listing-thumbs?
                                  :selected-components selected-components
                                  :on-asset-click on-asset-click
                                  :on-drag-start on-drag-start
                                  :do-rename do-rename
                                  :cancel-rename cancel-rename
                                  :on-rename-group on-rename-group
                                  :on-ungroup on-ungroup
                                  :on-context-menu on-context-menu
                                  :selected-components-full selected-components-full}]))])]))

(mf/defc components-box
  [{:keys [file local? components listing-thumbs? open? reverse-sort? open-groups selected-assets
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [input-ref                (mf/use-ref nil)
        state                    (mf/use-state {:renaming nil
                                                :component-id nil})

        menu-state               (mf/use-state auto-pos-menu-state)
        workspace-read-only?     (mf/use-ctx ctx/workspace-read-only?)

        selected-components      (:components selected-assets)
        selected-components-full (filter #(contains? selected-components (:id %)) components)
        multi-components?        (> (count selected-components) 1)
        multi-assets?            (or (seq (:graphics selected-assets))
                                     (seq (:colors selected-assets))
                                     (seq (:typographies selected-assets)))

        groups                   (group-assets components reverse-sort?)

        components-v2            (mf/use-ctx ctx/components-v2)

        add-component
        (mf/use-fn
         (fn []
           #(st/emit! (dwl/set-assets-box-open (:id file) :components true))
           (dom/click (mf/ref-val input-ref))))

        on-file-selected
        (mf/use-fn
         (mf/deps file)
         (fn [blobs]
           (let [params {:file-id (:id file)
                         :blobs (seq blobs)}]
             (st/emit! (dwm/upload-media-components params)
                       (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                              :asset-type "components"})))))

        on-duplicate
        (mf/use-fn
         (mf/deps @state)
         (fn []
           (let [undo-id (js/Symbol)]
             (if (empty? selected-components)
               (st/emit! (dwl/duplicate-component (:id file) (:component-id @state)))
               (do
                 (st/emit! (dwu/start-undo-transaction undo-id))
                 (apply st/emit! (map (partial dwl/duplicate-component (:id file)) selected-components))
                 (st/emit! (dwu/commit-undo-transaction undo-id)))))))

        on-delete
        (mf/use-fn
         (mf/deps @state file multi-components? multi-assets?)
         (fn []
           (let [undo-id (js/Symbol)]
             (if (or multi-components? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwu/start-undo-transaction undo-id)
                       (dwl/delete-component {:id (:component-id @state)})
                       (dwl/sync-file (:id file) (:id file) :components (:component-id @state))
                       (dwu/commit-undo-transaction undo-id))))))

        on-rename
        (mf/use-fn
         (mf/deps @state)
         (fn []
           (swap! state assoc :renaming (:component-id @state))))

        do-rename
        (mf/use-fn
         (mf/deps @state)
         (fn [new-name]
           (st/emit! (dwl/rename-component (:renaming @state) new-name))
           (swap! state assoc :renaming nil)))

        cancel-rename
        (mf/use-fn
         (fn []
           (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-fn
         (mf/deps selected-components on-clear-selection workspace-read-only?)
         (fn [component-id]
           (fn [event]
             (when (and local? (not workspace-read-only?))
               (when-not (contains? selected-components component-id)
                 (on-clear-selection))
               (swap! state assoc :component-id component-id)
               (swap! menu-state #(open-auto-pos-menu % event))))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-auto-pos-menu)))

        create-group
        (mf/use-fn
         (mf/deps components selected-components on-clear-selection)
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> components
                         (filter #(if multi-components?
                                    (contains? selected-components (:id %))
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
             (apply st/emit!
                    (->> components
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/rename-component
                                (:id %)
                                (rename-group % path last-path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps components selected-components)
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
             (apply st/emit!
                    (->> components
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/rename-component
                                (:id %)
                                (ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-drag-start
        (mf/use-fn
         (fn [component event]
           (dnd/set-data! event "penpot/component" {:file-id (:id file)
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
               (st/emit! (dw/go-to-main-instance main-instance-page main-instance-id))))))]

    [:& asset-section {:file-id (:id file)
                       :title (tr "workspace.assets.components")
                       :box :components
                       :assets-count (count components)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when (and components-v2 (not workspace-read-only?))
          [:div.assets-button {:on-click add-component}
           i/plus
           [:& file-uploader {:accept cm/str-image-types
                              :multi true
                              :ref input-ref
                              :on-selected on-file-selected}]])])

     [:& asset-section-block {:role :content}
      [:& components-group {:file file
                            :prefix ""
                            :groups groups
                            :open-groups open-groups
                            :renaming (:renaming @state)
                            :listing-thumbs? listing-thumbs?
                            :selected-components selected-components
                            :on-asset-click (partial on-asset-click groups)
                            :on-drag-start on-drag-start
                            :do-rename do-rename
                            :cancel-rename cancel-rename
                            :on-rename-group on-rename-group
                            :on-group on-group
                            :on-ungroup on-ungroup
                            :on-context-menu on-context-menu
                            :selected-components-full selected-components-full}]
      (when local?
        [:& auto-pos-menu
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


;; ---- Graphics box ----

(mf/defc graphics-item
  [{:keys [object renaming listing-thumbs? selected-objects
           on-asset-click on-context-menu on-drag-start do-rename cancel-rename
           selected-graphics-full selected-graphics-paths]}]
  (let [item-ref             (mf/use-ref)
        visible?             (h/use-visible item-ref :once? true)
        dragging?            (mf/use-state false)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        on-drop
        (mf/use-fn
         (mf/deps object dragging? selected-objects selected-graphics-full selected-graphics-paths)
         (fn [event]
           (on-drop-asset event object dragging? selected-objects selected-graphics-full
                          selected-graphics-paths dwl/rename-media)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drag-enter
        (mf/use-fn
         (mf/deps object dragging? selected-objects selected-graphics-paths)
         (fn [event]
           (on-drag-enter-asset event object dragging? selected-objects selected-graphics-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-grahic-drag-start
        (mf/use-fn
         (mf/deps object selected-objects item-ref on-drag-start workspace-read-only?)
         (fn [event]
           (if workspace-read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event object selected-objects item-ref :graphics on-drag-start))))]

    [:div {:ref item-ref
           :class-name (dom/classnames
                        :selected (contains? selected-objects (:id object))
                        :grid-cell listing-thumbs?
                        :enum-item (not listing-thumbs?))
           :draggable (not workspace-read-only?)
           :on-click #(on-asset-click % (:id object) nil)
           :on-context-menu (on-context-menu (:id object))
           :on-drag-start on-grahic-drag-start
           :on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over on-drag-over
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
           (when @dragging?
             [:div.dragging])])])]))

(mf/defc graphics-group
  [{:keys [file-id prefix groups open-groups renaming listing-thumbs? selected-objects on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-ungroup
           on-context-menu selected-graphics-full]}]
  (let [group-open? (get open-groups prefix true)

        dragging? (mf/use-state false)

        selected-graphics-paths (->> selected-graphics-full
                                     (map #(:path %))
                                     (map #(if (nil? %) "" %)))


        on-drag-enter
        (mf/use-fn
         (mf/deps dragging? prefix selected-graphics-paths)
         (fn [event]
           (on-drag-enter-asset-group event dragging? prefix selected-graphics-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drop
        (mf/use-fn
         (mf/deps dragging? prefix selected-graphics-paths selected-graphics-full)
         (fn [event]
           (on-drop-asset-group event dragging? prefix selected-graphics-paths selected-graphics-full dwl/rename-media)))]


    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over on-drag-over
           :on-drop on-drop}
     [:& asset-group-title {:file-id file-id
                            :box :graphics
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
                                           (not @dragging?)))
                 :on-drag-enter on-drag-enter
                 :on-drag-leave on-drag-leave
                 :on-drag-over on-drag-over
                 :on-drop on-drop}
           (when @dragging?
             [:div.grid-placeholder "\u00A0"])
           (when (and
                  (empty? objects)
                  (some? groups))
             [:div.drop-space])
           (for [object objects]
             [:& graphics-item {:key (:id object)
                                :object object
                                :renaming renaming
                                :listing-thumbs? listing-thumbs?
                                :selected-objects selected-objects
                                :on-asset-click on-asset-click
                                :on-context-menu on-context-menu
                                :on-drag-start on-drag-start
                                :do-rename do-rename
                                :cancel-rename cancel-rename
                                :selected-graphics-full selected-graphics-full
                                :selected-graphics-paths selected-graphics-paths}])])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& graphics-group {:file-id file-id
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
                                :selected-graphics-full selected-graphics-full
                                :selected-graphics-paths selected-graphics-paths}]))])]))

(mf/defc graphics-box
  [{:keys [file-id project-id local? objects listing-thumbs? open? open-groups selected-assets reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [input-ref            (mf/use-ref nil)
        state                (mf/use-state {:renaming nil
                                            :object-id nil})

        menu-state           (mf/use-state auto-pos-menu-state)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        selected-objects     (:graphics selected-assets)
        selected-graphics-full (filter #(contains? selected-objects (:id %)) objects)
        multi-objects?       (> (count selected-objects) 1)
        multi-assets?        (or (seq (:components selected-assets))
                                 (seq (:colors selected-assets))
                                 (seq (:typographies selected-assets)))
        objects (->> objects
                     (map dwl/extract-path-if-missing))


        groups (group-assets objects reverse-sort?)

        components-v2   (mf/use-ctx ctx/components-v2)
        team-id (mf/use-ctx ctx/current-team-id)

        add-graphic
        (mf/use-fn
         (fn []
           #(st/emit! (dwl/set-assets-box-open file-id :graphics true))
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
         (mf/deps @state)
         (fn []
           (swap! state assoc :renaming (:object-id @state))))

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
         (mf/deps selected-objects on-clear-selection workspace-read-only?)
         (fn [object-id]
           (fn [event]
             (when (and local? (not workspace-read-only?))
               (when-not (contains? selected-objects object-id)
                 (on-clear-selection))
               (swap! state assoc :object-id object-id)
               (swap! menu-state #(open-auto-pos-menu % event))))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-auto-pos-menu)))

        create-group
        (mf/use-fn
         (mf/deps objects selected-objects on-clear-selection)
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> objects
                         (filter #(if multi-objects?
                                    (contains? selected-objects (:id %))
                                    (= (:object-id @state) (:id %))))
                         (map #(dwl/rename-media
                                (:id %)
                                (add-group % group-name)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        rename-group
        (mf/use-fn
         (mf/deps objects)
         (fn [path last-path]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> objects
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/rename-media
                                (:id %)
                                (rename-group % path last-path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps objects selected-objects)
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
             (apply st/emit!
                    (->> objects
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/rename-media
                                (:id %)
                                (ungroup % path)))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-drag-start
        (mf/use-fn
         (fn [{:keys [name id mtype]} event]
           (dnd/set-data! event "text/asset-id" (str id))
           (dnd/set-data! event "text/asset-name" name)
           (dnd/set-data! event "text/asset-type" mtype)
           (dnd/set-allowed-effect! event "move")))]

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.graphics")
                       :box :graphics
                       :assets-count (count objects)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when (and (not components-v2) (not workspace-read-only?))
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
                          :selected-objects selected-objects
                          :on-asset-click (partial on-asset-click groups)
                          :on-drag-start on-drag-start
                          :do-rename do-rename
                          :cancel-rename cancel-rename
                          :on-rename-group on-rename-group
                          :on-ungroup on-ungroup
                          :on-context-menu on-context-menu
                          :selected-graphics-full selected-graphics-full}]
      (when local?
        [:& auto-pos-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-objects? multi-assets?)
                      [(tr "workspace.assets.rename") on-rename])
                    [(tr "workspace.assets.delete") on-delete]
                    (when-not multi-assets?
                      [(tr "workspace.assets.group") on-group])]}])]]))


;; ---- Colors box ----

(mf/defc color-item
  [{:keys [color local? file-id selected-colors multi-colors? multi-assets?
           on-asset-click on-assets-delete on-clear-selection on-group
           selected-colors-full selected-colors-paths move-color] :as props}]
  (let [item-ref             (mf/use-ref)
        dragging?            (mf/use-state false)
        rename?              (= (:color-for-rename @refs/workspace-local) (:id color))
        input-ref            (mf/use-ref)
        state                (mf/use-state {:editing rename?})

        menu-state           (mf/use-state auto-pos-menu-state)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        default-name (cond
                       (:gradient color) (bc/gradient-type->string (get-in color [:gradient :type]))
                       (:color color) (:color color)
                       :else (:value color))

        apply-color
        (fn [event]
          (st/emit! (dc/apply-color-from-palette (merge uc/empty-color color) (kbd/alt? event))))

        rename-color
        (fn [name]
          (st/emit! (dwl/rename-color file-id (:id color) name)))

        edit-color
        (fn [new-color]
          (let [old-data (-> (select-keys color [:id :file-id])
                             (assoc :name (cph/merge-path-item (:path color) (:name color))))
                updated-color (merge new-color old-data)]
            (st/emit! (dwl/update-color updated-color file-id))))

        delete-color
        (mf/use-fn
         (mf/deps @state multi-colors? multi-assets? file-id)
         (fn []
           (if (or multi-colors? multi-assets?)
             (on-assets-delete)
             (let [undo-id (js/Symbol)]
               (st/emit! (dwu/start-undo-transaction undo-id)
                         (dwl/delete-color color)
                         (dwl/sync-file file-id file-id :colors (:id color))
                         (dwu/commit-undo-transaction undo-id))))))

        rename-color-clicked
        (fn [event]
          (when (and local? (not workspace-read-only?))
            (dom/prevent-default event)
            (swap! state assoc :editing true)))

        input-blur
        (fn [event]
          (let [target (dom/event->target event)
                name (dom/get-value target)]
            (rename-color name)
            (st/emit! dwl/clear-color-for-rename)
            (swap! state assoc :editing false)))

        input-key-down
        (fn [event]
          (when (kbd/esc? event)
            (st/emit! dwl/clear-color-for-rename)
            (swap! state assoc :editing false))
          (when (kbd/enter? event)
            (input-blur event)))

        edit-color-clicked
        (fn [event]
          (modal/show! :colorpicker
                       {:x (.-clientX event)
                        :y (.-clientY event)
                        :on-accept edit-color
                        :data color
                        :position :right}))

        on-context-menu
        (mf/use-fn
         (mf/deps color selected-colors on-clear-selection workspace-read-only?)
         (fn [event]
           (when (and local? (not workspace-read-only?))
             (when-not (contains? selected-colors (:id color))
               (on-clear-selection))
             (swap! menu-state #(open-auto-pos-menu % event)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-auto-pos-menu)))
        on-drop
        (mf/use-fn
         (mf/deps color dragging? selected-colors selected-colors-full selected-colors-paths move-color)
         (fn [event]
           (on-drop-asset event color dragging? selected-colors selected-colors-full
                          selected-colors-paths move-color)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drag-enter
        (mf/use-fn
         (mf/deps color dragging? selected-colors selected-colors-paths)
         (fn [event]
           (on-drag-enter-asset event color dragging? selected-colors selected-colors-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-color-drag-start
        (mf/use-fn
         (mf/deps color selected-colors item-ref workspace-read-only?)
         (fn [event]
           (if workspace-read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event color selected-colors item-ref :colors identity))))]

    (mf/use-effect
     (mf/deps (:editing @state))
     #(when (:editing @state)
        (let [input (mf/ref-val input-ref)]
          (dom/select-text! input))
        nil))

    [:div.asset-list-item {:class-name (dom/classnames
                                        :selected (contains? selected-colors (:id color)))
                           :on-context-menu on-context-menu
                           :on-click (when-not (:editing @state)
                                       #(on-asset-click % (:id color) apply-color))
                           :ref item-ref
                           :draggable (and (not workspace-read-only?) (not (:editing @state)))
                           :on-drag-start on-color-drag-start
                           :on-drag-enter on-drag-enter
                           :on-drag-leave on-drag-leave
                           :on-drag-over on-drag-over
                           :on-drop on-drop}
     [:& bc/color-bullet {:color color}]

     (if (:editing @state)
       [:input.element-name
        {:type "text"
         :ref input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :default-value (cph/merge-path-item (:path color) (:name color))}]

       [:div.name-block {:on-double-click rename-color-clicked}
        (:name color)
        (when-not (= (:name color) default-name)
          [:span default-name])])
     (when local?
       [:& auto-pos-menu
        {:on-close on-close-menu
         :state @menu-state
         :options [(when-not (or multi-colors? multi-assets?)
                     [(tr "workspace.assets.rename") rename-color-clicked])
                   (when-not (or multi-colors? multi-assets?)
                     [(tr "workspace.assets.edit") edit-color-clicked])
                   [(tr "workspace.assets.delete") delete-color]
                   (when-not multi-assets?
                     [(tr "workspace.assets.group") (on-group (:id color))])]}])
     (when @dragging?
       [:div.dragging])]))

(mf/defc colors-group
  [{:keys [file-id prefix groups open-groups local? selected-colors
           multi-colors? multi-assets? on-asset-click on-assets-delete
           on-clear-selection on-group on-rename-group on-ungroup colors
           selected-colors-full]}]
  (let [group-open? (get open-groups prefix true)
        dragging? (mf/use-state false)

        selected-colors-paths (->> selected-colors-full
                                   (map #(:path %))
                                   (map #(if (nil? %) "" %)))


        move-color (partial dwl/rename-color file-id)

        on-drag-enter
        (mf/use-fn
         (mf/deps dragging? prefix selected-colors-paths)
         (fn [event]
           (on-drag-enter-asset-group event dragging? prefix selected-colors-paths)))

        on-drag-leave (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drop
        (mf/use-fn
         (mf/deps dragging? prefix selected-colors-paths selected-colors-full move-color)
         (fn [event]
           (on-drop-asset-group event dragging? prefix selected-colors-paths selected-colors-full move-color)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over on-drag-over
           :on-drop on-drop}
     [:& asset-group-title {:file-id file-id
                            :box :colors
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [colors (get groups "" [])]
          [:div.asset-list {:on-drag-enter on-drag-enter
                            :on-drag-leave on-drag-leave
                            :on-drag-over on-drag-over
                            :on-drop on-drop}
           (when @dragging?
             [:div.grid-placeholder "\u00A0"])
           (when (and
                  (empty? colors)
                  (some? groups))
             [:div.drop-space])
           (for [color colors]
             (let [color (cond-> color
                           (:value color) (assoc :color (:value color) :opacity 1)
                           (:value color) (dissoc :value)
                           true (assoc :file-id file-id))]
               [:& color-item {:key (:id color)
                               :color color
                               :file-id file-id
                               :local? local?
                               :selected-colors selected-colors
                               :multi-colors? multi-colors?
                               :multi-assets? multi-assets?
                               :on-asset-click on-asset-click
                               :on-assets-delete on-assets-delete
                               :on-clear-selection on-clear-selection
                               :on-group on-group
                               :colors colors
                               :selected-colors-full selected-colors-full
                               :selected-colors-paths selected-colors-paths
                               :move-color move-color}]))])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& colors-group {:file-id file-id
                              :prefix (cph/merge-path-item prefix path-item)
                              :key (str "group-" path-item)
                              :groups content
                              :open-groups open-groups
                              :local? local?
                              :selected-colors selected-colors
                              :multi-colors? multi-colors?
                              :multi-assets? multi-assets?
                              :on-asset-click on-asset-click
                              :on-assets-delete on-assets-delete
                              :on-clear-selection on-clear-selection
                              :on-group on-group
                              :on-rename-group on-rename-group
                              :on-ungroup on-ungroup
                              :colors colors
                              :selected-colors-full selected-colors-full}]))])]))

(mf/defc colors-box
  [{:keys [file-id local? colors open? open-groups selected-assets reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [selected-colors      (:colors selected-assets)
        selected-colors-full (filter #(contains? selected-colors (:id %)) colors)
        multi-colors?        (> (count selected-colors) 1)
        multi-assets?        (or (seq (:components selected-assets))
                                 (seq (:graphics selected-assets))
                                 (seq (:typographies selected-assets)))

        groups               (group-assets colors reverse-sort?)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        add-color
        (mf/use-fn
         (mf/deps file-id)
         (fn [value _opacity]
           (st/emit! (dwl/add-color value))))

        add-color-clicked
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (st/emit! (dwl/set-assets-box-open file-id :colors true)
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
         (mf/deps colors selected-colors on-clear-selection file-id)
         (fn [color-id]
           (fn [group-name]
             (on-clear-selection)
             (let [undo-id (js/Symbol)]
               (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> colors
                         (filter #(if multi-colors?
                                    (contains? selected-colors (:id %))
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
             (apply st/emit!
                    (->> colors
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/update-color
                                (assoc % :name
                                       (rename-group % path last-path))
                                file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps colors selected-colors)
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
             (st/emit! (dwu/commit-undo-transaction undo-id)))))]

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.colors")
                       :box :colors
                       :assets-count (count colors)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when-not workspace-read-only?
          [:div.assets-button {:on-click add-color-clicked}
           i/plus])])

     [:& asset-section-block {:role :content}
      [:& colors-group {:file-id file-id
                        :prefix ""
                        :groups groups
                        :open-groups open-groups
                        :local? local?
                        :selected-colors selected-colors
                        :multi-colors? multi-colors?
                        :multi-assets? multi-assets?
                        :on-asset-click (partial on-asset-click groups)
                        :on-assets-delete on-assets-delete
                        :on-clear-selection on-clear-selection
                        :on-group on-group
                        :on-rename-group on-rename-group
                        :on-ungroup on-ungroup
                        :colors colors
                        :selected-colors-full selected-colors-full}]]]))

;; ---- Typography box ----

(mf/defc typography-item
  [{:keys [typography file local? handle-change selected-typographies apply-typography
           editing-id local-data on-asset-click on-context-menu selected-typographies-full
           selected-typographies-paths move-typography] :as props}]
  (let [item-ref             (mf/use-ref)
        dragging?            (mf/use-state false)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        editing?             (= editing-id (:id typography))
        open?                (mf/use-state editing?)
        on-drop
        (mf/use-fn
         (mf/deps typography dragging? selected-typographies selected-typographies-full selected-typographies-paths move-typography)
         (fn [event]
           (on-drop-asset event typography dragging? selected-typographies selected-typographies-full
                          selected-typographies-paths move-typography)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drag-enter
        (mf/use-fn
         (mf/deps typography dragging? selected-typographies selected-typographies-paths)
         (fn [event]
           (on-drag-enter-asset event typography dragging? selected-typographies selected-typographies-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-typography-drag-start
        (mf/use-fn
         (mf/deps typography selected-typographies item-ref workspace-read-only?)
         (fn [event]
           (if workspace-read-only?
             (dom/prevent-default event)
             (on-asset-drag-start event typography selected-typographies item-ref :typographies identity))))]

    [:div.typography-container {:ref item-ref
                                :draggable (and (not workspace-read-only?) (not @open?))
                                :on-drag-start on-typography-drag-start
                                :on-drag-enter on-drag-enter
                                :on-drag-leave on-drag-leave
                                :on-drag-over on-drag-over
                                :on-drop on-drop}
     [:& typography-entry
      {:key (:id typography)
       :typography typography
       :file file
       :local? local?
       :on-context-menu #(on-context-menu (:id typography) %)
       :on-change #(handle-change typography %)
       :selected? (contains? selected-typographies (:id typography))
       :on-click  #(on-asset-click % (:id typography)
                                   (partial apply-typography typography))
       :editing? editing?
       :focus-name? (= (:rename-typography local-data) (:id typography))
       :open? open?}]
     (when @dragging?
       [:div.dragging])]))

(mf/defc typographies-group
  [{:keys [file-id prefix groups open-groups file local? selected-typographies local-data
           editing-id on-asset-click handle-change apply-typography on-rename-group
           on-ungroup on-context-menu selected-typographies-full]}]
  (let [group-open? (get open-groups prefix true)
        dragging? (mf/use-state false)

        selected-typographies-paths (->> selected-typographies-full
                                         (map #(:path %))
                                         (map #(if (nil? %) "" %)))

        move-typography (partial dwl/rename-typography file-id)

        on-drag-enter
        (mf/use-fn
         (mf/deps dragging? prefix selected-typographies-paths)
         (fn [event]
           (on-drag-enter-asset-group event dragging? prefix selected-typographies-paths)))

        on-drag-leave
        (mf/use-fn
         (mf/deps dragging?)
         (fn [event]
           (on-drag-leave-asset event dragging?)))

        on-drag-over (mf/use-fn #(dom/prevent-default %))

        on-drop
        (mf/use-fn
         (mf/deps dragging? prefix selected-typographies-paths selected-typographies-full move-typography)
         (fn [event]
           (on-drop-asset-group event dragging? prefix selected-typographies-paths selected-typographies-full move-typography)))]

    [:div {:on-drag-enter on-drag-enter
           :on-drag-leave on-drag-leave
           :on-drag-over on-drag-over
           :on-drop on-drop}
     [:& asset-group-title {:file-id file-id
                            :box :typographies
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [typographies (get groups "" [])]
          [:div.asset-list {:on-drag-enter on-drag-enter
                            :on-drag-leave on-drag-leave
                            :on-drag-over on-drag-over
                            :on-drop on-drop}
           (when @dragging?
             [:div.grid-placeholder "\u00A0"])
           (when (and
                  (empty? typographies)
                  (some? groups))
             [:div.drop-space])
           (for [typography typographies]
             [:& typography-item {:typography typography
                                  :key (dm/str (:id typography))
                                  :file file
                                  :local? local?
                                  :handle-change handle-change
                                  :selected-typographies selected-typographies
                                  :apply-typography apply-typography
                                  :editing-id editing-id
                                  :local-data local-data
                                  :on-asset-click on-asset-click
                                  :on-context-menu on-context-menu
                                  :selected-typographies-full selected-typographies-full
                                  :selected-typographies-paths selected-typographies-paths
                                  :move-typography move-typography}])])

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& typographies-group {:file-id file-id
                                    :prefix (cph/merge-path-item prefix path-item)
                                    :key (dm/str path-item)
                                    :groups content
                                    :open-groups open-groups
                                    :file file
                                    :local? local?
                                    :selected-typographies selected-typographies
                                    :editing-id editing-id
                                    :local-data local-data
                                    :on-asset-click on-asset-click
                                    :handle-change handle-change
                                    :apply-typography apply-typography
                                    :on-rename-group on-rename-group
                                    :on-ungroup on-ungroup
                                    :on-context-menu on-context-menu
                                    :selected-typographies-full selected-typographies-full}]))])]))

(mf/defc typographies-box
  [{:keys [file file-id local? typographies open? open-groups selected-assets reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [state (mf/use-state {:detail-open? false
                             :id nil})

        local-data (mf/deref typography-data)
        menu-state (mf/use-state auto-pos-menu-state)
        typographies (->> typographies
                          (map dwl/extract-path-if-missing))

        groups     (group-assets typographies reverse-sort?)

        selected-typographies (:typographies selected-assets)
        selected-typographies-full (filter #(contains? selected-typographies (:id %)) typographies)
        multi-typographies?   (> (count selected-typographies) 1)
        multi-assets?         (or (seq (:components selected-assets))
                                  (seq (:graphics selected-assets))
                                  (seq (:colors selected-assets)))
        workspace-read-only?  (mf/use-ctx ctx/workspace-read-only?)

        text-shapes (->>
                     (mf/deref refs/selected-objects)
                     (filter #(= (:type %) :text)))

        state-map (mf/deref refs/workspace-editor-state)
        text-shape (first text-shapes)
        editor-state (get state-map (:id text-shape))

        text-values (dwt/current-text-values
                      {:editor-state editor-state
                       :shape text-shape
                       :attrs dwt/text-attrs})

        multiple? (or (> 1 (count text-shape))
                    (->> text-values vals (d/seek #(= % :multiple))))

        values (-> (d/without-nils text-values)
                   (select-keys
                     (d/concat-vec dwt/text-font-attrs
                       dwt/text-spacing-attrs
                       dwt/text-transform-attrs)))

        typography-id (uuid/next)
        typography (-> (if multiple?
                         txt/default-typography
                         (merge txt/default-typography values))
                       (generate-typography-name)
                       (assoc :id typography-id))

        add-typography
        (mf/use-fn
         (mf/deps file-id typography)
         (fn [_]
           (when (not multiple?)
             (st/emit! (dwt/update-attrs (:id text-shape) {:typography-ref-id typography-id
                                                           :typography-ref-file file-id})))

           (st/emit! (dwl/add-typography typography)
             (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                    :asset-type "typography"}))))
        
        handle-change
        (mf/use-fn
         (mf/deps file-id)
         (fn [typography changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        apply-typography
        (fn [typography _event]
          (let [ids (wsh/lookup-selected @st/state)
                attrs (merge
                       {:typography-ref-file file-id
                        :typography-ref-id (:id typography)}
                       (dissoc typography :id :name))]
            (run! #(st/emit!
                    (dwt/update-text-attrs
                     {:id %
                      :editor (get @refs/workspace-editor-state %)
                      :attrs attrs}))
                  ids)))

        create-group
        (mf/use-fn
         (mf/deps typographies selected-typographies on-clear-selection file-id)
         (fn [group-name]
           (on-clear-selection)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit!
                    (->> typographies
                         (filter #(if multi-typographies?
                                    (contains? selected-typographies (:id %))
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
             (apply st/emit!
                    (->> typographies
                         (filter #(str/starts-with? (:path %) path))
                         (map #(dwl/update-typography
                                (assoc % :name
                                       (rename-group % path last-path))
                                file-id))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))

        on-group
        (mf/use-fn
         (mf/deps typographies selected-typographies)
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
         (mf/deps selected-typographies on-clear-selection workspace-read-only?)
         (fn [id event]
           (when (and local? (not workspace-read-only?))
             (when-not (contains? selected-typographies id)
               (on-clear-selection))
             (swap! state assoc :id id)
             (swap! menu-state #(open-auto-pos-menu % event)))))

        on-close-menu
        (mf/use-fn
         (fn []
           (swap! menu-state close-auto-pos-menu)))

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
                       (:edit-typography local-data))]

    (mf/use-effect
     (mf/deps local-data)
     (fn []
       (when (:rename-typography local-data)
         (st/emit! #(update % :workspace-global dissoc :rename-typography)))
       (when (:edit-typography local-data)
         (st/emit! #(update % :workspace-global dissoc :edit-typography)))))

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.typography")
                       :box :typographies
                       :assets-count (count typographies)
                       :open? open?}
     (when local?
       [:& asset-section-block {:role :title-button}
        (when-not workspace-read-only?
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
                              :selected-typographies selected-typographies
                              :editing-id editing-id
                              :local-data local-data
                              :on-asset-click (partial on-asset-click groups)
                              :handle-change handle-change
                              :apply-typography apply-typography
                              :on-rename-group on-rename-group
                              :on-ungroup on-ungroup
                              :on-context-menu on-context-menu
                              :selected-typographies-full selected-typographies-full}]

      (when local?
        [:& auto-pos-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-typographies? multi-assets?)
                      [(tr "workspace.assets.rename") handle-rename-typography-clicked])
                    (when-not (or multi-typographies? multi-assets?)
                      [(tr "workspace.assets.edit") handle-edit-typography-clicked])
                    [(tr "workspace.assets.delete") handle-delete-typography]
                    (when-not multi-assets?
                      [(tr "workspace.assets.group") on-group])]}])]]))


;; --- Assets toolbox ----

(defn file-colors-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-data state)]
                 (if (= (:id wfile) id)
                   (vals (get wfile :colors))
                   (vals (get-in state [:workspace-libraries id :data :colors])))))
             st/state =))

(defn file-media-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-data state)]
                 (if (= (:id wfile) id)
                   (vals (get wfile :media))
                   (vals (get-in state [:workspace-libraries id :data :media])))))
             st/state =))

(defn file-components-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-data state)]
                 (if (= (:id wfile) id)
                   (ctkl/components-seq wfile)
                   (ctkl/components-seq (get-in state [:workspace-libraries id :data])))))
             st/state =))

(defn file-typography-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-data state)]
                 (if (= (:id wfile) id)
                   (vals (get wfile :typographies))
                   (vals (get-in state [:workspace-libraries id :data :typographies])))))
             st/state =))

(defn make-open-file-ref
  [id]
  (mf/with-memo [id]
    (-> (l/in [:assets-files-open id])
        (l/derived refs/workspace-global))))

(defn apply-filters
  [coll filters reverse-sort?]
  (let [comp-fn (if reverse-sort? > <)]
    (->> coll
         (filter (fn [item]
                   (or (matches-search (:name item "!$!") (:term filters))
                       (matches-search (:value item "!$!") (:term filters)))))
                                        ; Sort by folder order, but putting all "root" items always first,
                                        ; independently of sort order.
         (sort-by #(str/lower (cph/merge-path-item (if (empty? (:path %))
                                                     (if reverse-sort? "z" "a")
                                                     (:path %))
                                                   (:name %)))
                  comp-fn))))

(mf/defc file-library
  [{:keys [file local? default-open? filters] :as props}]
  (let [open-file            (mf/deref (make-open-file-ref (:id file)))
        open?                (-> open-file
                                 :library
                                 (d/nilv default-open?))
        open-box?            (fn [box]
                               (-> open-file
                                   box
                                   (d/nilv true)))
        open-groups          (fn [box]
                               (-> open-file
                                   :groups
                                   box
                                   (d/nilv {})))
        shared?              (:is-shared file)
        router               (mf/deref refs/router)

        reverse-sort?      (mf/deref refs/file-library-reverse-sort?)
        reverse-sort?      (if (nil? reverse-sort?) false reverse-sort?)

        listing-thumbs?      (mf/deref refs/file-library-listing-thumbs?)
        listing-thumbs?      (if (nil? listing-thumbs?) true listing-thumbs?)

        selected-assets      (mf/deref refs/selected-assets)

        selected-count       (+ (count (:components selected-assets))
                                (count (:graphics selected-assets))
                                (count (:colors selected-assets))
                                (count (:typographies selected-assets)))

        components-v2        (mf/use-ctx ctx/components-v2)

        toggle-open          #(st/emit! (dwl/set-assets-box-open (:id file) :library (not open?)))

        url                  (rt/resolve router :workspace
                                         {:project-id (:project-id file)
                                          :file-id (:id file)}
                                         {:page-id (get-in file [:data :pages 0])})

        colors-ref           (mf/use-memo (mf/deps (:id file)) #(file-colors-ref (:id file)))
        colors               (apply-filters (mf/deref colors-ref) filters reverse-sort?)

        typography-ref       (mf/use-memo (mf/deps (:id file)) #(file-typography-ref (:id file)))
        typographies         (apply-filters (mf/deref typography-ref) filters reverse-sort?)

        media-ref            (mf/use-memo (mf/deps (:id file)) #(file-media-ref (:id file)))
        media                (apply-filters (mf/deref media-ref) filters reverse-sort?)

        components-ref       (mf/use-memo (mf/deps (:id file)) #(file-components-ref (:id file)))
        components           (apply-filters (mf/deref components-ref) filters reverse-sort?)

        toggle-sort
        (mf/use-fn
         (mf/deps reverse-sort?)
         (fn [_]
           (st/emit! (dw/set-file-library-reverse-sort (not reverse-sort?)))))

        toggle-listing
        (mf/use-fn
         (mf/deps listing-thumbs?)
         (fn [_]
           (st/emit! (dw/set-file-library-listing-thumbs (not listing-thumbs?)))))

        extend-selected-assets
        (mf/use-fn
         (mf/deps selected-assets)
         (fn [asset-type asset-groups asset-id]
           (letfn [(flatten-groups
                     [groups]
                     (reduce concat [(get groups "" [])
                                     (into []
                                           (->> (filter #(seq (first %)) groups)
                                                (map second)
                                                (mapcat flatten-groups)))]))]
             (let [selected-assets-type (get selected-assets asset-type)
                   count-assets (count selected-assets-type)]
               (if (<= count-assets 0)
                 (st/emit! (dw/select-single-asset asset-id asset-type))
                 (let [all-assets   (flatten-groups asset-groups)
                       clicked-idx  (d/index-of-pred all-assets #(= (:id %) asset-id))
                       components (get selected-assets asset-type)

                       first-idx    (first (sort (map (fn [asset] (d/index-of-pred all-assets #(= (:id %) asset))) components)))
                       selected-idx (vector  first-idx clicked-idx)
                       min-idx      (apply min (conj selected-idx clicked-idx))
                       max-idx      (apply max (conj selected-idx clicked-idx))
                       values (->> all-assets
                                   d/enumerate
                                   (filter #(<= min-idx (first %) max-idx))
                                   (map #(-> % second :id))
                                   set)]

                   (st/emit! (dw/select-assets values asset-type))))))))

        unselect-all
        (mf/use-fn
         (fn []
           (st/emit! (dw/unselect-all-assets))))

        on-asset-click
        (mf/use-fn
         (mf/deps selected-assets)
         (fn [asset-type asset-groups event asset-id default-click]
           (cond
             (kbd/mod? event)
             (do
               (dom/stop-propagation event)
               (st/emit! (dw/toggle-selected-assets asset-id asset-type)))

             (kbd/shift? event)
             (do
               (dom/stop-propagation event)
               (extend-selected-assets asset-type asset-groups asset-id))

             :else
             (when default-click
               (default-click event)))))

        on-assets-delete
        (mf/use-fn
         (mf/deps selected-assets)
         (fn []
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id))
             (apply st/emit! (map #(dwl/delete-component {:id %})
                                  (:components selected-assets)))
             (apply st/emit! (map #(dwl/delete-media {:id %})
                                  (:graphics selected-assets)))
             (apply st/emit! (map #(dwl/delete-color {:id %})
                                  (:colors selected-assets)))
             (apply st/emit! (map #(dwl/delete-typography %)
                                  (:typographies selected-assets)))
             (when (or (d/not-empty? (:components selected-assets))
                       (d/not-empty? (:colors selected-assets))
                       (d/not-empty? (:typographies selected-assets)))
               (st/emit! (dwl/sync-file (:id file) (:id file))))
             (st/emit! (dwu/commit-undo-transaction undo-id)))))]

    [:div.tool-window {:on-context-menu #(dom/prevent-default %)
                       :on-click unselect-all}
     [:div.tool-window-bar.library-bar
      {:on-click toggle-open}
      [:div.collapse-library
       {:class (dom/classnames :open open?)}
       i/arrow-slide]

      (if local?
        [:*
         [:span (:name file) " (" (tr "workspace.assets.local-library") ")"]
         (when shared?
           [:span.tool-badge (tr "workspace.assets.shared")])]
        [:*
         [:span (:name file)]
         [:span.tool-link.tooltip.tooltip-left {:alt "Open library file"}
          [:a {:href (str "#" url)
               :target "_blank"
               :on-click dom/stop-propagation}
           i/chain]]])]

     (when open?
       (let [show-components?   (and (or (= (:box filters) :all)
                                         (= (:box filters) :components))
                                     (or (> (count components) 0)
                                         (str/empty? (:term filters))))
             show-graphics?     (and (or (= (:box filters) :all)
                                         (= (:box filters) :graphics))
                                     (or (> (count media) 0)
                                         (and (str/empty? (:term filters))
                                              (not components-v2))))
             show-colors?       (and (or (= (:box filters) :all)
                                         (= (:box filters) :colors))
                                     (or (> (count colors) 0)
                                         (str/empty? (:term filters))))
             show-typography?   (and (or (= (:box filters) :all)
                                         (= (:box filters) :typographies))
                                     (or (> (count typographies) 0)
                                         (str/empty? (:term filters))))]
         [:div.tool-window-content
          [:div.listing-options
           (when (> selected-count 0)
             [:span.selected-count
              (tr "workspace.assets.selected-count" (i18n/c selected-count))])
           [:div.listing-option-btn.first {:on-click toggle-sort}
            (if reverse-sort?
              i/sort-ascending
              i/sort-descending)]
           [:div.listing-option-btn {:on-click toggle-listing}
            (if listing-thumbs?
              i/listing-enum
              i/listing-thumbs)]]

          (when show-components?
            [:& components-box {:file file
                                :local? local?
                                :components components
                                :listing-thumbs? listing-thumbs?
                                :open? (open-box? :components)
                                :open-groups (open-groups :components)
                                :reverse-sort? reverse-sort?
                                :selected-assets selected-assets
                                :on-asset-click (partial on-asset-click :components)
                                :on-assets-delete on-assets-delete
                                :on-clear-selection unselect-all}])

          (when show-graphics?
            [:& graphics-box {:file-id (:id file)
                              :project-id (:project-id file)
                              :local? local?
                              :objects media
                              :listing-thumbs? listing-thumbs?
                              :open? (open-box? :graphics)
                              :open-groups (open-groups :graphics)
                              :reverse-sort? reverse-sort?
                              :selected-assets selected-assets
                              :on-asset-click (partial on-asset-click :graphics)
                              :on-assets-delete on-assets-delete
                              :on-clear-selection unselect-all}])
          (when show-colors?
            [:& colors-box {:file-id (:id file)
                            :local? local?
                            :colors colors
                            :open? (open-box? :colors)
                            :open-groups (open-groups :colors)
                            :reverse-sort? reverse-sort?
                            :selected-assets selected-assets
                            :on-asset-click (partial on-asset-click :colors)
                            :on-assets-delete on-assets-delete
                            :on-clear-selection unselect-all}])

          (when show-typography?
            [:& typographies-box {:file file
                                  :file-id (:id file)
                                  :local? local?
                                  :typographies typographies
                                  :open? (open-box? :typographies)
                                  :open-groups (open-groups :typographies)
                                  :reverse-sort? reverse-sort?
                                  :selected-assets selected-assets
                                  :on-asset-click (partial on-asset-click :typographies)
                                  :on-assets-delete on-assets-delete
                                  :on-clear-selection unselect-all}])

          (when (and (not show-components?) (not show-graphics?) (not show-colors?) (not show-typography?))
            [:div.asset-section
             [:div.asset-title (tr "workspace.assets.not-found")]])]))]))


(mf/defc assets-toolbox
  []
  (let [libraries            (->> (mf/deref refs/workspace-libraries)
                                  (vals)
                                  (remove :is-indirect))
        file                 (mf/deref refs/workspace-file)
        team-id              (mf/use-ctx ctx/current-team-id)
        filters              (mf/use-state {:term "" :box :all})
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        on-search-term-change
        (mf/use-fn
         (mf/deps team-id)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (swap! filters assoc :term value))))

        on-search-clear-click
        (mf/use-fn
         (mf/deps team-id)
         (fn [_]
           (swap! filters assoc :term "")))

        on-box-filter-change
        (mf/use-fn
         (mf/deps team-id)
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value)
                           (d/read-string))]
             (swap! filters assoc :box value))))

        handle-key-down
        (mf/use-callback
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 input-node (dom/event->target event)]

             (when enter?
               (dom/blur! input-node))
             (when esc?
               (dom/blur! input-node)))))]

    [:div.assets-bar
     [:div.tool-window
      [:div.tool-window-content
       [:div.assets-bar-title
        (tr "workspace.assets.assets")
        (when-not workspace-read-only?
          [:div.libraries-button {:on-click #(modal/show! :libraries-dialog {})}
           i/text-align-justify
           (tr "workspace.assets.libraries")])]

       [:div.search-block
        [:input.search-input
         {:placeholder (tr "workspace.assets.search")
          :type "text"
          :value (:term @filters)
          :on-change on-search-term-change
          :on-key-down handle-key-down}]
        (if (str/empty? (:term @filters))
          [:div.search-icon
           i/search]
          [:div.search-icon.close
           {:on-click on-search-clear-click}
           i/close])]

       [:select.input-select {:value (:box @filters)
                              :on-change on-box-filter-change}
        [:option {:value ":all"} (tr "workspace.assets.box-filter-all")]
        [:option {:value ":components"} (tr "workspace.assets.components")]
        [:option {:value ":graphics"} (tr "workspace.assets.graphics")]
        [:option {:value ":colors"} (tr "workspace.assets.colors")]
        [:option {:value ":typographies"} (tr "workspace.assets.typography")]]]]

     [:div.libraries-wrapper
      [:& file-library
       {:file file
        :local? true
        :default-open? true
        :filters @filters}]

      (for [file (->> libraries
                      (sort-by #(str/lower (:name %))))]
        [:& file-library
         {:key (:id file)
          :file file
          :local? false
          :default-open? false
          :filters @filters}])]]))

