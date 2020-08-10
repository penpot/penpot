;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.assets
  (:require
   [okulary.core :as l]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.common.geom.point :as gpt]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.media :as di]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.colors :as dcol]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.dom :as dom]
   [uxbox.util.dom.dnd :as dnd]
   [uxbox.util.timers :as timers]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.colorpicker :refer [colorpicker most-used-colors]]
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
   [uxbox.main.ui.components.file-uploader :refer [file-uploader]]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]))

(defn matches-search
  [name search-term]
  (if (str/empty? search-term)
    true
    (let [st (str/trim (str/lower search-term))
          nm (str/trim (str/lower name))]
      (str/includes? nm st))))

(mf/defc modal-edit-color
  [{:keys [color-value on-accept on-cancel] :as ctx}]
  (let [state (mf/use-state {:current-color color-value})]
    (letfn [(accept [event]
              (dom/prevent-default event)
              (modal/hide!)
              (when on-accept (on-accept (:current-color @state))))

            (cancel [event]
              (dom/prevent-default event)
              (modal/hide!)
              (when on-cancel (on-cancel)))]

      [:div.modal-overlay.transparent
        [:div.modal-create-color
         [:h3.modal-create-color-title (tr "modal.create-color.new-color")]
         [:& colorpicker {:value (:current-color @state)
                          :colors (into-array @most-used-colors)
                          :disable-opacity true
                          :on-change #(swap! state assoc :current-color %)}]

         [:input.btn-primary {:type "button"
                              :value (tr "ds.button.save")
                              :on-click accept}]

         [:a.close {:href "#" :on-click cancel} i/close]]])))

(mf/defc graphics-box
  [{:keys [file-id media-objects] :as props}]
  (let [state (mf/use-state {:menu-open false
                             :top nil
                             :left nil
                             :object-id nil})

        file-input (mf/use-ref nil)

        add-graphic
        #(dom/click (mf/ref-val file-input))

        delete-graphic
        #(st/emit! (dw/delete-media-object (:object-id @state)))

        on-files-selected
        (fn [js-files]
          (st/emit! (dw/upload-media-objects file-id false js-files)))

        on-context-menu
        (fn [object-id]
          (fn [event]
            (let [pos (dom/get-client-position event)
                  top (:y pos)
                  left (- (:x pos) 20)]
              (dom/prevent-default event)
              (swap! state assoc :menu-open true
                     :top top
                     :left left
                     :object-id object-id))))

        on-drag-start
        (fn [uri]
          (fn [event]
            (dnd/set-data! event "text/uri-list" uri)
            (dnd/set-allowed-effect! event "move")))]

    [:div.asset-group
     [:div.group-title
      (tr "workspace.assets.graphics")
      [:span (str "\u00A0(") (count media-objects) ")"] ;; Unicode 00A0 is non-breaking space
      [:div.group-button {:on-click add-graphic}
       i/plus
       [:& file-uploader {:accept di/str-media-types
                          :multi true
                          :input-ref file-input
                          :on-selected on-files-selected}]]]
     [:div.group-grid
       (for [object (sort-by :name media-objects)]
         [:div.grid-cell {:key (:id object)
                          :draggable true
                          :on-context-menu (on-context-menu (:id object))
                          :on-drag-start (on-drag-start (:uri object))}
          [:img {:src (:thumb-uri object)
                 :draggable false}] ;; Also need to add css pointer-events: none
          [:div.cell-name (:name object)]])
       [:& context-menu
        {:selectable false
         :show (:menu-open @state)
         :on-close #(swap! state assoc :menu-open false)
         :top (:top @state)
         :left (:left @state)
         :options [[(tr "workspace.assets.delete") delete-graphic]]}]]]))


(mf/defc color-item
  [{:keys [color file-id] :as props}]
  (let [workspace-local @refs/workspace-local
        color-for-rename (:color-for-rename workspace-local)

        edit-input-ref (mf/use-ref)

        state (mf/use-state {:menu-open false
                             :top nil
                             :left nil
                             :editing (= color-for-rename (:id color))})

        rename-color
        (fn [name]
          (st/emit! (dcol/rename-color file-id (:id color) name)))

        edit-color
        (fn [value opacity]
          (st/emit! (dcol/update-color file-id (:id color) value)))

        delete-color
        (fn []
          (st/emit! (dcol/delete-color file-id (:id color))))

        rename-color-clicked
        (fn [event]
          (dom/prevent-default event)
          (swap! state assoc :editing true))

        input-blur
        (fn [event]
          (let [target (dom/event->target event)
                name (dom/get-value target)]
            (rename-color name)
            (st/emit! dcol/clear-color-for-rename)
            (swap! state assoc :editing false)))

        input-key-down
        (fn [event]
          (when (kbd/esc? event)
            (st/emit! dcol/clear-color-for-rename)
            (swap! state assoc :editing false))
          (when (kbd/enter? event)
            (input-blur event)))

        edit-color-clicked
        (fn [event]
          (modal/show! modal-edit-color
                       {:color-value (:content color)
                        :on-accept edit-color}))

        on-context-menu
        (fn [event]
          (let [pos (dom/get-client-position event)
                top (:y pos)
                left (- (:x pos) 20)]
            (dom/prevent-default event)
            (swap! state assoc
                   :menu-open true
                   :top top
                   :left left)))]

    (mf/use-effect
      (mf/deps (:editing @state))
      #(when (:editing @state)
         (let [edit-input (mf/ref-val edit-input-ref)]
           (dom/select-text! edit-input))
         nil))

    [:div.group-list-item {:on-context-menu on-context-menu}
     [:div.color-block {:style {:background-color (:content color)}}]
     (if (:editing @state)
       [:input.element-name
        {:type "text"
         :ref edit-input-ref
         :on-blur input-blur
         :on-key-down input-key-down
         :auto-focus true
         :default-value (:name color "")}]
       [:div.name-block
        {:on-double-click rename-color-clicked}
        (:name color)
        (when-not (= (:name color) (:content color))
          [:span (:content color)])])
     [:& context-menu
       {:selectable false
        :show (:menu-open @state)
        :on-close #(swap! state assoc :menu-open false)
        :top (:top @state)
        :left (:left @state)
        :options [[(tr "workspace.assets.rename") rename-color-clicked]
                  [(tr "workspace.assets.edit") edit-color-clicked]
                  [(tr "workspace.assets.delete") delete-color]]}]]))

(mf/defc colors-box
  [{:keys [file-id colors] :as props}]
  (let [add-color
        (fn [value opacity]
          (st/emit! (dcol/create-color file-id value)))

        add-color-clicked
        (fn [event]
          (modal/show! modal-edit-color
                       {:color-value "#406280"
                        :on-accept add-color}))]

    [:div.asset-group
     [:div.group-title
       (tr "workspace.assets.colors")
       [:span (str "\u00A0(") (count colors) ")"] ;; Unicode 00A0 is non-breaking space
       [:div.group-button {:on-click add-color-clicked} i/plus]]
     [:div.group-list
      (for [color (sort-by :name colors)]
        [:& color-item {:key (:id color)
                        :color color
                        :file-id file-id}])]]))

(mf/defc file-library-toolbox
  [{:keys [file-id
           shared?
           media-objects
           colors
           initial-open?
           search-term
           box-filter] :as props}]
  (let [open? (mf/use-state initial-open?)
        toggle-open #(swap! open? not)]
    [:div.tool-window
     [:div.tool-window-bar
      [:div.collapse-library
       {:class (classnames :open @open?)
        :on-click toggle-open}
       i/arrow-slide]
      [:span (tr "workspace.assets.file-library")]
      (when shared?
        [:span.tool-badge (tr "workspace.assets.shared")])]
     (when @open?
       (let [show-graphics (and (or (= box-filter :all) (= box-filter :graphics))
                                 (or (> (count media-objects) 0) (str/empty? search-term))) 
              show-colors (and (or (= box-filter :all) (= box-filter :colors))
                               (or (> (count colors) 0) (str/empty? search-term)))]
         [:div.tool-window-content
          (when show-graphics
            [:& graphics-box {:file-id file-id :media-objects media-objects}])
          (when show-colors
            [:& colors-box {:file-id file-id :colors colors}])
          (when (and (not show-graphics) (not show-colors))
            [:div.asset-group
             [:div.group-title (tr "workspace.assets.not-found")]])]))]))

(mf/defc assets-toolbox
  []
  (let [team-id (-> refs/workspace-project mf/deref :team-id)
        file (mf/deref refs/workspace-file)
        file-id (:id file)
        file-media (mf/deref refs/workspace-media-library)
        file-colors (mf/deref refs/workspace-colors-library)

        state (mf/use-state {:search-term ""
                             :box-filter :all})

        filtered-media-objects (filter #(matches-search (:name %) (:search-term @state))
                                       (vals file-media))

        filtered-colors (filter #(or (matches-search (:name %) (:search-term @state))
                                     (matches-search (:content %) (:search-term @state)))
                                (vals file-colors))

        on-search-term-change (fn [event]
                               (let [value (-> (dom/get-target event)
                                               (dom/get-value))]
                                 (swap! state assoc :search-term value)))

        on-search-clear-click (fn [event]
                                (swap! state assoc :search-term ""))

        on-box-filter-change (fn [event]
                               (let [value (-> (dom/get-target event)
                                               (dom/get-value)
                                               (d/read-string))]
                                 (swap! state assoc :box-filter value)))]

    (mf/use-effect
     (mf/deps file-id)
     #(when file-id
        (st/emit! (dw/fetch-media-library file-id))
        (st/emit! (dw/fetch-colors-library file-id))))

    [:div.assets-bar

     [:div.tool-window
       [:div.tool-window-content
        [:div.assets-bar-title (tr "workspace.assets.assets")]

        [:div.search-block
          [:input.search-input
           {:placeholder (tr "workspace.assets.search")
            :type "text"
            :value (:search-term @state)
            :on-change on-search-term-change}]
          (if (str/empty? (:search-term @state))
            [:div.search-icon
             i/search]
            [:div.search-icon.close
             {:on-click on-search-clear-click}
             i/close])]

        [:select.input-select {:value (:box-filter @state)
                               :on-change on-box-filter-change}
         [:option {:value ":all"} (tr "workspace.assets.box-filter-all")]
         [:option {:value ":graphics"} (tr "workspace.assets.box-filter-graphics")]
         [:option {:value ":colors"} (tr "workspace.assets.box-filter-colors")]]
        ]]

     [:& file-library-toolbox {:file-id file-id
                               :shared? (:is-shared file)
                               :media-objects filtered-media-objects
                               :colors filtered-colors
                               :initial-open? true
                               :search-term (:search-term @state)
                               :box-filter (:box-filter @state)}]]))

