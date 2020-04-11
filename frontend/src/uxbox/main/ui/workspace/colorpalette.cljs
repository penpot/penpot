;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.colorpalette
  (:require
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.colors :as udc]
   [uxbox.main.data.library :as dlib]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.util.color :refer [hex->rgb]]
   [uxbox.util.dom :as dom]))

;; --- Refs

(def libraries-ref
  (l/derived #(get-in % [:library :palettes]) st/state))

(defn selected-items-ref
  [library-id]
  (->> #(get-in % [:library-items :palettes library-id])
       (l/derived st/state)))

(def selected-library-ref
  (-> #(get-in % [:library-selected :palettes])
      (l/derived st/state)))

;; --- Components

(mf/defc palette-item
  [{:keys [color] :as props}]
  (let [rgb-vec (hex->rgb color)
        select-color
        (fn [event]
          (if (kbd/shift? event)
            (st/emit! (udw/update-selected-shapes {:stroke-color color}))
            (st/emit! (udw/update-selected-shapes {:fill-color color}))))]

    [:div.color-cell {:key (str color)
                      :on-click select-color}
     [:span.color {:style {:background color}}]
     [:span.color-text color]]))

(mf/defc palette
  [{:keys [libraries left-sidebar?] :as props}]

  (when (and libraries (-> libraries count (> 0)))
    (let [current-selection (or (mf/deref selected-library-ref) (-> libraries first :id))
          state (mf/use-state {:show-menu false })]
      (mf/use-effect
       (mf/deps current-selection)
       #(st/emit! (dlib/retrieve-library-data :palettes current-selection)))

      (let [items (-> current-selection selected-items-ref  mf/deref)
            doc-width (.. js/document -documentElement -clientWidth)
            width (:width @state (* doc-width 0.84))
            offset (:offset @state 0)
            visible (/ width 86)
            invisible (- (count items) visible)
            close #(st/emit! (udw/toggle-layout-flag :colorpalette))
            container (mf/use-ref nil)
            container-child (mf/use-ref nil)

            on-left-arrow-click
            (fn [event]
              (when (> offset 0)
                (let [element (mf/ref-val container-child)]
                  (swap! state update :offset dec))))

            on-right-arrow-click
            (fn [event]
              (when (< offset invisible)
                (let [element (mf/ref-val container-child)]
                  (swap! state update :offset inc))))

            on-scroll
            (fn [event]
              (if (pos? (.. event -nativeEvent -deltaY))
                (on-right-arrow-click event)
                (on-left-arrow-click event)))

            after-render
            (fn []
              (let [dom (mf/ref-val container)
                    width (.-clientWidth dom)]
                (when (not= (:width @state) width)
                  (swap! state assoc :width width))))

            handle-click
            (fn [library]
              (st/emit! (dlib/select-library :palettes (:id library))))]

        (mf/use-effect nil after-render)

        [:div.color-palette {:class (when left-sidebar? "left-sidebar-open")}
         [:& context-menu {:selectable true
                           :selected (->> libraries (filter #(= (:id %) current-selection)) first :name)
                           :show (:show-menu @state)
                           :on-close #(swap! state assoc :show-menu false)
                           :options (mapv #(vector (:name %) (partial handle-click %)) libraries)} ]
         [:div.color-palette-actions
          {:on-click #(swap! state assoc :show-menu true)}
          [:div.color-palette-actions-button i/actions]]

         [:span.left-arrow {:on-click on-left-arrow-click} i/arrow-slide]

         [:div.color-palette-content {:ref container :on-wheel on-scroll}
          [:div.color-palette-inside {:ref container-child
                                      :style {:position "relative"
                                              :width (str (* 86 (count items)) "px")
                                              :right (str (* 86 offset) "px")}}
           (for [item items]
             [:& palette-item {:color (:content item) :key (:id item)}])]]

         [:span.right-arrow {:on-click on-right-arrow-click} i/arrow-slide]]))))

(mf/defc colorpalette
  [{:keys [left-sidebar?]}]
  (let [team-id (-> refs/workspace-project mf/deref :team-id)
        libraries (-> libraries-ref mf/deref vals flatten)]
    (mf/use-effect #(st/emit! (dlib/retrieve-libraries :palettes)
                              (dlib/retrieve-libraries :palettes team-id)))
    [:& palette {:left-sidebar? left-sidebar?
                 :libraries libraries}]))
