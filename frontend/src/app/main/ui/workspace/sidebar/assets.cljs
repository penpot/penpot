;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.assets
  (:require
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.config :as cfg]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.exports :as exports]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.editable-label :refer [editable-label]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [typography-entry]]
   [app.util.data :refer [matches-search]]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

; TODO: refactor to remove duplicate code and less parameter passing.
;  - Move all state to [:workspace-local :assets-bar file-id :open-boxes {}
;                                                            :open-groups {}
;                                                            :reverse-sort?
;                                                            :listing-thumbs?
;                                                            :selected-assets {}]
;  - Move selection code to independent functions that receive the state as a parameter.
;
; TODO: change update operations to admit multiple ids, thus avoiding the need of
;       emitting many events and opening an undo transaction. Also move the logic
;       of grouping, deleting, etc. to events in the data module, since now the
;       selection info is in the global state.

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
                (let [path-vector (cp/split-path (or (:path asset) ""))]
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
      (cp/merge-path-item group-name)
      (cp/merge-path-item (:name asset))))

(defn rename-group
  [asset path last-path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cp/split-path)
      butlast
      (vec)
      (conj last-path)
      (cp/join-path)
      (str (str/slice (:path asset) (count path)))
      (cp/merge-path-item (:name asset))))

(defn ungroup
  [asset path]
  (-> (:path asset)
      (str/slice 0 (count path))
      (cp/split-path)
      butlast
      (cp/join-path)
      (str (str/slice (:path asset) (count path)))
      (cp/merge-path-item (:name asset))))

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
        (mf/use-callback
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
       [:& fm/form {:form form}
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

;; ---- Common blocks ----

(def auto-pos-menu-state {:open? false
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
      [:span {:on-click (st/emitf (dwl/set-assets-box-open file-id box (not open?)))}
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
    (let [[other-path last-path truncated] (cp/compact-path path 35)
          menu-state (mf/use-state auto-pos-menu-state)

          on-fold-group
          (mf/use-callback
            (mf/deps file-id box path group-open?)
            (fn [event]
              (dom/stop-propagation event)
              (st/emit! (dwl/set-assets-group-open file-id
                                                   box
                                                   path
                                                   (not group-open?)))))
          on-context-menu
          (mf/use-callback
            (fn [event]
              (swap! menu-state #(open-auto-pos-menu % event))))

          on-close-menu
          (mf/use-callback
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


;; ---- Components box ----

(mf/defc components-item
  [{:keys [component renaming listing-thumbs? selected-components
           on-asset-click on-context-menu on-drag-start do-rename cancel-rename]}]
     [:div {:key (:id component)
            :class-name (dom/classnames
                          :selected (contains? selected-components (:id component))
                          :grid-cell @listing-thumbs?
                          :enum-item (not @listing-thumbs?))
            :draggable true
            :on-click #(on-asset-click % (:id component) nil)
            :on-context-menu (on-context-menu (:id component))
            :on-drag-start (partial on-drag-start component)}
      [:& exports/component-svg {:group (get-in component [:objects (:id component)])
                                 :objects (:objects component)}]
      (let [renaming? (= renaming (:id component))]
        [:& editable-label
         {:class-name (dom/classnames
                        :cell-name @listing-thumbs?
                        :item-name (not @listing-thumbs?)
                        :editing renaming?)
          :value (cp/merge-path-item (:path component) (:name component))
          :tooltip (cp/merge-path-item (:path component) (:name component))
          :display-value (if @listing-thumbs?
                           (:name component)
                           (cp/compact-name (:path component)
                                            (:name component)))
          :editing? renaming?
          :disable-dbl-click? true
          :on-change do-rename
          :on-cancel cancel-rename}])])

(mf/defc components-group
  [{:keys [file-id prefix groups open-groups renaming listing-thumbs? selected-components on-asset-click
    on-drag-start do-rename cancel-rename on-rename-group on-ungroup on-context-menu]}]
  (let [group-open? (get open-groups prefix true)]

    [:*
     [:& asset-group-title {:file-id file-id
                            :box :components
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [components (get groups "" [])]
          [:div {:class-name (dom/classnames
                               :asset-grid @listing-thumbs?
                               :big @listing-thumbs?
                               :asset-enum (not @listing-thumbs?))}
           (for [component components]
             [:& components-item {:component component
                                  :renaming renaming
                                  :listing-thumbs? listing-thumbs?
                                  :selected-components selected-components
                                  :on-asset-click on-asset-click
                                  :on-context-menu on-context-menu
                                  :on-drag-start on-drag-start
                                  :do-rename do-rename
                                  :cancel-rename cancel-rename}])])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& components-group {:file-id file-id
                                  :prefix (cp/merge-path-item prefix path-item)
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
                                  :on-context-menu on-context-menu}]))])]))

(mf/defc components-box
  [{:keys [file-id local? components listing-thumbs? open? reverse-sort? open-groups selected-assets
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [state (mf/use-state {:renaming nil
                             :component-id nil})

        menu-state (mf/use-state auto-pos-menu-state)

        selected-components (:components selected-assets)
        multi-components?   (> (count selected-components) 1)
        multi-assets?       (or (seq (:graphics selected-assets))
                                (seq (:colors selected-assets))
                                (seq (:typographies selected-assets)))

        groups              (group-assets components reverse-sort?)

        on-duplicate
        (mf/use-callback
          (mf/deps @state)
          (fn []
            (if (empty? selected-components)
              (st/emit! (dwl/duplicate-component {:id (:component-id @state)}))
              (do
                (st/emit! (dwu/start-undo-transaction))
                (apply st/emit! (map #(dwl/duplicate-component {:id %}) selected-components))
                (st/emit! (dwu/commit-undo-transaction))))))

        on-delete
        (mf/use-callback
          (mf/deps @state file-id multi-components? multi-assets?)
          (fn []
            (if (or multi-components? multi-assets?)
              (on-assets-delete)
              (st/emit! (dwu/start-undo-transaction)
                        (dwl/delete-component {:id (:component-id @state)})
                        (dwl/sync-file file-id file-id)
                        (dwu/commit-undo-transaction)))))

        on-rename
        (mf/use-callback
          (mf/deps @state)
          (fn []
            (swap! state assoc :renaming (:component-id @state))))

        do-rename
        (mf/use-callback
          (mf/deps @state)
          (fn [new-name]
            (st/emit! (dwl/rename-component (:renaming @state) new-name))
            (swap! state assoc :renaming nil)))

        cancel-rename
        (mf/use-callback
          (fn []
            (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-callback
          (mf/deps selected-components on-clear-selection)
          (fn [component-id]
            (fn [event]
              (when local?
                (when-not (contains? selected-components component-id)
                  (on-clear-selection))
                (swap! state assoc :component-id component-id)
                (swap! menu-state #(open-auto-pos-menu % event))))))

        on-close-menu
        (mf/use-callback
          (fn []
            (swap! menu-state close-auto-pos-menu)))

        create-group
        (mf/use-callback
          (mf/deps components selected-components on-clear-selection)
          (fn [group-name]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> components
                        (filter #(if multi-components?
                                   (contains? selected-components (:id %))
                                   (= (:component-id @state) (:id %))))
                        (map #(dwl/rename-component
                                (:id %)
                                (add-group % group-name)))))
            (st/emit! (dwu/commit-undo-transaction))))

        rename-group
        (mf/use-callback
          (mf/deps components)
          (fn [path last-path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> components
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-component
                                (:id %)
                                (rename-group % path last-path)))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-group
        (mf/use-callback
          (mf/deps components selected-components)
          (fn [event]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:accept create-group})))

        on-rename-group
        (mf/use-callback
          (mf/deps components)
          (fn [event path last-path]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:path path
                                             :last-path last-path
                                             :accept rename-group})))

        on-ungroup
        (mf/use-callback
          (mf/deps components)
          (fn [path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> components
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-component
                                (:id %)
                                (ungroup % path)))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-drag-start
        (mf/use-callback
         (fn [component event]
           (dnd/set-data! event "penpot/component" {:file-id file-id
                                                    :component component})
           (dnd/set-allowed-effect! event "move")))]

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.components")
                       :box :components
                       :assets-count (count components)
                       :open? open?}
     [:& asset-section-block {:role :content}
      [:& components-group {:file-id file-id
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
                            :on-ungroup on-ungroup
                            :on-context-menu on-context-menu}]
      (when local?
        [:& auto-pos-menu
         {:on-close on-close-menu
          :state @menu-state
          :options [(when-not (or multi-components? multi-assets?)
                      [(tr "workspace.assets.rename") on-rename])
                    (when-not multi-assets?
                      [(tr "workspace.assets.duplicate") on-duplicate])
                    [(tr "workspace.assets.delete") on-delete]
                    (when-not multi-assets?
                      [(tr "workspace.assets.group") on-group])]}])]]))


;; ---- Graphics box ----

(mf/defc graphics-item
  [{:keys [object renaming listing-thumbs? selected-objects
           on-asset-click on-context-menu on-drag-start do-rename cancel-rename]}]
  [:div {:key (:id object)
         :class-name (dom/classnames
                       :selected (contains? selected-objects (:id object))
                       :grid-cell @listing-thumbs?
                       :enum-item (not @listing-thumbs?))
         :draggable true
         :on-click #(on-asset-click % (:id object) nil)
         :on-context-menu (on-context-menu (:id object))
         :on-drag-start (partial on-drag-start object)}
   [:img {:src (cfg/resolve-file-media object true)
          :draggable false}] ;; Also need to add css pointer-events: none

   (let [renaming? (= renaming (:id object))]
     [:& editable-label
      {:class-name (dom/classnames
                     :cell-name @listing-thumbs?
                     :item-name (not @listing-thumbs?)
                     :editing renaming?)
       :value (cp/merge-path-item (:path object) (:name object))
       :tooltip (cp/merge-path-item (:path object) (:name object))
       :display-value (if @listing-thumbs?
                        (:name object)
                        (cp/compact-name (:path object)
                                         (:name object)))
       :editing? renaming?
       :disable-dbl-click? true
       :on-change do-rename
       :on-cancel cancel-rename}])])

(mf/defc graphics-group
  [{:keys [file-id prefix groups open-groups renaming listing-thumbs? selected-objects on-asset-click
           on-drag-start do-rename cancel-rename on-rename-group on-ungroup
           on-context-menu]}]
  (let [group-open? (get open-groups prefix true)]

    [:*
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
                               :asset-grid @listing-thumbs?
                               :asset-enum (not @listing-thumbs?))}
           (for [object objects]
             [:& graphics-item {:object object
                                :renaming renaming
                                :listing-thumbs? listing-thumbs?
                                :selected-objects selected-objects
                                :on-asset-click on-asset-click
                                :on-context-menu on-context-menu
                                :on-drag-start on-drag-start
                                :do-rename do-rename
                                :cancel-rename cancel-rename}])])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& graphics-group {:file-id file-id
                                :prefix (cp/merge-path-item prefix path-item)
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
                                :on-context-menu on-context-menu}]))])]))

(mf/defc graphics-box
  [{:keys [file-id local? objects listing-thumbs? open? open-groups selected-assets reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [input-ref  (mf/use-ref nil)
        state      (mf/use-state {:renaming nil
                                  :object-id nil})

        menu-state (mf/use-state auto-pos-menu-state)

        selected-objects    (:graphics selected-assets)
        multi-objects?      (> (count selected-objects) 1)
        multi-assets?       (or (seq (:components selected-assets))
                                (seq (:colors selected-assets))
                                (seq (:typographies selected-assets)))

        groups (group-assets objects reverse-sort?)

        add-graphic
        (mf/use-callback
         (fn []
           (st/emitf (dwl/set-assets-box-open file-id :graphics true))
           (dom/click (mf/ref-val input-ref))))

        on-file-selected
        (mf/use-callback
         (mf/deps file-id)
         (fn [blobs]
           (let [params {:file-id file-id
                         :blobs (seq blobs)}]
             (st/emit! (dw/upload-media-asset params)
                       (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                              :asset-type "graphics"})))))

        on-delete
        (mf/use-callback
         (mf/deps @state multi-objects? multi-assets?)
         (fn []
           (if (or multi-objects? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwl/delete-media {:id (:object-id @state)})))))

        on-rename
        (mf/use-callback
          (mf/deps @state)
          (fn []
            (swap! state assoc :renaming (:object-id @state))))

        cancel-rename
        (mf/use-callback
          (fn []
            (swap! state assoc :renaming nil)))

        do-rename
        (mf/use-callback
          (mf/deps @state)
          (fn [new-name]
            (st/emit! (dwl/rename-media (:renaming @state) new-name))
            (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-callback
          (mf/deps selected-objects on-clear-selection)
          (fn [object-id]
            (fn [event]
              (when local?
                (when-not (contains? selected-objects object-id)
                  (on-clear-selection))
                (swap! state assoc :object-id object-id)
                (swap! menu-state #(open-auto-pos-menu % event))))))

        on-close-menu
        (mf/use-callback
          (fn []
            (swap! menu-state close-auto-pos-menu)))

        create-group
        (mf/use-callback
          (mf/deps objects selected-objects on-clear-selection)
          (fn [group-name]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> objects
                        (filter #(if multi-objects?
                                   (contains? selected-objects (:id %))
                                   (= (:object-id @state) (:id %))))
                        (map #(dwl/rename-media
                                (:id %)
                                (add-group % group-name)))))
            (st/emit! (dwu/commit-undo-transaction))))

        rename-group
        (mf/use-callback
          (mf/deps objects)
          (fn [path last-path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> objects
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-media
                                (:id %)
                                (rename-group % path last-path)))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-group
        (mf/use-callback
          (mf/deps objects selected-objects)
          (fn [event]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:accept create-group})))

        on-rename-group
        (mf/use-callback
          (mf/deps objects)
          (fn [event path last-path]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:path path
                                             :last-path last-path
                                             :accept rename-group})))
        on-ungroup
        (mf/use-callback
          (mf/deps objects)
          (fn [path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> objects
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/rename-media
                                (:id %)
                                (ungroup % path)))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-drag-start
        (mf/use-callback
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
         [:div.assets-button {:on-click add-graphic}
          i/plus
          [:& file-uploader {:accept cm/str-image-types
                             :multi true
                             :ref input-ref
                             :on-selected on-file-selected}]]])

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
                            :on-context-menu on-context-menu}]
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
           on-asset-click on-assets-delete on-clear-selection on-group] :as props}]
  (let [rename?   (= (:color-for-rename @refs/workspace-local) (:id color))
        input-ref (mf/use-ref)
        state     (mf/use-state {:editing rename?})

        menu-state (mf/use-state auto-pos-menu-state)

        default-name (cond
                       (:gradient color) (bc/gradient-type->string (get-in color [:gradient :type]))
                       (:color color) (:color color)
                       :else (:value color))

        ;; TODO: looks like the first argument is not necessary
        apply-color
        (fn [_ event]
          (let [ids (wsh/lookup-selected @st/state)]
            (if (kbd/shift? event)
              (st/emit! (dc/change-stroke ids color))
              (st/emit! (dc/change-fill ids color)))))

        rename-color
        (fn [name]
          (st/emit! (dwl/update-color (assoc color :name name) file-id)))

        edit-color
        (fn [new-color]
          (let [old-data (-> (select-keys color [:id :file-id])
                             (assoc :name (cp/merge-path-item (:path color) (:name color))))
                updated-color (merge new-color old-data)]
            (st/emit! (dwl/update-color updated-color file-id))))

        delete-color
        (mf/use-callback
         (mf/deps @state multi-colors? multi-assets? file-id)
         (fn []
           (if (or multi-colors? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwu/start-undo-transaction)
                       (dwl/delete-color color)
                       (dwl/sync-file file-id file-id)
                       (dwu/commit-undo-transaction)))))

        rename-color-clicked
        (fn [event]
          (when local?
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
        (mf/use-callback
          (mf/deps color selected-colors on-clear-selection)
          (fn [event]
            (when local?
              (when-not (contains? selected-colors (:id color))
                (on-clear-selection))
              (swap! menu-state #(open-auto-pos-menu % event)))))

        on-close-menu
        (mf/use-callback
          (fn []
            (swap! menu-state close-auto-pos-menu)))]

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
                                       #(on-asset-click % (:id color)
                                                       (partial apply-color (:id color))))}
     [:& bc/color-bullet {:color color}]

     (if (:editing @state)
       [:input.element-name
        {:type "text"
         :ref input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :default-value (cp/merge-path-item (:path color) (:name color))}]

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
                      [(tr "workspace.assets.group") (on-group (:id color))])]}])]))

