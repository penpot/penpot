(ns uxbox.ui.shapes.circle
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as ush]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.util.dom :as dom]))

(defmethod uusc/render-shape :builtin/circle
  [{:keys [id] :as shape}]
  (let [key (str id)
        rfm (ush/transformation shape)
        props (select-keys shape [:cx :cy :rx :ry])
        attrs (-> (uusc/extract-style-attrs shape)
                  (merge {:id key :key key :transform (str rfm)})
                  (merge props))]
    (html
     [:ellipse attrs])))
