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
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.state-helpers :as wsh]
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
  [{:keys [file-id local? components listing-thumbs? open? selected-assets
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [state (mf/use-state {:menu-open false
                             :renaming nil
                             :top nil
                             :left nil
                             :component-id nil
                             :folded-groups empty-folded-groups})

        selected-components (:components selected-assets)
        multi-components?   (> (count selected-components) 1)
        multi-assets?       (or (not (empty? (:graphics selected-assets)))
                                (not (empty? (:colors selected-assets)))
                                (not (empty? (:typographies selected-assets))))

        groups        (group-assets components)
        folded-groups (:folded-groups @state)

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
             (st/emit! (dwl/delete-component {:id (:component-id @state)})))
           (st/emit! (dwl/sync-file file-id file-id))))

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
                (let [pos (dom/get-client-position event)
                      top (:y pos)
                      left (- (:x pos) 20)]
                  (dom/prevent-default event)
                  (when-not (contains? selected-components component-id)
                    (on-clear-selection))
                  (swap! state assoc :menu-open true
                         :top top
                         :left left
                         :component-id component-id))))))

        create-group
        (mf/use-callback
          (mf/deps components selected-components on-clear-selection)
          (fn [name]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> components
                        (filter #(contains? selected-components (:id %)))
                        (map #(dwl/rename-component
                                (:id %)
                                (str name " / "
                                    (cp/merge-path-item (:path %) (:name %)))))))
            (st/emit! (dwu/commit-undo-transaction))))

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
          (mf/deps components selected-components)
          (fn [event]
            (dom/stop-propagation event)
            (modal/show! :create-group-dialog {:create create-group})))

        on-drag-start
        (mf/use-callback
         (fn [component event]
           (dnd/set-data! event "penpot/component" {:file-id file-id
                                                    :component component})
           (dnd/set-allowed-effect! event "move")))]

    [:div.asset-section
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
                                        :selected (contains? selected-components (:id component))
                                        :grid-cell @listing-thumbs?
                                        :enum-item (not @listing-thumbs?))
                          :draggable true
                          :on-click #(on-asset-click % (:id component) groups nil)
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
         :options [(when-not (or multi-components? multi-assets?)
                     [(tr "workspace.assets.rename") on-rename])
                   (when-not multi-assets?
                     [(tr "workspace.assets.duplicate") on-duplicate])
                   [(tr "workspace.assets.delete") on-delete]
                   (when-not multi-assets?
                     [(tr "workspace.assets.group") on-group])]}])]))


;; ---- Graphics box ----

(mf/defc graphics-box
  [{:keys [file-id local? objects listing-thumbs? open? selected-assets
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [input-ref  (mf/use-ref nil)
        state      (mf/use-state {:menu-open false
                                  :renaming nil
                                  :top nil
                                  :left nil
                                  :object-id nil
                                  :folded-groups empty-folded-groups})

        selected-objects    (:graphics selected-assets)
        multi-objects?      (> (count selected-objects) 1)
        multi-assets?       (or (not (empty? (:components selected-assets)))
                                (not (empty? (:colors selected-assets)))
                                (not (empty? (:typographies selected-assets))))

        groups        (group-assets objects)
        folded-groups (:folded-groups @state)

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
             (st/emit! (dw/upload-media-asset params)))))

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
                (let [pos (dom/get-client-position event)
                      top (:y pos)
                      left (- (:x pos) 20)]
                  (dom/prevent-default event)
                  (when-not (contains? selected-objects object-id)
                    (on-clear-selection))
                  (swap! state assoc :menu-open true
                         :top top
                         :left left
                         :object-id object-id))))))

        create-group
        (mf/use-callback
          (mf/deps objects selected-objects on-clear-selection)
          (fn [name]
            (on-clear-selection)
            (st/emit! (dwu/start-undo-transaction))
            (apply st/emit!
                   (->> objects
                        (filter #(contains? selected-objects (:id %)))
                        (map #(dwl/rename-media
                                (:id %)
                                (str name " / "
                                    (cp/merge-path-item (:path %) (:name %)))))))
            (st/emit! (dwu/commit-undo-transaction))))

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
          (mf/deps objects selected-objects)
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

    [:div.asset-section
     [:div.asset-title {:class (when (not open?) "closed")}
      [:span {:on-click (st/emitf (dwl/set-assets-box-open file-id :graphics (not open?)))}
       i/arrow-slide (tr "workspace.assets.graphics")]
      [:span.num-assets (str "\u00A0(") (count objects) ")"] ;; Unicode 00A0 is non-breaking space
      (when local?
        [:div.assets-button {:on-click add-graphic}
         i/plus
         [:& file-uploader {:accept cm/str-image-types
                            :multi true
                            :input-ref input-ref
                            :on-selected on-file-selected}]])]
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
                                      :selected (contains? selected-objects (:id object))
                                      :grid-cell @listing-thumbs?
                                      :enum-item (not @listing-thumbs?))
                        :draggable true
                        :on-click #(on-asset-click % (:id object) groups nil)
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
         :options [(when-not (or multi-objects? multi-assets?)
                     [(tr "workspace.assets.rename") on-rename])
                   [(tr "workspace.assets.delete") on-delete]
                   (when-not multi-assets?
                     [(tr "workspace.assets.group") on-group])]}])]))