(mf/defc colors-group
  [{:keys [file-id prefix groups open-groups local? selected-colors
           multi-colors? multi-assets? on-asset-click on-assets-delete
           on-clear-selection on-group on-rename-group on-ungroup colors]}]
  (let [group-open? (get open-groups prefix true)]

    [:*
     [:& asset-group-title {:file-id file-id
                            :box :colors
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [colors (get groups "" [])]
          [:div.asset-list
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
                               :colors colors}]))])
        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& colors-group {:file-id file-id
                              :prefix (cp/merge-path-item prefix path-item)
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
                              :colors colors}]))])]))

(mf/defc colors-box
  [{:keys [file-id local? colors open? open-groups selected-assets reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [selected-colors     (:colors selected-assets)
        multi-colors?       (> (count selected-colors) 1)
        multi-assets?       (or (seq (:components selected-assets))
                                (seq (:graphics selected-assets))
                                (seq (:typographies selected-assets)))

        groups              (group-assets colors reverse-sort?)

        add-color
        (mf/use-callback
         (mf/deps file-id)
         (fn [value _opacity]
           (st/emit! (dwl/add-color value))))

        add-color-clicked
        (mf/use-callback
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
        (mf/use-callback
          (mf/deps colors selected-colors on-clear-selection file-id)
          (fn [color-id]
            (fn [group-name]
              (on-clear-selection)
              (st/emit! (dwu/start-undo-transaction))
              (apply st/emit!
                     (->> colors
                          (filter #(if multi-colors?
                                     (contains? selected-colors (:id %))
                                     (= color-id (:id %))))
                          (map #(dwl/update-color
                                  (assoc % :name
                                         (add-group % group-name))
                                  file-id))))
              (st/emit! (dwu/commit-undo-transaction)))))

        rename-group
        (mf/use-callback
          (mf/deps colors)
          (fn [path last-path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> colors
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/update-color
                                (assoc % :name
                                       (rename-group % path last-path))
                                file-id))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-group
        (mf/use-callback
          (mf/deps colors selected-colors)
          (fn [color-id]
            (fn [event]
              (dom/stop-propagation event)
              (modal/show! :name-group-dialog {:accept (create-group color-id)}))))

        on-rename-group
        (mf/use-callback
          (mf/deps colors)
          (fn [event path last-path]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:path path
                                             :last-path last-path
                                             :accept rename-group})))
        on-ungroup
        (mf/use-callback
          (mf/deps colors)
          (fn [path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> colors
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/update-color
                                (assoc % :name
                                       (ungroup % path))
                                file-id))))
            (st/emit! (dwu/commit-undo-transaction))))]

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.colors")
                       :box :colors
                       :assets-count (count colors)
                       :open? open?}
      (when local?
        [:& asset-section-block {:role :title-button}
         [:div.assets-button {:on-click add-color-clicked}
          i/plus]])

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
                          :colors colors}]]]))

