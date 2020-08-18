;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.assets
  (:require
   [okulary.core :as l]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.config :as cfg]
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.pages :as cp]
   [app.common.geom.shapes :as geom]
   [app.common.geom.point :as gpt]
   [app.main.ui.icons :as i]
   [app.main.data.workspace :as dw]
   [app.main.data.colors :as dcol]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.shapes.icon :as icon]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.timers :as timers]
   [app.common.uuid :as uuid]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.data :refer [classnames matches-search]]
   [app.util.router :as rt]
   [app.main.ui.modal :as modal]
   [app.main.ui.colorpicker :refer [colorpicker most-used-colors]]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.workspace.libraries :refer [libraries-dialog]]))

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
  [{:keys [file-id local-library? media-objects] :as props}]
  (let [state (mf/use-state {:menu-open false
                             :top nil
                             :left nil
                             :object-id nil})

        file-input (mf/use-ref nil)

        add-graphic
        #(dom/click (mf/ref-val file-input))

        delete-graphic
        #(st/emit! (dw/delete-media-object file-id (:object-id @state)))

        on-files-selected
        (fn [js-files]
          (let [params {:file-id file-id
                        :local? false
                        :js-files js-files}]
            (st/emit! (dw/upload-media-objects params))))

        on-context-menu
        (fn [object-id]
          (fn [event]
            (when local-library?
              (let [pos (dom/get-client-position event)
                    top (:y pos)
                    left (- (:x pos) 20)]
                (dom/prevent-default event)
                (swap! state assoc :menu-open true
                       :top top
                       :left left
                       :object-id object-id)))))

        on-drag-start
        (fn [path event]
          (dnd/set-data! event "text/uri-list" (cfg/resolve-media-path path))
          (dnd/set-allowed-effect! event "move"))]

    [:div.asset-group
     [:div.group-title
      (tr "workspace.assets.graphics")
      [:span (str "\u00A0(") (count media-objects) ")"] ;; Unicode 00A0 is non-breaking space
      (when local-library?
        [:div.group-button {:on-click add-graphic}
         i/plus
         [:& file-uploader {:accept cm/str-media-types
                            :multi true
                            :input-ref file-input
                            :on-selected on-files-selected}]])]
     [:div.group-grid
      (for [object media-objects]
        [:div.grid-cell {:key (:id object)
                         :draggable true
                         :on-context-menu (on-context-menu (:id object))
                         :on-drag-start (partial on-drag-start (:path object))}
         [:img {:src (cfg/resolve-media-path (:thumb-path object))
                :draggable false}] ;; Also need to add css pointer-events: none
         [:div.cell-name (:name object)]])

      (when local-library?
        [:& context-menu
         {:selectable false
          :show (:menu-open @state)
          :on-close #(swap! state assoc :menu-open false)
          :top (:top @state)
          :left (:left @state)
          :options [[(tr "workspace.assets.delete") delete-graphic]]}])]]))


(mf/defc color-item
  [{:keys [color file-id local-library?] :as props}]
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
          (when local-library?
            (let [pos (dom/get-client-position event)
                  top (:y pos)
                  left (- (:x pos) 20)]
              (dom/prevent-default event)
              (swap! state assoc
                     :menu-open true
                     :top top
                     :left left))))]

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
     (when local-library?
       [:& context-menu
         {:selectable false
          :show (:menu-open @state)
          :on-close #(swap! state assoc :menu-open false)
          :top (:top @state)
          :left (:left @state)
          :options [[(tr "workspace.assets.rename") rename-color-clicked]
                    [(tr "workspace.assets.edit") edit-color-clicked]
                    [(tr "workspace.assets.delete") delete-color]]}])]))

(mf/defc colors-box
  [{:keys [file-id local-library? colors] :as props}]
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
       (when local-library?
         [:div.group-button {:on-click add-color-clicked} i/plus])]
     [:div.group-list
      (for [color colors]
        [:& color-item {:key (:id color)
                        :color color
                        :file-id file-id
                        :local-library? local-library?}])]]))