;; ---- Colors box ----

(mf/defc color-item
  [{:keys [color local? file-id selected-colors multi-colors? multi-assets?
           on-asset-click on-assets-delete on-clear-selection colors locale] :as props}]
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

        apply-color
        (fn [color-id event]
          (let [ids (wsh/lookup-selected @st/state)]
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
        (mf/use-callback
         (mf/deps @state multi-colors? multi-assets?)
         (fn []
           (if (or multi-colors? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwl/delete-color color)))))

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
              (let [pos (dom/get-client-position event)
                    top (:y pos)
                    left (+ 10 (:x pos))]
                (dom/prevent-default event)
                (when-not (contains? selected-colors (:id color))
                  (on-clear-selection))
                (swap! state assoc
                       :menu-open true
                       :top top
                       :left left)))))]

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
                                       #(on-asset-click % (:id color) {"" colors}
                                                       (partial apply-color (:id color))))}
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
          :options [(when-not (or multi-colors? multi-assets?)
                      [(t locale "workspace.assets.rename") rename-color-clicked])
                    (when-not (or multi-colors? multi-assets?)
                      [(t locale "workspace.assets.edit") edit-color-clicked])
                    [(t locale "workspace.assets.delete") delete-color]]}])]))

(mf/defc colors-box
  [{:keys [file-id local? colors locale open? selected-assets
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [selected-colors     (:colors selected-assets)
        multi-colors?       (> (count selected-colors) 1)
        multi-assets?       (or (not (empty? (:components selected-assets)))
                                (not (empty? (:graphics selected-assets)))
                                (not (empty? (:typographies selected-assets))))

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
                         :position :right})))]

    [:div.asset-section
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
                            :selected-colors selected-colors
                            :multi-colors? multi-colors?
                            :multi-assets? multi-assets?
                            :on-asset-click on-asset-click
                            :on-assets-delete on-assets-delete
                            :on-clear-selection on-clear-selection
                            :colors colors
                            :locale locale}]))])]))


;; ---- Typography box ----

