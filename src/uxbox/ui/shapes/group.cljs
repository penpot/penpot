(ns uxbox.ui.shapes.group
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.util.geom :as geom]
            [uxbox.util.dom :as dom]))

(defmethod uusc/render-shape :group
  [{:keys [items id dx dy rotation] :as shape} factory]
  (let [key (str "group-" id)
        rfm (geom/transformation-matrix shape)
        attrs (merge {:id key :key key :transform (str rfm)}
                     (uusc/extract-style-attrs shape)
                     (uusc/make-debug-attrs shape))
        shapes-by-id (get @st/state :shapes-by-id)]
    (html
     [:g attrs
      (for [item (->> items
                      (map #(get shapes-by-id %))
                      (remove :hidden)
                      (reverse))]
        (-> (factory (:id item))
            (rum/with-key (str (:id item)))))])))

