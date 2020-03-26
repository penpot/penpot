;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.libraries
  (:require
   [lentes.core :as l]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.workspace.sortable :refer [use-sortable]]
   [uxbox.util.dom :as dom]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
   [uxbox.main.data.library :as dlib]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]))

;; --- Refs

(def project-ref
  (-> (l/key :workspace-project)
      (l/derive st/state)))

(defn libraries-ref [section]
  (-> (comp (l/key :library) (l/key section))
      (l/derive st/state)))

(defn selected-items-ref [section library-id]
  (-> (comp (l/key :library-items) (l/key section) (l/key library-id))
      (l/derive st/state)))

(defn selected-library-ref [section]
  (-> (comp (l/key :library-selected) (l/key section))
      (l/derive st/state)))

(defn selected-filter-ref [section]
  (-> (comp (l/key :library-filter) (l/key section))
      (l/derive st/state)))

;; --- Components

(mf/defc library-tab [{:keys [libraries section]}]
  (when (and libraries (-> libraries count (> 0)))
    (let [first-id (-> libraries first :id)
          current-selection (or (mf/deref (selected-library-ref section)) first-id)]

      ;; Check if the current selection is in the list of libraries
      (mf/use-effect
       (mf/deps libraries)
       #(when (not (some (fn [it] (= current-selection (-> it :id))) libraries))
          (st/emit! (dlib/select-library section first-id))))

      ;; Retrieve the library data given the current selected library
      (mf/use-effect
       (mf/deps current-selection)
       #(st/emit! (dlib/retrieve-library-data section current-selection)))

      [:div.library-tab
       {:class (classnames :icons-tab (= section :icons)
                           :images-tab (= section :images))}
       [:select.input-select.library-tab-libraries
        {:on-change #(st/emit! (dlib/select-library section (-> % dom/get-target dom/get-value uuid)))}
        (for [library libraries]
          [:option.library-tab-libraries-item
           {:key (:id library)
            :value (:id library)
            :selected (= current-selection (:id library))}
           (:name library)])]
       [:div.library-tab-content
        (let [items (mf/deref (selected-items-ref section current-selection))]
          (for [item items]
            [:div.library-tab-element
             {:key (:id item)
              :on-click #(st/emit! (dw/select-for-drawing :icon item))}
             (if (= section :icons)
               [:* ;; ICONS
                [:svg {:view-box (->> item :metadata :view-box (str/join " "))
                       :width (-> item :metadata :width)
                       :height (-> item :metadat :height) 
                       :dangerouslySetInnerHTML {:__html (:content item)}}]
                [:span.library-tab-element-name (:name item)]]

               [:* ;; IMAGES
                [:img {:src (:thumb-uri item)}]
                [:span.library-tab-element-name (:name item)]])]))]])))

(mf/defc libraries-toolbox
  [{:keys [key]}]
  (let [state (mf/use-state {:menu-open false})
        selected-filter (fn [section] (or (mf/deref (selected-filter-ref section)) :all))
        team-id (-> project-ref mf/deref :team-id)
        locale (i18n/use-locale)

        filter-to-str {:all "All libraries"
                       :own "My libraries"
                       :store "Store libraries"}

        select-option
        (fn [option]
          (st/emit!
           (dlib/change-library-filter :icons option)
           (dlib/change-library-filter :images option)))

        filter-libraries
        (fn [section libraries]
          (case (selected-filter section)
            :all (-> libraries vals flatten)
            :own (libraries team-id)
            :store (libraries uuid/zero)))

        get-libraries
        (fn [section] (->> (libraries-ref section)
                           mf/deref
                           (filter-libraries section)))]

    (mf/use-effect
     (mf/deps team-id)
     #(when team-id
        (st/emit! (dlib/retrieve-libraries :icons)
                  (dlib/retrieve-libraries :images)
                  (dlib/retrieve-libraries :icons team-id)
                  (dlib/retrieve-libraries :images team-id))))

    [:div#libraries.tool-window
     [:div.libraries-window-bar
      [:div.libraries-window-bar-title "Libraries"]
      [:div.libraries-window-bar-options
       {:on-click #(swap! state assoc :menu-open true)}
       (filter-to-str (selected-filter :icons))
       [:button
        {
         :type "button"}
        i/arrow-slide
        [:& context-menu
         {:selectable true
          :show (:menu-open @state)
          :selected (filter-to-str (selected-filter :icons))
          :on-close #(swap! state assoc :menu-open false)
          :options (mapv (fn [[key val]] [val #(select-option key)]) filter-to-str)}]]]]

     [:div.tool-window-content
      [:& tab-container {}
       [:& tab-element
        {:id :icons :title "Icons"}
        [:& library-tab {:section :icons
                         :libraries (get-libraries :icons) }]]

       [:& tab-element
        {:id :images :title "Images"}
        [:& library-tab {:section :images
                         :libraries (get-libraries :images)}]]]]]))