(mf/defc typography-box
  [{:keys [file file-id local? typographies locale open? selected-assets
           on-asset-click on-assets-delete on-clear-selection] :as props}]
  (let [state (mf/use-state {:detail-open? false
                             :menu-open? false
                             :top nil
                             :left nil
                             :id nil})

        local    (deref refs/workspace-local)

        selected-typographies (:typographies selected-assets)
        multi-typographies?   (> (count selected-typographies) 1)
        multi-assets?         (or (not (empty? (:graphics selected-assets)))
                                  (not (empty? (:colors selected-assets)))
                                  (not (empty? (:typographies selected-assets))))

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

        apply-typography
        (fn [typography event]
          (let [ids (wsh/lookup-selected @st/state)
                attrs (merge
                        {:typography-ref-file file-id
                         :typography-ref-id (:id typography)}
                        (d/without-keys typography [:id :name]))]
            (run! #(st/emit! (dwt/update-text-attrs {:id % :editor (get-in local [:editors %]) :attrs attrs}))
                  ids)))

        on-context-menu
        (mf/use-callback
          (mf/deps selected-typographies on-clear-selection)
          (fn [id event]
            (when local?
              (let [pos (dom/get-client-position event)
                    top (:y pos)
                    left (- (:x pos) 20)]
                (dom/prevent-default event)
                (when-not (contains? selected-typographies id)
                  (on-clear-selection))
                (swap! state assoc
                       :menu-open? true
                       :top top
                       :left left
                       :id id)))))

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
        (mf/use-callback
         (mf/deps @state multi-typographies? multi-assets?)
         (fn []
           (if (or multi-typographies? multi-assets?)
             (on-assets-delete)
             (st/emit! (dwl/delete-typography (:id @state))))))

        editting-id (or (:rename-typography local) (:edit-typography local))]

    (mf/use-effect
     (mf/deps local)
     (fn []
       (when (:rename-typography local)
         (st/emit! #(update % :workspace-local dissoc :rename-typography)))
       (when (:edit-typography local)
         (st/emit! #(update % :workspace-local dissoc :edit-typography)))))

    [:div.asset-section
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
       :options [(when-not (or multi-typographies? multi-assets?)
                   [(t locale "workspace.assets.rename") handle-rename-typography-clicked])
                 (when-not (or multi-typographies? multi-assets?)
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
            :selected? (contains? selected-typographies (:id typography))
            :on-click  #(on-asset-click % (:id typography) {"" typographies}
                                        (partial apply-typography typography))
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
          (fn [event]
            (swap! reverse-sort? not)))

        toggle-listing
        (mf/use-callback
          (fn [event]
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
          (fn [asset-type asset-id asset-groups]
            (swap! selected-assets update asset-type
                   (fn [selected]
                     (let [all-assets   (-> asset-groups vals flatten)
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
                            set))))))

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
          (fn [asset-type event asset-id all-assets default-click]
            (cond
              (kbd/ctrl? event)
              (do
                (dom/stop-propagation event)
                (toggle-selected-asset asset-type asset-id))

              (kbd/shift? event)
              (do
                (dom/stop-propagation event)
                (extend-selected-assets asset-type asset-id all-assets))

              :else
              (when default-click
                (default-click event)))))

        on-assets-delete
        (mf/use-callback
          (mf/deps @selected-assets)
          (fn []
            (do
              (st/emit! (dwu/start-undo-transaction))
              (apply st/emit! (map #(dwl/delete-component {:id %})
                                   (:components @selected-assets)))
              (apply st/emit! (map #(dwl/delete-media {:id %})
                                   (:graphics @selected-assets)))
              (apply st/emit! (map #(dwl/delete-color {:id %})
                                   (:colors @selected-assets)))
              (apply st/emit! (map #(dwl/delete-typography %)
                                   (:typographies @selected-assets)))
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
           (when (> selected-count 0)
             [:span.selected-count
              (tr "workspace.assets.selected-count" (i18n/c selected-count))])
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
                                :open? (open-box? :components)
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
                              :selected-assets @selected-assets
                              :on-asset-click (partial on-asset-click :graphics)
                              :on-assets-delete on-assets-delete
                              :on-clear-selection unselect-all}])
          (when show-colors?
            [:& colors-box {:file-id (:id file)
                            :local? local?
                            :locale locale
                            :colors colors
                            :open? (open-box? :colors)
                            :selected-assets @selected-assets
                            :on-asset-click (partial on-asset-click :colors)
                            :on-assets-delete on-assets-delete
                            :on-clear-selection unselect-all}])

          (when show-typography?
            [:& typography-box {:file file
                                :file-id (:id file)
                                :local? local?
                                :locale locale
                                :typographies typographies
                                :open? (open-box? :typographies)
                                :selected-assets @selected-assets
                                :on-asset-click (partial on-asset-click :typographies)
                                :on-assets-delete on-assets-delete
                                :on-clear-selection unselect-all}])

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