;; ---- Typography box ----

(mf/defc typographies-group
  [{:keys [file-id prefix groups open-groups file local? selected-typographies local
           editing-id on-asset-click handle-change apply-typography
           on-rename-group on-ungroup on-context-menu]}]
  (let [group-open? (get open-groups prefix true)]

    [:*
     [:& asset-group-title {:file-id file-id
                            :box :typographies
                            :path prefix
                            :group-open? group-open?
                            :on-rename on-rename-group
                            :on-ungroup on-ungroup}]
     (when group-open?
       [:*
        (let [typographies (get groups "" [])]
          [:div.asset-list
           (for [typography typographies]
             [:& typography-entry
              {:key (:id typography)
               :typography typography
               :file file
               :read-only? (not local?)
               :on-context-menu #(on-context-menu (:id typography) %)
               :on-change #(handle-change typography %)
               :selected? (contains? selected-typographies (:id typography))
               :on-click  #(on-asset-click % (:id typography)
                                           (partial apply-typography typography))
               :editing? (= editing-id (:id typography))
               :focus-name? (= (:rename-typography local) (:id typography))}])])

        (for [[path-item content] groups]
          (when-not (empty? path-item)
            [:& typographies-group {:file-id file-id
                                    :prefix (cp/merge-path-item prefix path-item)
                                    :groups content
                                    :open-groups open-groups
                                    :file file
                                    :local? local?
                                    :selected-typographies selected-typographies
                                    :editing-id editing-id
                                    :local local
                                    :on-asset-click on-asset-click
                                    :handle-change handle-change
                                    :apply-typography apply-typography
                                    :on-rename-group on-rename-group
                                    :on-ungroup on-ungroup
                                    :on-context-menu on-context-menu}]))])]))

