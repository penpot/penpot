;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpalette
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [app.common.math :as mth]
   ;; [app.main.data.library :as dlib]
   [app.main.data.workspace :as udw]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.color :refer [hex->rgb]]
   [app.util.dom :as dom]
   [app.util.object :as obj]))

;; --- Refs

(def palettes-ref
  (-> (l/in [:library :palettes])
      (l/derived st/state)))

(def selected-palette-ref
  (-> (l/in [:library-selected :palettes])
      (l/derived st/state)))

(defn- make-selected-palette-item-ref
  [lib-id]
  (-> (l/in [:library-items :palettes lib-id])
      (l/derived st/state)))

;; --- Components

(mf/defc palette-item
  [{:keys [color] :as props}]
  (let [rgb-vec (hex->rgb color)

        select-color
        (fn [event]
          (if (kbd/shift? event)
            (st/emit! (udw/update-color-on-selected-shapes {:stroke-color color}))
            (st/emit! (udw/update-color-on-selected-shapes {:fill-color color}))))]

    [:div.color-cell {:key (str color)
                      :on-click select-color}
     [:span.color {:style {:background color}}]
     [:span.color-text color]]))

(mf/defc palette
  [{:keys [palettes selected left-sidebar?] :as props}]
  (let [items-ref  (mf/use-memo
                    (mf/deps selected)
                    (partial make-selected-palette-item-ref selected))

        items      (mf/deref items-ref)
        state      (mf/use-state {:show-menu false })

        width      (:width @state 0)
        visible    (mth/round (/ width 66))

        offset     (:offset @state 0)
        max-offset (- (count items)
                      visible)

        close-fn   #(st/emit! (udw/toggle-layout-flags :colorpalette))
        container  (mf/use-ref nil)

        on-left-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [event]
           (swap! state update :offset
                  (fn [offset]
                    (if (pos? offset)
                      (max (- offset (mth/round (/ visible 2))) 0)
                      offset)))))

        on-right-arrow-click
        (mf/use-callback
         (mf/deps max-offset visible)
         (fn [event]
           (swap! state update :offset
                  (fn [offset]
                    (if (< offset max-offset)
                      (min max-offset (+ offset (mth/round (/ visible 2))))
                      offset)))))

        on-scroll
        (mf/use-callback
         (mf/deps max-offset)
         (fn [event]
           (if (pos? (.. event -nativeEvent -deltaY))
             (on-right-arrow-click event)
             (on-left-arrow-click event))))

        on-resize
        (mf/use-callback
         (fn [event]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (swap! state assoc :width width))))

        handle-click
        (mf/use-callback
         (fn [library]))]
           ;; (st/emit! (dlib/select-library :palettes (:id library)))))]

    (mf/use-layout-effect
     #(let [dom   (mf/ref-val container)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    (mf/use-effect
     #(let [key1 (events/listen js/window "resize" on-resize)]
        (fn []
          (events/unlistenByKey key1))))

    (mf/use-effect
     (mf/deps selected)
     (fn []
       (when selected)))
         ;; (st/emit! (dlib/retrieve-library-data :palettes selected)))))

    [:div.color-palette {:class (when left-sidebar? "left-sidebar-open")}
     [:& context-menu
      {:selectable true
       :selected (->> palettes
                      (filter #(= (:id %) selected))
                      first
                      :name)
       :show (:show-menu @state)
       :on-close #(swap! state assoc :show-menu false)
       :options (mapv #(vector (:name %) (partial handle-click %)) palettes)}]

     [:div.color-palette-actions
      {:on-click #(swap! state assoc :show-menu true)}
      [:div.color-palette-actions-button i/actions]]

     [:span.left-arrow {:on-click on-left-arrow-click} i/arrow-slide]
     [:div.color-palette-content {:ref container :on-wheel on-scroll}
      [:div.color-palette-inside {:style {:position "relative"
                                          :right (str (* 66 offset) "px")}}
       (for [item items]
         [:& palette-item {:color (:content item) :key (:id item)}])]]
     [:span.right-arrow {:on-click on-right-arrow-click} i/arrow-slide]]))

(mf/defc colorpalette
  [{:keys [left-sidebar? project] :as props}]
  (let [team-id  (:team-id project)
        palettes (->> (mf/deref palettes-ref)
                      (vals)
                      (mapcat identity))
        selected (or (mf/deref selected-palette-ref)
                     (:id (first palettes)))]
    (mf/use-effect
     (mf/deps team-id)
     (fn []))
       ;; (st/emit! (dlib/retrieve-libraries :palettes)
       ;;           (dlib/retrieve-libraries :palettes team-id))))

    [:& palette {:left-sidebar? left-sidebar?
                 :selected selected
                 :palettes palettes}]))
