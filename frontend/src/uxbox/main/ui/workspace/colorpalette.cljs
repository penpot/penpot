;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.colorpalette
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.colors :as dc]
            [uxbox.main.ui.dashboard.colors :refer (collections-ref)]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.lens :as ul]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.color :refer (hex->rgb)]
            [uxbox.util.dom :as dom]
            [rumext.core :as mx :include-macros true]))

(defn- get-selected-collection
  [local collections]
  (if-let [selected (:selected @local)]
    (first (filter #(= selected (:id %)) collections))
    (first (filter #(and (:id %) (> (count (:colors %)) 0)) collections))))

(mx/defc palette-item
  {:mixins [mx/static]}
  [color]
  (letfn [(select-color [event]
            (let [attrs (if (kbd/shift? event)
                          {:stroke-color color}
                          {:fill-color color})]
              (st/emit! (uds/update-selected-shapes-attrs attrs))))]
    (let [rgb-vec (hex->rgb color)
          rgb-color (apply str "" (interpose ", " rgb-vec))]
      [:div.color-cell {:key (str color)
                        :on-click select-color}
       [:span.color {:style {:background color}}]
       [:span.color-text color]
       [:span.color-text rgb-color]])))

(defn- palette-after-render
  [{:keys [rum/local] :as own}]
  (let [dom (mx/ref-node own "container")
        width (.-clientWidth dom)]
    (when (not= (:width @local) width)
      (swap! local assoc :width width))
    own))

(defn- document-width
  []
  (.. js/document -documentElement -clientWidth))

(mx/defcs palette
  {:mixins [mx/static mx/reactive (mx/local)]
   :after-render palette-after-render}
  [{:keys [rum/local] :as own}]
  (let [collections (->> (mx/react collections-ref)
                         (vals)
                         (filter :id)
                         (sort-by :name))
        {:keys [colors] :as selected-coll} (get-selected-collection local collections)
        width (:width @local (* (document-width) 0.84))
        offset (:offset @local 0)
        visible (/ width 86)
        invisible (- (count colors) visible)]
    (letfn [(select-collection [event]
              (let [value (read-string (dom/event->value event))]
                (swap! local assoc :selected value :position 0)))
            (close [event]
              (st/emit! (dw/toggle-flag :colorpalette)))]
      [:div.color-palette
       [:div.color-palette-actions
        [:select.input-select {:on-change select-collection
                               :value (pr-str (:id selected-coll))}
         (for [collection collections]
           [:option {:key (str (:id collection))
                     :value (pr-str (:id collection))}
            (:name collection)])]
        #_[:div.color-palette-buttons
           [:div.btn-palette.edit.current i/pencil]
           [:div.btn-palette.create i/close]]]

       [:span.left-arrow
        (when (> offset 0)
          {:on-click #(swap! local update :offset (fnil dec 1))})
        i/arrow-slide]

       [:div.color-palette-content {:ref "container"}
        [:div.color-palette-inside {:style {:position "relative"
                                            :right (str (* 86 offset) "px")}}
         (for [color colors]
           (-> (palette-item color)
               (mx/with-key color)))]]

       [:span.right-arrow
        (when (< offset invisible)
          {:on-click #(swap! local update :offset (fnil inc 0))})
        i/arrow-slide]
       [:span.close-palette {:on-click close}
        i/close]])))

(defn- colorpalette-will-mount
  [own]
  (st/emit! (dc/fetch-collections))
  own)

(mx/defc colorpalette
  {:mixins [mx/static mx/reactive]
   :will-mount colorpalette-will-mount}
  []
  (let [flags (mx/react refs/flags)]
    (when (contains? flags :colorpalette)
      (palette))))