(mf/defc file-library-toolbox
  [{:keys [library
           local-library?
           shared?
           media-objects
           colors
           initial-open?
           search-term
           box-filter] :as props}]
  (let [open? (mf/use-state initial-open?)
        toggle-open #(swap! open? not)
        router (mf/deref refs/router)
        library-url (rt/resolve router :workspace
                                {:project-id (:project-id library)
                                 :file-id (:id library)}
                                {:page-id (first (:pages library))})]
    [:div.tool-window
     [:div.tool-window-bar
      [:div.collapse-library
       {:class (classnames :open @open?)
        :on-click toggle-open}
       i/arrow-slide]
      (if local-library?
        [:*
          [:span (tr "workspace.assets.file-library")]
          (when shared?
            [:span.tool-badge (tr "workspace.assets.shared")])]
        [:*
          [:span (:name library)]
          [:span.tool-link
           [:a {:href (str "#" library-url) :target "_blank"} i/chain]]])]
     (when @open?
       (let [show-graphics (and (or (= box-filter :all) (= box-filter :graphics))
                                (or (> (count media-objects) 0) (str/empty? search-term)))
             show-colors (and (or (= box-filter :all) (= box-filter :colors))
                              (or (> (count colors) 0) (str/empty? search-term)))]
         [:div.tool-window-content
          (when show-graphics
            [:& graphics-box {:file-id (:id library)
                              :local-library? local-library?
                              :media-objects media-objects}])
          (when show-colors
            [:& colors-box {:file-id (:id library)
                            :local-library? local-library?
                            :colors colors}])
          (when (and (not show-graphics) (not show-colors))
            [:div.asset-group
             [:div.group-title (tr "workspace.assets.not-found")]])]))]))

(mf/defc assets-toolbox
  []
  (let [team-id (-> refs/workspace-project mf/deref :team-id)
        file (mf/deref refs/workspace-file)
        libraries (mf/deref refs/workspace-libraries)
        sorted-libraries (->> (vals libraries)
                              (sort-by #(str/lower (:name %))))

        state (mf/use-state {:search-term ""
                             :box-filter :all})

        filtered-media-objects (fn [library-id]
                                 (as-> libraries $$
                                   (assoc $$ (:id file) file)
                                   (get-in $$ [library-id :media-objects])
                                   (filter #(matches-search (:name %) (:search-term @state)) $$)
                                   (sort-by #(str/lower (:name %)) $$)))

        filtered-colors (fn [library-id]
                          (as-> libraries $$
                            (assoc $$ (:id file) file)
                            (get-in $$ [library-id :colors])
                            (filter #(or (matches-search (:name %) (:search-term @state))
                                         (matches-search (:content %) (:search-term @state))) $$)
                            (sort-by #(str/lower (:name %)) $$)))

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

    [:div.assets-bar

     [:div.tool-window
       [:div.tool-window-content
        [:div.assets-bar-title
         (tr "workspace.assets.assets")
         [:div.libraries-button {:on-click #(modal/show! libraries-dialog {})}
          i/libraries
          (tr "workspace.assets.libraries")]]

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
         [:option {:value ":colors"} (tr "workspace.assets.box-filter-colors")]]]]

     [:& file-library-toolbox {:key (:id file)
                               :library file
                               :local-library? true
                               :shared? (:is-shared file)
                               :media-objects (filtered-media-objects (:id file))
                               :colors (filtered-colors (:id file))
                               :initial-open? true
                               :search-term (:search-term @state)
                               :box-filter (:box-filter @state)}]
     (for [library sorted-libraries]
       [:& file-library-toolbox {:key (:id library)
                                 :library library
                                 :local-library? false
                                 :shared? (:is-shared library)
                                 :media-objects (filtered-media-objects (:id library))
                                 :colors (filtered-colors (:id library))
                                 :initial-open? false
                                 :search-term (:search-term @state)
                                 :box-filter (:box-filter @state)}])]))

