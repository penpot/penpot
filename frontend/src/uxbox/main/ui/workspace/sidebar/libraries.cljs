;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

;; (ns uxbox.main.ui.workspace.sidebar.libraries
;;   (:require
;;    [okulary.core :as l]
;;    [cuerdas.core :as str]
;;    [rumext.alpha :as mf]
;;    [uxbox.common.data :as d]
;;    [uxbox.common.pages :as cp]
;;    [uxbox.common.geom.shapes :as geom]
;;    [uxbox.common.geom.point :as gpt]
;;    [uxbox.main.ui.icons :as i]
;;    [uxbox.main.data.workspace :as dw]
;;    [uxbox.main.refs :as refs]
;;    [uxbox.main.store :as st]
;;    [uxbox.main.ui.keyboard :as kbd]
;;    [uxbox.main.ui.shapes.icon :as icon]
;;    [uxbox.util.dom :as dom]
;;    [uxbox.util.dom.dnd :as dnd]
;;    [uxbox.util.timers :as timers]
;;    [uxbox.common.uuid :as uuid]
;;    [uxbox.util.i18n :as i18n :refer [tr]]
;;    [uxbox.util.data :refer [classnames]]
;;    [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]
;;    [uxbox.main.data.library :as dlib]
;;    [uxbox.main.ui.components.context-menu :refer [context-menu]]))
;;
;; ;; --- Refs
;;
;; (defn libraries-ref [section]
;;   (-> (l/in [:library section])
;;       (l/derived st/state)))
;;
;; (defn selected-items-ref [section library-id]
;;   (-> (l/in [:library-items section library-id])
;;       (l/derived st/state)))
;;
;; (defn selected-library-ref [section]
;;   (-> (l/in [:library-selected section])
;;       (l/derived st/state)))
;;
;; (defn selected-filter-ref [section]
;;   (-> (l/in [:library-filter section])
;;       (l/derived st/state)))
;;
;; (defmulti shape-from-item (fn [type _] type))
;;
;; (defmethod shape-from-item :icons [_ item]
;;   (-> (cp/make-minimal-shape :icon)
;;       (merge item)
;;       (geom/resize (-> item :metadata :width) (-> item :metadata :height))
;;       (geom/absolute-move (gpt/point 0 0))))
;;
;; (defmethod shape-from-item :images [_ item]
;;   (let [metadata (select-keys item [:width :height :thumb-width
;;                                     :thumb-height :thumb-uri :uri])]
;;     (-> (cp/make-minimal-shape :image)
;;         (merge item)
;;         (assoc :metadata metadata)
;;         (geom/resize (-> item :width) (-> item :height))
;;         (geom/absolute-move (gpt/point 0 0)))))
;;
;; ;; --- Components
;;
;; (mf/defc library-tab [{:keys [libraries section]}]
;;   (when (and libraries (-> libraries count (> 0)))
;;     (let [state (mf/use-state {:drag-style false})
;;           first-id (-> libraries first :id)
;;           current-selection (or (mf/deref (selected-library-ref section)) first-id)]
;;
;;       ;; Check if the current selection is in the list of libraries
;;       (mf/use-effect
;;        (mf/deps libraries)
;;        #(when (not (some (fn [it] (= current-selection (-> it :id))) libraries))
;;           (st/emit! (dlib/select-library section first-id))))
;;
;;       ;; Retrieve the library data given the current selected library
;;       (mf/use-effect
;;        (mf/deps current-selection)
;;        #(st/emit! (dlib/retrieve-library-data section current-selection)))
;;
;;       [:div.library-tab
;;        {:class (classnames :icons-tab (= section :icons)
;;                            :images-tab (= section :images))}
;;        [:select.input-select.library-tab-libraries
;;         {:value current-selection
;;          :on-change #(st/emit! (dlib/select-library section (-> % dom/get-target dom/get-value uuid)))}
;;         (for [library libraries]
;;           [:option.library-tab-libraries-item
;;            {:key (:id library)
;;             :value (:id library)}
;;            (:name library)])]
;;        [:div.library-tab-content
;;         (let [items (mf/deref (selected-items-ref section current-selection))]
;;           (for [item items]
;;             [:div.library-tab-element
;;              {:draggable true
;;               :class (classnames :is-dragging (:drag-style @state))
;;               :key (str (:id item))
;;               :on-drag-start (fn [event]
;;                                (swap! state assoc :drag-style true)
;;                                (dnd/set-data! event "uxbox/shape" (shape-from-item section item))
;;                                (dnd/set-allowed-effect! event "move")
;;                                ;; This state is so we can give custom css to the dragging
;;                                (timers/schedule #(swap! state assoc :drag-style false)))}
;;              (if (= section :icons)
;;                [:svg {:view-box (->> item :metadata :view-box (str/join " "))
;;                       :width (-> item :metadata :width)
;;                       :height (-> item :metadata :height)
;;                       :dangerouslySetInnerHTML {:__html (:content item)}}]
;;                [:img {:draggable false
;;                       :src (:thumb-uri item)}])
;;              [:span.library-tab-element-name (:name item)]]))]])))
;;
;; (mf/defc libraries-toolbox
;;   [{:keys [key]}]
;;   (let [state (mf/use-state {:menu-open false})
;;         selected-filter (fn [section] (or (mf/deref (selected-filter-ref section)) :all))
;;         team-id (-> refs/workspace-project mf/deref :team-id)
;;
;;         filter-to-str {:all (tr "workspace.library.all")
;;                        :own (tr "workspace.library.own")
;;                        :store (tr "workspace.library.store")}
;;
;;         select-option
;;         (fn [option]
;;           (st/emit!
;;            (dlib/change-library-filter :icons option)
;;            (dlib/change-library-filter :images option)))
;;
;;         filter-libraries
;;         (fn [section libraries]
;;           (case (selected-filter section)
;;             :all (-> libraries vals flatten)
;;             :own (libraries team-id)
;;             :store (libraries uuid/zero)))
;;
;;         get-libraries
;;         (fn [section] (->> (libraries-ref section)
;;                            mf/deref
;;                            (filter-libraries section)))]
;;
;;     (mf/use-effect
;;      (mf/deps team-id)
;;      #(when team-id
;;         (st/emit! (dlib/retrieve-libraries :icons)
;;                   (dlib/retrieve-libraries :images)
;;                   (dlib/retrieve-libraries :icons team-id)
;;                   (dlib/retrieve-libraries :images team-id))))
;;
;;     [:div#libraries.tool-window
;;      [:div.libraries-window-bar
;;       [:div.libraries-window-bar-title (tr "workspace.library.libraries")]
;;       [:div.libraries-window-bar-options
;;        {:on-click #(swap! state assoc :menu-open true)}
;;        (filter-to-str (selected-filter :icons))
;;        [:button
;;         {
;;          :type "button"}
;;         i/arrow-slide
;;         [:& context-menu
;;          {:selectable true
;;           :show (:menu-open @state)
;;           :selected (filter-to-str (selected-filter :icons))
;;           :on-close #(swap! state assoc :menu-open false)
;;           :options (mapv (fn [[key val]] [val #(select-option key)]) filter-to-str)}]]]]
;;
;;      [:div.tool-window-content
;;       [:& tab-container {}
;;        [:& tab-element
;;         {:id :icons :title (tr "workspace.library.icons")}
;;         [:& library-tab {:section :icons
;;                          :libraries (get-libraries :icons) }]]
;;
;;        [:& tab-element
;;         {:id :images :title (tr "workspace.library.images")}
;;         [:& library-tab {:section :images
;;                          :libraries (get-libraries :images)}]]]]]))
;;
;;
