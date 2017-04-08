;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.recent-colors
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.builtins.icons :as i]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.i18n :refer (tr)]))

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
    (reduce #(count-color %1 %2 :fill-color) $ shapes)
    (reduce #(count-color %1 %2 :stroke-color) $ shapes)
    (remove nil? $)
    (sort-by second (into [] $))
    (take 5 (map first $))))

;; --- Component

(mx/defc recent-colors
  {:mixins [mx/static mx/reactive]}
  [{:keys [page id] :as shape} callback]
  (let [shapes-by-id (mx/react refs/shapes-by-id)
        shapes (->> (vals shapes-by-id)
                    (filter #(= (:page %) page)))
        colors (calculate-colors shapes)]
    [:div
     [:span (tr "ds.recent-colors")]
     [:div.row-flex
      (for [color colors]
        [:span.color-th {:style {:background-color color}
                         :key color
                         :on-click (partial callback color)}])
      (for [i (range (- 5 (count colors)))]
        [:span.color-th {:key (str "empty" i)}])
      [:span.color-th.palette-th i/picker]]]))

