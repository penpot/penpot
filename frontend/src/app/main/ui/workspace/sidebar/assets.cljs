;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.assets
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.media :as cm]
   [app.common.pages :as cp]
   [app.common.text :as txt]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.exports :as exports]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.components.editable-label :refer [editable-label]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [typography-entry]]
   [app.util.data :refer [matches-search]]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [app.util.timers :as timers]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))


;; ---- Assets selection management

(def empty-selection #{})

(defn toggle-select
  [selected asset-id]
  (if (contains? selected asset-id)
    (disj selected asset-id)
    (conj selected asset-id)))

(defn replace-select
  [selected asset-id]
  #{asset-id})

(defn extend-select
  [selected asset-id groups]
  (let [assets       (->> groups vals flatten)
        clicked-idx  (d/index-of-pred assets #(= (:id %) asset-id))
        selected-idx (->> selected
                          (map (fn [id] (d/index-of-pred assets
                                                         #(= (:id %) id)))))
        min-idx      (apply min (conj selected-idx clicked-idx))
        max-idx      (apply max (conj selected-idx clicked-idx))]

    (->> assets
         d/enumerate
         (filter #(<= min-idx (first %) max-idx))
         (map #(-> % second :id))
         set)))


;; ---- Group assets management ----

(s/def ::asset-name ::us/not-empty-string)
(s/def ::create-group-form
  (s/keys :req-un [::asset-name]))

(defn group-assets
  [assets]
  (reduce (fn [groups asset]
              (update groups (or (:path asset) "")
                      #(conj (or % []) asset)))
          (sorted-map)
          assets))

(def empty-folded-groups #{})

(defn toggle-folded-group
  [folded-groups path]
  (if (contains? folded-groups path)
    (disj folded-groups path)
    (conj folded-groups path)))

(mf/defc create-group-dialog
  {::mf/register modal/components
   ::mf/register-as :create-group-dialog}
  [{:keys [create] :as ctx}]
  (let [form  (fm/use-form :spec ::create-group-form
                           :initial {})

        close #(modal/hide!)

        on-accept
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (let [asset-name (get-in @form [:clean-data :asset-name])]
             (create asset-name)
             (modal/hide!))))]

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "workspace.assets.create-group")]]
       [:div.modal-close-button
        {:on-click close} i/close]]

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
          :on-click close}]

        [:input.accept-button.primary
         {:type "button"
          :class (when-not (:valid @form) "btn-disabled")
          :disabled (not (:valid @form))
          :value (tr "labels.create")
          :on-click on-accept}]]]]]))


;; ---- Components box ----

