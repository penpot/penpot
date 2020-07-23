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
   [uxbox.main.data.workspace :as dw]
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
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
   [uxbox.main.data.library :as dlib]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]))

(defn matches-search
  [name search-term]
  (if (empty? search-term)
    true
    (let [st (str/trim (str/lower search-term))
          nm (str/trim (str/lower name))]
      (str/includes? nm st))))

(mf/defc graphics-box
  [{:keys [library-id images] :as props}]
  (let [state (mf/use-state {:menu-open false
                             :top nil
                             :left nil
                             :image-id nil})

        add-graphic #(println "añadir gráfico")

        delete-graphic
        #(st/emit! (dw/delete-file-image library-id (:image-id @state)))

        on-context-menu (fn [image-id]
                          (fn [event]
                            (let [pos (dom/get-client-position event)
                                  top (:y pos)
                                  left (- (:x pos) 20)]
                              (dom/prevent-default event)
                              (swap! state assoc :menu-open true
                                                 :top top
                                                 :left left
                                                 :image-id image-id))))]

    [:div.asset-group
     [:div.group-title
      (tr "workspace.assets.graphics")
      [:span (str "\u00A0(") (count images) ")"] ;; Unicode 00A0 is non-breaking space
      [:div.group-button {:on-click add-graphic} i/plus]]
     [:div.group-grid
       (for [image (sort-by :name images)]
         [:div.grid-cell {:key (:id image)
                          :on-context-menu (on-context-menu (:id image))}
          [:img {:src (:thumb-uri image)}]
          [:div.cell-name (:name image)]])
       [:& context-menu
        {:selectable false
         :show (:menu-open @state)
         :on-close #(swap! state assoc :menu-open false)
         :top (:top @state)
         :left (:left @state)
         :options [[(tr "workspace.assets.delete") delete-graphic]]}]]
       ]))

(mf/defc colors-box
  [{:keys [colors] :as props}]
  (let [add-color #(println "añadir color")]
    [:div.asset-group
     [:div.group-title
       (tr "workspace.assets.colors")
       [:div.group-button {:on-click add-color} i/plus]]]))

(mf/defc library-toolbox
  [{:keys [library-id images initial-open? search-term box-filter] :as props}]
  (let [open? (mf/use-state initial-open?)
        toggle-open #(swap! open? not)]
    [:div.tool-window
     [:div.tool-window-bar
      [:div.collapse-library
       {:class (classnames :open @open?)
        :on-click toggle-open}
       i/arrow-slide]
      [:span (tr "workspace.assets.file-library")]]
     (when @open?
       [:div.tool-window-content
        (when (or (= box-filter :all) (= box-filter :graphics))
          [:& graphics-box {:library-id library-id :images images}])
        (when (or (= box-filter :all) (= box-filter :colors))
          [:& colors-box {:colors {}}])])]))

(mf/defc assets-toolbox
  []
  (let [team-id (-> refs/workspace-project mf/deref :team-id)
        file-id (-> refs/workspace-file mf/deref :id)
        file-images (mf/deref refs/workspace-images)

        state (mf/use-state {:search-term ""
                             :box-filter :all})

        filtered-images (filter #(matches-search (:name %) (:search-term @state))
                                (vals file-images))

        on-search-term-change (fn [event]
                               (let [value (-> (dom/get-target event)
                                               (dom/get-value))]
                                 (swap! state assoc :search-term value)))

        on-box-filter-change (fn [event]
                               (let [value (-> (dom/get-target event)
                                               (dom/get-value)
                                               (d/read-string))]
                                 (swap! state assoc :box-filter value)))]

    (mf/use-effect
     (mf/deps file-id)
     #(when file-id
        (st/emit! (dw/fetch-images file-id))))

    [:div.assets-bar

     [:div.tool-window
       [:div.tool-window-content
        [:div.assets-bar-title (tr "workspace.assets.assets")]

        [:input.search-input
         {:placeholder (tr "workspace.assets.search")
          :type "text"
          :value (:search-term @state)
          :on-change on-search-term-change}]

        [:select.input-select {:value (:box-filter @state)
                               :on-change on-box-filter-change}
         [:option {:value ":all"} (tr "workspace.assets.box-filter-all")]
         [:option {:value ":graphics"} (tr "workspace.assets.box-filter-graphics")]
         [:option {:value ":colors"} (tr "workspace.assets.box-filter-colors")]]
        ]]

     [:& library-toolbox {:library-id file-id
                          :images filtered-images
                          :initial-open? true
                          :search-term (:search-term @state)
                          :box-filter (:box-filter @state)}]]))