(mf/defc typographies-box
  [{:keys [file file-id local? typographies open? open-groups selected-assets reverse-sort?
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [state (mf/use-state {:detail-open? false
                             :id nil})

        menu-state (mf/use-state auto-pos-menu-state)

        local    (deref refs/workspace-local)

        groups        (group-assets typographies reverse-sort?)

        selected-typographies (:typographies selected-assets)
        multi-typographies?   (> (count selected-typographies) 1)
        multi-assets?         (or (seq (:components selected-assets))
                                  (seq (:graphics selected-assets))
                                  (seq (:colors selected-assets)))

        add-typography
        (mf/use-callback
         (mf/deps file-id)
         (fn [_]
           (st/emit! (dwl/add-typography txt/default-typography)
                     (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                            :asset-type "typography"}))))

        handle-change
        (mf/use-callback
         (mf/deps file-id)
         (fn [typography changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        apply-typography
        (fn [typography _event]
          (let [ids (wsh/lookup-selected @st/state)
                attrs (merge
                        {:typography-ref-file file-id
                         :typography-ref-id (:id typography)}
                        (d/without-keys typography [:id :name]))]
            (run! #(st/emit! (dwt/update-text-attrs {:id % :editor (get-in local [:editors %]) :attrs attrs}))
                  ids)))

        create-group
        (mf/use-callback
          (mf/deps typographies selected-typographies on-clear-selection file-id)
          (fn [group-name]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> typographies
                        (filter #(if multi-typographies?
                                   (contains? selected-typographies (:id %))
                                   (= (:id @state) (:id %))))
                        (map #(dwl/update-typography
                                (assoc % :name
                                       (add-group % group-name))
                                file-id))))
            (st/emit! (dwu/commit-undo-transaction))))

        rename-group
        (mf/use-callback
          (mf/deps typographies)
          (fn [path last-path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> typographies
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/update-typography
                                (assoc % :name
                                       (rename-group % path last-path))
                                file-id))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-group
        (mf/use-callback
          (mf/deps typographies selected-typographies)
          (fn [event]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:accept create-group})))

        on-rename-group
        (mf/use-callback
          (mf/deps typographies)
          (fn [event path last-path]
            (dom/stop-propagation event)
            (modal/show! :name-group-dialog {:path path
                                             :last-path last-path
                                             :accept rename-group})))
        on-ungroup
        (mf/use-callback
          (mf/deps typographies)
          (fn [path]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> typographies
                        (filter #(str/starts-with? (:path %) path))
                        (map #(dwl/update-typography
                                (assoc % :name
                                       (ungroup % path))
                                file-id))))
            (st/emit! (dwu/commit-undo-transaction))))

        on-context-menu
        (mf/use-callback
          (mf/deps selected-typographies on-clear-selection)
          (fn [id event]
            (when local?
              (when-not (contains? selected-typographies id)
                (on-clear-selection))
              (swap! state assoc :id id)
              (swap! menu-state #(open-auto-pos-menu % event)))))

        on-close-menu
        (mf/use-callback
          (fn []
            (swap! menu-state close-auto-pos-menu)))

        handle-rename-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-local :rename-typography] (:id @state))))

        handle-edit-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-local :edit-typography] (:id @state))))

        handle-delete-typography
        (mf/use-callback
         (mf/deps @state multi-typographies? multi-assets?)
         (fn []
           (if (or multi-typographies? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwu/start-undo-transaction)
                       (dwl/delete-typography (:id @state))
                       (dwl/sync-file file-id file-id)
                       (dwu/commit-undo-transaction)))))

        editing-id (or (:rename-typography local) (:edit-typography local))]

    (mf/use-effect
     (mf/deps local)
     (fn []
       (when (:rename-typography local)
         (st/emit! #(update % :workspace-local dissoc :rename-typography)))
       (when (:edit-typography local)
         (st/emit! #(update % :workspace-local dissoc :edit-typography)))))

    [:& asset-section {:file-id file-id
                       :title (tr "workspace.assets.typography")
                       :box :typographies
                       :assets-count (count typographies)
                       :open? open?}
      (when local?
        [:& asset-section-block {:role :title-button}
         [:div.assets-button {:on-click add-typography}
          i/plus]])

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
                                :local local
                                :on-asset-click (partial on-asset-click groups)
                                :handle-change handle-change
                                :apply-typography apply-typography
                                :on-rename-group on-rename-group
                                :on-ungroup on-ungroup
                                :on-context-menu on-context-menu}]

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
                   (vals (get wfile :components))
                   (vals (get-in state [:workspace-libraries id :data :components])))))
             st/state =))