(mf/defc components-box
  [{:keys [file-id local? components listing-thumbs? open? change-selected] :as props}]
  (let [state (mf/use-state {:menu-open false
                             :renaming nil
                             :top nil
                             :left nil
                             :component-id nil
                             :selected empty-selection
                             :folded-groups empty-folded-groups})

        groups        (group-assets components)
        selected      (:selected @state)
        folded-groups (:folded-groups @state)

        on-duplicate
        (mf/use-callback
         (mf/deps state)
         (fn []
           (if (empty? selected)
             (st/emit! (dwl/duplicate-component {:id (:component-id @state)}))
             (do
               (st/emit! (dwc/start-undo-transaction))
               (apply st/emit! (map #(dwl/duplicate-component {:id %}) selected))
               (st/emit! (dwc/commit-undo-transaction))))))

        on-delete
        (mf/use-callback
         (mf/deps state)
         (fn []
           (if (empty? selected)
             (st/emit! (dwl/delete-component {:id (:component-id @state)}))
             (do
               (st/emit! (dwc/start-undo-transaction))
               (apply st/emit! (map #(dwl/delete-component {:id %}) selected))
               (st/emit! (dwc/commit-undo-transaction))))
           (st/emit! (dwl/sync-file file-id file-id))))

        on-rename
        (mf/use-callback
          (mf/deps state)
          (fn []
            (swap! state assoc :renaming (:component-id @state))))

        do-rename
        (mf/use-callback
          (mf/deps state)
          (fn [new-name]
            (st/emit! (dwl/rename-component (:renaming @state) new-name))
            (swap! state assoc :renaming nil)))

        cancel-rename
        (mf/use-callback
          (mf/deps state)
          (fn []
            (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-callback
         (fn [component-id]
           (fn [event]
             (when local?
               (let [pos (dom/get-client-position event)
                     top (:y pos)
                     left (- (:x pos) 20)]
                 (dom/prevent-default event)
                 (swap! state assoc :menu-open true
                        :top top
                        :left left
                        :component-id component-id))))))

        unselect-all
        (mf/use-callback
          (fn [event]
            (swap! state assoc :selected empty-selection)))

        on-select
        (mf/use-callback
          (mf/deps state)
          (fn [component-id]
            (fn [event]
              (dom/stop-propagation event)
              (swap! state update :selected
                     (fn [selected]
                       (cond
                         (kbd/ctrl? event)
                         (toggle-select selected component-id)

                         (kbd/shift? event)
                         (extend-select selected component-id groups)))))))

        create-group
        (mf/use-callback
          (mf/deps components selected)
          (fn [name]
            (swap! state assoc :selected empty-selection)
            (st/emit! (dwc/start-undo-transaction))
            (apply st/emit!
                   (->> components
                        (filter #(contains? selected (:id %)))
                        (map #(dwl/rename-component
                                (:id %)
                                (str name " / "
                                    (cp/merge-path-item (:path %) (:name %)))))))
            (st/emit! (dwc/commit-undo-transaction))))

        on-fold-group
        (mf/use-callback
          (mf/deps groups folded-groups)
          (fn [path]
            (fn [event]
              (dom/stop-propagation event)
              (swap! state update :folded-groups 
                     toggle-folded-group path))))

        on-group
        (mf/use-callback
          (mf/deps components selected)
          (fn [event]
            (dom/stop-propagation event)
            (modal/show! :create-group-dialog {:create create-group})))

        on-drag-start
        (mf/use-callback
         (fn [component event]
           (dnd/set-data! event "penpot/component" {:file-id file-id
                                                    :component component})
           (dnd/set-allowed-effect! event "move")))]

    (mf/use-effect
      (mf/deps [change-selected selected])
      #(change-selected (count selected)))

    [:div.asset-section {:on-click unselect-all}
     [:div.asset-title {:class (when (not open?) "closed")}
      [:span {:on-click (st/emitf (dwl/set-assets-box-open file-id :components (not open?)))}
       i/arrow-slide (tr "workspace.assets.components")]
      [:span (str "\u00A0(") (count components) ")"]] ;; Unicode 00A0 is non-breaking space
     (when open?
       (for [group groups]
         (let [path        (first group)
               components  (second group)
               group-open? (not (contains? folded-groups path))]
           [:*
            (when-not (empty? path)
              (let [[other-path last-path truncated] (cp/compact-path path 35)]
                [:div.group-title {:class (when-not group-open? "closed")
                                   :on-click (on-fold-group path)}
                 [:span i/arrow-slide]
                 (when-not (empty? other-path)
                   [:span.dim {:title (when truncated path)}
                    other-path "\u00A0/\u00A0"])
                 [:span {:title (when truncated path)}
                  last-path]]))
            (when group-open?
              [:div {:class-name (dom/classnames
                                   :asset-grid @listing-thumbs?
                                   :big @listing-thumbs?
                                   :asset-enum (not @listing-thumbs?))}
               (for [component components]
                 (let [renaming? (= (:renaming @state)(:id component))]
                   [:div {:key (:id component)
                          :class-name (dom/classnames
                                        :selected (contains? selected (:id component))
                                        :grid-cell @listing-thumbs?
                                        :enum-item (not @listing-thumbs?))
                          :draggable true
                          :on-click (on-select (:id component))
                          :on-context-menu (on-context-menu (:id component))
                          :on-drag-start (partial on-drag-start component)}
                    [:& exports/component-svg {:group (get-in component [:objects (:id component)])
                                               :objects (:objects component)}]
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
                      :on-cancel cancel-rename}]]))])])))

     (when local?
       [:& context-menu
        {:selectable false
         :show (:menu-open @state)
         :on-close #(swap! state assoc :menu-open false)
         :top (:top @state)
         :left (:left @state)
         :options [(when (<= (count selected) 1)
                     [(tr "workspace.assets.rename") on-rename])
                   [(tr "workspace.assets.duplicate") on-duplicate]
                   [(tr "workspace.assets.delete") on-delete]
                   [(tr "workspace.assets.group") on-group]]}])]))


;; ---- Graphics box ----

(mf/defc graphics-box
  [{:keys [file-id local? objects listing-thumbs? open? change-selected] :as props}]
  (let [input-ref  (mf/use-ref nil)
        state      (mf/use-state {:menu-open false
                                  :renaming nil
                                  :top nil
                                  :left nil
                                  :object-id nil
                                  :selected empty-selection
                                  :folded-groups empty-folded-groups})

        groups        (group-assets objects)
        selected      (:selected @state)
        folded-groups (:folded-groups @state)

        add-graphic
        (mf/use-callback
         (fn []
           (st/emitf (dwl/set-assets-box-open file-id :graphics true))
           (dom/click (mf/ref-val input-ref))))

        on-selected
        (mf/use-callback
         (mf/deps file-id)
         (fn [blobs]
           (let [params {:file-id file-id
                         :blobs (seq blobs)}]
             (st/emit! (dw/upload-media-asset params)))))

        on-delete
        (mf/use-callback
         (mf/deps state)
         (fn []
           (if (empty? selected)
             (st/emit! (dwl/delete-media {:id (:object-id @state)}))
             (do
               (st/emit! (dwc/start-undo-transaction))
               (apply st/emit! (map #(dwl/delete-media {:id %}) selected))
               (st/emit! (dwc/commit-undo-transaction))))))

        on-rename
        (mf/use-callback
          (mf/deps state)
          (fn []
            (swap! state assoc :renaming (:object-id @state))))

        cancel-rename
        (mf/use-callback
          (mf/deps state)
          (fn []
            (swap! state assoc :renaming nil)))

        do-rename
        (mf/use-callback
          (mf/deps state)
          (fn [new-name]
            (st/emit! (dwl/rename-media (:renaming @state) new-name))
            (swap! state assoc :renaming nil)))

        on-context-menu
        (mf/use-callback
         (fn [object-id]
           (fn [event]
             (when local?
               (let [pos (dom/get-client-position event)
                     top (:y pos)
                     left (- (:x pos) 20)]
                 (dom/prevent-default event)
                 (swap! state assoc :menu-open true
                        :top top
                        :left left
                        :object-id object-id))))))

        unselect-all
        (mf/use-callback
          (fn [event]
            (swap! state assoc :selected empty-selection)))

        on-select
        (mf/use-callback
          (mf/deps state)
          (fn [object-id]
            (fn [event]
              (dom/stop-propagation event)
              (swap! state update :selected
                     (fn [selected]
                       (cond
                         (kbd/ctrl? event)
                         (toggle-select selected object-id)

                         (kbd/shift? event)
                         (extend-select selected object-id groups)))))))

        create-group
        (mf/use-callback
          (mf/deps objects selected)
          (fn [name]
            (swap! state assoc :selected empty-selection)
            (st/emit! (dwc/start-undo-transaction))
            (apply st/emit!
                   (->> objects
                        (filter #(contains? selected (:id %)))
                        (map #(dwl/rename-media
                                (:id %)
                                (str name " / "
                                    (cp/merge-path-item (:path %) (:name %)))))))
            (st/emit! (dwc/commit-undo-transaction))))

        on-fold-group
        (mf/use-callback
          (mf/deps groups folded-groups)
          (fn [path]
            (fn [event]
              (dom/stop-propagation event)
              (swap! state update :folded-groups 
                     toggle-folded-group path))))

        on-group
        (mf/use-callback
          (mf/deps objects selected)
          (fn [event]
            (dom/stop-propagation event)
            (modal/show! :create-group-dialog {:create create-group})))

        on-drag-start
        (mf/use-callback
         (fn [{:keys [name id mtype]} event]
           (dnd/set-data! event "text/asset-id" (str id))
           (dnd/set-data! event "text/asset-name" name)
           (dnd/set-data! event "text/asset-type" mtype)
           (dnd/set-allowed-effect! event "move")))]

    (mf/use-effect
      (mf/deps [change-selected selected])
      #(change-selected (count selected)))

    [:div.asset-section {:on-click unselect-all}
     [:div.asset-title {:class (when (not open?) "closed")}
      [:span {:on-click (st/emitf (dwl/set-assets-box-open file-id :graphics (not open?)))}
       i/arrow-slide (tr "workspace.assets.graphics")]
      [:span.num-assets (str "\u00A0(") (count objects) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.assets-button {:on-click add-graphic}
         i/plus
         [:& file-uploader {:accept cm/str-media-types
                            :multi true
                            :input-ref input-ref
                            :on-selected on-selected}]])]
     (when open?
       (for [group groups]
         (let [path        (first group)
               objects     (second group)
               group-open? (not (contains? folded-groups path))]
           [:*
            (when-not (empty? path)
              (let [[other-path last-path truncated] (cp/compact-path path 35)]
                [:div.group-title {:class (when-not group-open? "closed")
                                   :on-click (on-fold-group path)}
                 [:span i/arrow-slide]
                 (when-not (empty? other-path)
                   [:span.dim {:title (when truncated path)}
                    other-path "\u00A0/\u00A0"])
                 [:span {:title (when truncated path)}
                  last-path]]))
            (when group-open?
              [:div {:class-name (dom/classnames
                                   :asset-grid @listing-thumbs?
                                   :asset-enum (not @listing-thumbs?))}
               (for [object objects]
                 [:div {:key (:id object)
                        :class-name (dom/classnames
                                      :selected (contains? selected (:id object))
                                      :grid-cell @listing-thumbs?
                                      :enum-item (not @listing-thumbs?))
                        :draggable true
                        :on-click (on-select (:id object))
                        :on-context-menu (on-context-menu (:id object))
                        :on-drag-start (partial on-drag-start object)}
                  [:img {:src (cfg/resolve-file-media object true)
                         :draggable false}] ;; Also need to add css pointer-events: none

                  (let [renaming? (= (:renaming @state) (:id object))]
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
                      :on-cancel cancel-rename}])])])])))

     (when local?
       [:& context-menu
        {:selectable false
         :show (:menu-open @state)
         :on-close #(swap! state assoc :menu-open false)
         :top (:top @state)
         :left (:left @state)
         :options [(when (<= (count selected) 1)
                     [(tr "workspace.assets.rename") on-rename])
                   [(tr "workspace.assets.delete") on-delete]
                   [(tr "workspace.assets.group") on-group]]}])]))


;; ---- Colors box ----

(mf/defc color-item
  [{:keys [color local? file-id selected on-select locale] :as props}]
  (let [rename?   (= (:color-for-rename @refs/workspace-local) (:id color))
        id        (:id color)
        input-ref (mf/use-ref)
        state     (mf/use-state {:menu-open false
                                 :top nil
                                 :left nil
                                 :editing rename?})

        default-name (cond
                       (:gradient color) (bc/gradient-type->string (get-in color [:gradient :type]))
                       (:color color) (:color color)
                       :else (:value color))

        click-color
        (fn [event]
          (when on-select
            ((on-select (:id color)) event))
          (let [ids (get-in @st/state [:workspace-local :selected])]
            (if (kbd/shift? event)
              (st/emit! (dc/change-stroke ids color))
              (st/emit! (dc/change-fill ids color)))))

        rename-color
        (fn [name]
          (st/emit! (dwl/update-color (assoc color :name name) file-id)))

        edit-color
        (fn [new-color]
          (let [updated-color (merge new-color (select-keys color [:id :file-id :name]))]
            (st/emit! (dwl/update-color updated-color file-id))))

        delete-color
        (fn []
          (if (empty? selected)
            (st/emit! (dwl/delete-color color))
            (do
              (st/emit! (dwc/start-undo-transaction))
              (apply st/emit! (map #(dwl/delete-color {:id %}) selected))
              (st/emit! (dwc/commit-undo-transaction)))))

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
        (fn [event]
          (when local?
            (let [pos (dom/get-client-position event)
                  top (:y pos)
                  left (+ 10 (:x pos))]
              (dom/prevent-default event)
              (swap! state assoc
                     :menu-open true
                     :top top
                     :left left))))]

    (mf/use-effect
      (mf/deps (:editing @state))
      #(when (:editing @state)
         (let [input (mf/ref-val input-ref)]
           (dom/select-text! input))
         nil))

    [:div.asset-list-item {:class-name (dom/classnames
                                         :selected (contains? selected (:id color)))
                           :on-context-menu on-context-menu
                           :on-click (when-not (:editing @state)
                                       click-color)}
     [:& bc/color-bullet {:color color}]

     (if (:editing @state)
       [:input.element-name
        {:type "text"
         :ref input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :default-value (:name color "")}]

       [:div.name-block {:on-double-click rename-color-clicked}
        (:name color)
        (when-not (= (:name color) default-name)
          [:span default-name])])
     (when local?
       [:& context-menu
         {:selectable false
          :show (:menu-open @state)
          :on-close #(swap! state assoc :menu-open false)
          :top (:top @state)
          :left (:left @state)
          :options [(when (<= (count selected) 1)
                      [(t locale "workspace.assets.rename") rename-color-clicked])
                    (when (<= (count selected) 1)
                      [(t locale "workspace.assets.edit") edit-color-clicked])
                    [(t locale "workspace.assets.delete") delete-color]]}])]))

(mf/defc colors-box
  [{:keys [file-id local? colors locale open? change-selected] :as props}]
  (let [state (mf/use-state {:selected empty-selection})

        selected      (:selected @state)

        add-color
        (mf/use-callback
         (mf/deps file-id)
         (fn [value opacity]
           (st/emit! (dwl/add-color value))))

        add-color-clicked
        (mf/use-callback
         (mf/deps file-id)
         (fn [event]
           (st/emitf (dwl/set-assets-box-open file-id :colors true))
           (modal/show! :colorpicker
                        {:x (.-clientX event)
                         :y (.-clientY event)
                         :on-accept add-color
                         :data {:color "#406280"
                                :opacity 1}
                         :position :right})))

        unselect-all
        (mf/use-callback
          (fn [event]
            (swap! state assoc :selected empty-selection)))

        on-select
        (mf/use-callback
          (mf/deps state)
          (fn [color-id]
            (fn [event]
              (dom/stop-propagation event)
              (swap! state update :selected
                     (fn [selected]
                       (cond
                         (kbd/ctrl? event)
                         (toggle-select selected color-id)

                         (kbd/shift? event)
                         (extend-select selected color-id {"" colors})))))))]

    (mf/use-effect
      (mf/deps [change-selected selected])
      #(change-selected (count selected)))

    [:div.asset-section {:on-click unselect-all}
     [:div.asset-title {:class (when (not open?) "closed")}
      [:span {:on-click (st/emitf (dwl/set-assets-box-open file-id :colors (not open?)))}
       i/arrow-slide (t locale "workspace.assets.colors")]
      [:span.num-assets (str "\u00A0(") (count colors) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.assets-button {:on-click add-color-clicked} i/plus])]
     (when open?
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
                            :selected selected
                            :on-select on-select
                            :locale locale}]))])]))


;; ---- Typography box ----

(mf/defc typography-box
  [{:keys [file file-id local? typographies locale open? change-selected] :as props}]

  (let [state (mf/use-state {:detail-open? false
                             :menu-open? false
                             :top nil
                             :left nil
                             :selected empty-selection})

        local    (deref refs/workspace-local)
        selected (:selected @state)

        add-typography
        (mf/use-callback
         (mf/deps file-id)
         (fn [value opacity]
           (st/emit! (dwl/add-typography txt/default-typography))))

        handle-change
        (mf/use-callback
         (mf/deps file-id)
         (fn [typography changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        handle-typography-selection
        (fn [typography event]
          (dom/stop-propagation event)

          (swap! state update :selected
                 (fn [selected]
                   (cond
                     (kbd/ctrl? event)
                     (toggle-select selected (:id typography))

                     (kbd/shift? event)
                     (extend-select selected (:id typography) {"" typographies}))))

          (let [ids (get-in @st/state [:workspace-local :selected])
                attrs (merge
                        {:typography-ref-file file-id
                         :typography-ref-id (:id typography)}
                        (d/without-keys typography [:id :name]))]
            (run! #(st/emit! (dwt/update-text-attrs {:id % :editor (get-in local [:editors %]) :attrs attrs}))
                  ids)))

        on-context-menu
        (fn [id event]

          (when local?
            (let [pos (dom/get-client-position event)
                  top (:y pos)
                  left (- (:x pos) 20)]
              (dom/prevent-default event)
              (swap! state assoc
                     :menu-open? true
                     :top top
                     :left left
                     :id id))))

        unselect-all
        (mf/use-callback
          (fn [event]
            (swap! state assoc :selected empty-selection)))

        closed-typography-edit
        (mf/use-callback
         (mf/deps file-id)
         (fn [event] ))

        handle-rename-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-local :rename-typography] (:id @state))))

        handle-edit-typography-clicked
        (fn []
          (st/emit! #(assoc-in % [:workspace-local :edit-typography] (:id @state))))

        handle-delete-typography
        (fn []
          (if (empty? selected)
            (st/emit! (dwl/delete-typography (:id @state)))
            (do
              (st/emit! (dwc/start-undo-transaction))
              (apply st/emit! (map #(dwl/delete-typography %) selected))
              (st/emit! (dwc/commit-undo-transaction)))))

        editting-id (or (:rename-typography local) (:edit-typography local))]

    (mf/use-effect
     (mf/deps local)
     (fn []
       (when (:rename-typography local)
         (st/emit! #(update % :workspace-local dissoc :rename-typography)))
       (when (:edit-typography local)
         (st/emit! #(update % :workspace-local dissoc :edit-typography)))))

    (mf/use-effect
      (mf/deps [change-selected selected])
      #(change-selected (count selected)))

    [:div.asset-section {:on-click unselect-all}
     [:div.asset-title {:class (when (not open?) "closed")}
      [:span {:on-click (st/emitf (dwl/set-assets-box-open file-id :typographies (not open?)))}
       i/arrow-slide (t locale "workspace.assets.typography")]
      [:span.num-assets (str "\u00A0(") (count typographies) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.assets-button {:on-click add-typography} i/plus])]

     [:& context-menu
      {:selectable false
       :show (:menu-open? @state)
       :on-close #(swap! state assoc :menu-open? false)
       :top (:top @state)
       :left (:left @state)
       :options [(when (<= (count selected) 1)
                   [(t locale "workspace.assets.rename") handle-rename-typography-clicked])
                 (when (<= (count selected) 1)
                   [(t locale "workspace.assets.edit") handle-edit-typography-clicked])
                 [(t locale "workspace.assets.delete") handle-delete-typography]]}]
     (when open?
       [:div.asset-list
        (for [typography typographies]
          [:& typography-entry
           {:key (:id typography)
            :typography typography
            :file file
            :read-only? (not local?)
            :on-context-menu #(on-context-menu (:id typography) %)
            :on-change #(handle-change typography %)
            :selected? (contains? selected (:id typography))
            :on-select #(handle-typography-selection typography %)
            :editting? (= editting-id (:id typography))
            :focus-name? (= (:rename-typography local) (:id typography))}])])]))


;; --- Assets toolbox ----

(defn file-colors-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :colors]))
                   (vals (get-in state [:workspace-libraries id :data :colors])))))
             st/state =))


(defn file-media-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :media]))
                   (vals (get-in state [:workspace-libraries id :data :media])))))
             st/state =))

