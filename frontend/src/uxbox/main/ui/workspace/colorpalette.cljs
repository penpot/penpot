;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.colorpalette
  (:require
   [beicon.core :as rx]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.colors :as udc]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.util.color :refer [hex->rgb]]
   [uxbox.util.data :refer [read-string seek]]
   [uxbox.util.dom :as dom]))

;; --- Refs

(def collections-iref
  (-> (l/key :colors-collections)
      (l/derive st/state)))

;; --- Components

(mf/defc palette-item
  [{:keys [color] :as props}]
  (letfn [(select-color [event]
            (if (kbd/shift? event)
              (st/emit! (udw/update-selected-shapes :stroke-color color))
              (st/emit! (udw/update-selected-shapes :fill-color color))))]
    (let [rgb-vec (hex->rgb color)
          rgb-color (apply str "" (interpose ", " rgb-vec))]
      [:div.color-cell {:key (str color)
                        :on-click select-color}
       [:span.color {:style {:background color}}]
       [:span.color-text color]
       [:span.color-text rgb-color]])))

(defn- document-width
  []
  (.. js/document -documentElement -clientWidth))

(mf/defc palette
  [{:keys [colls] :as props}]
  (let [local (mf/use-state {})
        colls (->> colls
                   (filter :id)
                   (sort-by :name))
        coll  (or (:selected @local)
                  (first colls))

        width (:width @local (* (document-width) 0.84))
        offset (:offset @local 0)
        visible (/ width 86)
        invisible (- (count (:colors coll)) visible)
        close #(st/emit! (udw/toggle-flag :colorpalette))

        container (mf/use-ref nil)
        container-child (mf/use-ref nil)]

    (letfn [(select-coll [event]
              (let [id (read-string (dom/event->value event))
                    selected (seek #(= id (:id %)) colls)]
                (swap! local assoc :selected selected :position 0)))
            (on-left-arrow-click [event]
              (when (> offset 0)
                (let [element (mf/ref-node container-child)]
                  (swap! local update :offset dec))))
            (on-right-arrow-click [event]
              (when (< offset invisible)
                (let [element (mf/ref-node container-child)]
                  (swap! local update :offset inc))))
            (on-scroll [event]
              (if (pos? (.. event -nativeEvent -deltaY))
                (on-right-arrow-click event)
                (on-left-arrow-click event)))
            (after-render []
              (let [dom (mf/ref-node container)
                    width (.-clientWidth dom)]
                (when (not= (:width @local) width)
                  (swap! local assoc :width width))))]

      (mf/use-effect {:deps true :fn after-render})

      [:div.color-palette
       [:div.color-palette-actions
        [:select.input-select {:on-change select-coll
                               :default-value (pr-str (:id coll))}
         (for [item colls]
           [:option {:key (:id item) :value (pr-str (:id item))}
            (:name item)])]

        #_[:div.color-palette-buttons
         [:div.btn-palette.edit.current i/pencil]
         [:div.btn-palette.create i/close]]]

       [:span.left-arrow {:on-click on-left-arrow-click} i/arrow-slide]

       [:div.color-palette-content {:ref container :on-wheel on-scroll}
        [:div.color-palette-inside {:ref container-child
                                    :style {:position "relative"
                                            :width (str (* 86 (count (:colors coll))) "px")
                                            :right (str (* 86 offset) "px")}}
         (for [color (:colors coll)]
           [:& palette-item {:color color :key color}])]]

       [:span.right-arrow {:on-click on-right-arrow-click}  i/arrow-slide]
       [:span.close-palette {:on-click close} i/close]])))

(mf/defc colorpalette
  [props]
  (let [colls (mf/deref collections-iref)]
    (mf/use-effect #(st/emit! (udc/fetch-collections)))
    [:& palette {:colls (vals colls)}]))