(defn file-typography-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-data state)]
                 (if (= (:id wfile) id)
                   (vals (get wfile :typographies))
                   (vals (get-in state [:workspace-libraries id :data :typographies])))))
             st/state =))

(defn open-file-ref
  [id]
  (-> (l/in [:assets-files-open id])
      (l/derived refs/workspace-local)))

(defn apply-filters
  [coll filters reverse-sort?]
  (let [comp-fn (if reverse-sort? > <)]
    (->> coll
         (filter (fn [item]
                   (or (matches-search (:name item "!$!") (:term filters))
                       (matches-search (:value item "!$!") (:term filters)))))
         ; Sort by folder order, but putting all "root" items always first,
         ; independently of sort order.
         (sort-by #(str/lower (cp/merge-path-item (if (empty? (:path %))
                                                    (if reverse-sort? "z" "a")
                                                    (:path %))
                                                  (:name %)))
                  comp-fn))))

(mf/defc file-library
  [{:keys [file local? default-open? filters] :as props}]
  (let [open-file       (mf/deref (open-file-ref (:id file)))
        open?           (-> open-file
                            :library
                            (d/nilv default-open?))
        open-box?       (fn [box]
                          (-> open-file
                              box
                              (d/nilv true)))
        open-groups     (fn [box]
                          (-> open-file
                              :groups
                              box
                              (d/nilv {})))
        shared?         (:is-shared file)
        router          (mf/deref refs/router)

        reverse-sort?   (mf/use-state false)
        listing-thumbs? (mf/use-state true)

        selected-assets (mf/use-state {:components #{}
                                       :graphics #{}
                                       :colors #{}
                                       :typographies #{}})

        selected-count (+ (count (:components @selected-assets))
                          (count (:graphics @selected-assets))
                          (count (:colors @selected-assets))
                          (count (:typographies @selected-assets)))

        toggle-open     (st/emitf (dwl/set-assets-box-open (:id file) :library (not open?)))

        url             (rt/resolve router :workspace
                                    {:project-id (:project-id file)
                                     :file-id (:id file)}
                                    {:page-id (get-in file [:data :pages 0])})

        colors-ref      (mf/use-memo (mf/deps (:id file)) #(file-colors-ref (:id file)))
        colors          (apply-filters (mf/deref colors-ref) filters @reverse-sort?)

        typography-ref  (mf/use-memo (mf/deps (:id file)) #(file-typography-ref (:id file)))
        typographies    (apply-filters (mf/deref typography-ref) filters @reverse-sort?)

        media-ref       (mf/use-memo (mf/deps (:id file)) #(file-media-ref (:id file)))
        media           (apply-filters (mf/deref media-ref) filters @reverse-sort?)

        components-ref  (mf/use-memo (mf/deps (:id file)) #(file-components-ref (:id file)))
        components      (apply-filters (mf/deref components-ref) filters @reverse-sort?)

        toggle-sort
        (mf/use-callback
         (fn [_]
           (swap! reverse-sort? not)))

        toggle-listing
        (mf/use-callback
         (fn [_]
           (swap! listing-thumbs? not)))

        toggle-selected-asset
        (mf/use-callback
         (mf/deps @selected-assets)
         (fn [asset-type asset-id]
           (swap! selected-assets update asset-type
                  (fn [selected]
                    (if (contains? selected asset-id)
                      (disj selected asset-id)
                      (conj selected asset-id))))))

        extend-selected-assets
        (mf/use-callback
          (mf/deps @selected-assets)
          (fn [asset-type asset-groups asset-id]
            (letfn [(flatten-groups
                      [groups]
                      (concat
                        (get groups "" [])
                        (reduce concat
                                []
                                (->> (filter #(seq (first %)) groups)
                                     (map second)
                                     (map flatten-groups)))))]
              (swap! selected-assets update asset-type
                     (fn [selected]
                       (let [all-assets   (flatten-groups asset-groups)
                             clicked-idx  (d/index-of-pred all-assets #(= (:id %) asset-id))
                             selected-idx (->> selected
                                               (map (fn [id]
                                                      (d/index-of-pred all-assets
                                                                       #(= (:id %) id)))))
                             min-idx      (apply min (conj selected-idx clicked-idx))
                             max-idx      (apply max (conj selected-idx clicked-idx))]

                         (->> all-assets
                              d/enumerate
                              (filter #(<= min-idx (first %) max-idx))
                              (map #(-> % second :id))
                              set)))))))

        unselect-all
        (mf/use-callback
          (fn []
            (swap! selected-assets {:components #{}
                                    :graphics #{}
                                    :colors #{}
                                    :typographies #{}})))

        on-asset-click
        (mf/use-callback
          (mf/deps toggle-selected-asset extend-selected-assets)
          (fn [asset-type asset-groups event asset-id default-click]
            (cond
              (kbd/ctrl? event)
              (do
                (dom/stop-propagation event)
                (toggle-selected-asset asset-type asset-id))

              (kbd/shift? event)
              (do
                (dom/stop-propagation event)
                (extend-selected-assets asset-type asset-groups asset-id))

              :else
              (when default-click
                (default-click event)))))

        on-assets-delete
        (mf/use-callback
          (mf/deps @selected-assets)
          (fn []
            (let [selected-assets @selected-assets]
              (st/emit! (dwu/start-undo-transaction))
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
              (st/emit! (dwu/commit-undo-transaction)))))]

    [:div.tool-window {:on-context-menu #(dom/prevent-default %)
                       :on-click unselect-all}
     [:div.tool-window-bar.library-bar
      {:on-click toggle-open}
      [:div.collapse-library
       {:class (dom/classnames :open open?)}
       i/arrow-slide]

      (if local?
        [:*
          [:span (tr "workspace.assets.file-library")]
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
                                         (str/empty? (:term filters))))
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
            (if @reverse-sort?
              i/sort-ascending
              i/sort-descending)]
           [:div.listing-option-btn {:on-click toggle-listing}
            (if @listing-thumbs?
              i/listing-enum
              i/listing-thumbs)]]

          (when show-components?
            [:& components-box {:file-id (:id file)
                                :local? local?
                                :components components
                                :listing-thumbs? listing-thumbs?
                                :open? (open-box? :components)
                                :open-groups (open-groups :components)
                                :reverse-sort? @reverse-sort?
                                :selected-assets @selected-assets
                                :on-asset-click (partial on-asset-click :components)
                                :on-assets-delete on-assets-delete
                                :on-clear-selection unselect-all}])

          (when show-graphics?
            [:& graphics-box {:file-id (:id file)
                              :local? local?
                              :objects media
                              :listing-thumbs? listing-thumbs?
                              :open? (open-box? :graphics)
                              :open-groups (open-groups :graphics)
                              :reverse-sort? @reverse-sort?
                              :selected-assets @selected-assets
                              :on-asset-click (partial on-asset-click :graphics)
                              :on-assets-delete on-assets-delete
                              :on-clear-selection unselect-all}])
          (when show-colors?
            [:& colors-box {:file-id (:id file)
                            :local? local?
                            :colors colors
                            :open? (open-box? :colors)
                            :open-groups (open-groups :colors)
                            :reverse-sort? @reverse-sort?
                            :selected-assets @selected-assets
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
                                  :reverse-sort? @reverse-sort?
                                  :selected-assets @selected-assets
                                  :on-asset-click (partial on-asset-click :typographies)
                                  :on-assets-delete on-assets-delete
                                  :on-clear-selection unselect-all}])

          (when (and (not show-components?) (not show-graphics?) (not show-colors?))
            [:div.asset-section
             [:div.asset-title (tr "workspace.assets.not-found")]])]))]))


(mf/defc assets-toolbox
  []
  (let [libraries (->> (mf/deref refs/workspace-libraries)
                       (vals)
                       (remove :is-indirect))
        file      (mf/deref refs/workspace-file)
        team-id   (mf/use-ctx ctx/current-team-id)
        filters   (mf/use-state {:term "" :box :all})

        on-search-term-change
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (swap! filters assoc :term value))))

        on-search-clear-click
        (mf/use-callback
         (mf/deps team-id)
         (fn [_]
           (swap! filters assoc :term "")))

        on-box-filter-change
        (mf/use-callback
         (mf/deps team-id)
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value)
                           (d/read-string))]
             (swap! filters assoc :box value))))]

    [:div.assets-bar
     [:div.tool-window
       [:div.tool-window-content
        [:div.assets-bar-title
         (tr "workspace.assets.assets")
         [:div.libraries-button {:on-click #(modal/show! :libraries-dialog {})}
          i/text-align-justify
          (tr "workspace.assets.libraries")]]

        [:div.search-block
          [:input.search-input
           {:placeholder (tr "workspace.assets.search")
            :type "text"
            :value (:term @filters)
            :on-change on-search-term-change}]
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

