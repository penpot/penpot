;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.recent-colors
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.workspace.base :as wb]))

;; --- Helpers

(defn- count-color
  [state shape prop]
  (let [color (prop shape)]
    (if (contains? state color)
      (update state color inc)
      (assoc state color 1))))

(defn- calculate-colors
  [shapes]
  (as-> {} $
    (reduce #(count-color %1 %2 :fill) $ shapes)
    (reduce #(count-color %1 %2 :stroke) $ shapes)
    (remove nil? $)
    (sort-by second (into [] $))
    (take 5 (map first $))))

;; --- Component

(defn- recent-colors-render
  [own {:keys [page id] :as shape} callback]
  (let [shapes-by-id (mx/react wb/shapes-by-id-ref)
        shapes (->> (vals shapes-by-id)
                    (filter #(= (:page %) page)))
        colors (calculate-colors shapes)]
    (html
     [:div
      [:span (tr "ds.recent-colors")]
      [:div.row-flex
       (for [color colors]
         [:span.color-th {:style {:background-color color}
                          :key color
                          :on-click (partial callback color)}])
       (for [i (range (- 5 (count colors)))]
         [:span.color-th {:key (str "empty" i)}])
       [:span.color-th.palette-th i/picker]]])))

(def recent-colors
  (mx/component
   {:render recent-colors-render
    :name "recent-colors"
    :mixins [mx/static mx/reactive]}))