(defn file-components-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :components]))
                   (vals (get-in state [:workspace-libraries id :data :components])))))
             st/state =))

(defn file-typography-ref
  [id]
  (l/derived (fn [state]
               (let [wfile (:workspace-file state)]
                 (if (= (:id wfile) id)
                   (vals (get-in wfile [:data :typographies]))
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
         (sort-by #(str/lower (:name %)) comp-fn))))

(mf/defc file-library
  [{:keys [file local?  default-open? filters locale] :as props}]
  (let [open-file       (mf/deref (open-file-ref (:id file)))
        open?           (-> open-file
                            :library
                            (d/nilv default-open?))
        open-box?       (fn [box]
                          (-> open-file
                              box
                              (d/nilv true)))
        shared?         (:is-shared file)
        router          (mf/deref refs/router)

        reverse-sort?   (mf/use-state false)
        listing-thumbs? (mf/use-state true)
        selected-count  (mf/use-state {:components 0
                                       :graphics 0
                                       :colors 0
                                       :typographies 0})

        toggle-open     (st/emitf (dwl/set-assets-box-open (:id file) :library (not open?)))

        change-selected-count (fn [asset-type cnt]
                                (swap! selected-count assoc asset-type cnt))

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
          (fn [event]
            (swap! reverse-sort? not)))

        toggle-listing
        (mf/use-callback
          (fn [event]
            (swap! listing-thumbs? not)))]

    [:div.tool-window {:on-context-menu #(dom/prevent-default %)}
     [:div.tool-window-bar.library-bar
      {:on-click toggle-open}
      [:div.collapse-library
       {:class (dom/classnames :open open?)}
       i/arrow-slide]

      (if local?
        [:*
          [:span (t locale "workspace.assets.file-library")]
          (when shared?
            [:span.tool-badge (t locale "workspace.assets.shared")])]
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
           [:span.selected-count
            (let [selected-count @selected-count
                  total (+ (:components selected-count)
                           (:graphics selected-count)
                           (:colors selected-count)
                           (:typographies selected-count))]
              (when (> total 0)
                (tr "workspace.assets.selected-count" (i18n/c total))))]
           [:div.listing-option-btn.first {:on-click toggle-sort}
            (if @reverse-sort?
              i/sort-descending
              i/sort-ascending)]
           [:div.listing-option-btn {:on-click toggle-listing}
            (if @listing-thumbs?
              i/listing-enum
              i/listing-thumbs)]]
          (when show-components?
            [:& components-box {:file-id (:id file)
                                :local? local?
                                :components components
                                :listing-thumbs? listing-thumbs?
                                :change-selected (partial change-selected-count
                                                          :components)
                                :open? (open-box? :components)}])
          (when show-graphics?
            [:& graphics-box {:file-id (:id file)
                              :local? local?
                              :objects media
                              :listing-thumbs? listing-thumbs?
                              :change-selected (partial change-selected-count
                                                        :graphics)
                              :open? (open-box? :graphics)}])
          (when show-colors?
            [:& colors-box {:file-id (:id file)
                            :local? local?
                            :locale locale
                            :colors colors
                            :open? (open-box? :colors)
                            :change-selected (partial change-selected-count
                                                      :colors)}])

          (when show-typography?
            [:& typography-box {:file file
                                :file-id (:id file)
                                :local? local?
                                :locale locale
                                :typographies typographies
                                :open? (open-box? :typographies)
                                :change-selected (partial change-selected-count
                                                          :typographies)}])

          (when (and (not show-components?) (not show-graphics?) (not show-colors?))
            [:div.asset-section
             [:div.asset-title (t locale "workspace.assets.not-found")]])]))]))


(mf/defc assets-toolbox
  []
  (let [libraries (->> (mf/deref refs/workspace-libraries)
                       (vals)
                       (remove :is-indirect))
        file      (mf/deref refs/workspace-file)
        locale    (mf/deref i18n/locale)
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
         (fn [event]
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
         (t locale "workspace.assets.assets")
         [:div.libraries-button {:on-click #(modal/show! :libraries-dialog {})}
          i/text-align-justify
          (t locale "workspace.assets.libraries")]]

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
         [:option {:value ":all"} (t locale "workspace.assets.box-filter-all")]
         [:option {:value ":components"} (t locale "workspace.assets.components")]
         [:option {:value ":graphics"} (t locale "workspace.assets.graphics")]
         [:option {:value ":colors"} (t locale "workspace.assets.colors")]
         [:option {:value ":typographies"} (t locale "workspace.assets.typography")]]]]

     [:div.libraries-wrapper
      [:& file-library
       {:file file
        :locale locale
        :local? true
        :default-open? true
        :filters @filters}]

      (for [file (->> libraries
                      (sort-by #(str/lower (:name %))))]
        [:& file-library
         {:key (:id file)
          :file file
          :local? false
          :locale locale
          :default-open? false
          :filters @filters}])]]))

