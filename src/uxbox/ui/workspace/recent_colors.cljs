(ns uxbox.ui.workspace.recent-colors
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.ui.workspace.base :as wb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:static toggle-colorpalette
  #(rs/emit! (dw/toggle-flag :colorpalette)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- recent-colors-render
  [own {:keys [page id] :as shape} callback]
  (let [shapes-by-id (rum/react wb/shapes-by-id-l)
        shapes (->> (vals shapes-by-id)
                    (filter #(= (:page %) page)))
        colors (calculate-colors shapes)]
    (html
     [:div
      [:span (tr "ds.recent-colors")]
      [:div.row-flex
       (for [color colors]
         [:span.color-th {:style {:background color}
                          :on-click (partial callback color)}])
       (for [i (range (- 5 (count colors)))]
         [:span.color-th])

       [:span.color-th.palette-th {:on-click toggle-colorpalette}
        i/palette]]])))

(def ^:static recent-colors
  (mx/component
   {:render recent-colors-render
    :name "recent-colors"
    :mixins [mx/static rum/reactive]}))

