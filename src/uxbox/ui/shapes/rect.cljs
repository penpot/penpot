(ns uxbox.ui.shapes.rect
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

(defmethod uusc/render-shape :builtin/rect
  [{:keys [id x1 y1 x2 y2] :as shape}]
  (let [key (str id)
        rfm (ush/-transformation shape)
        size (ush/size shape)
        props {:x x1 :y y1 :id key :key key :transform (str rfm)}
        attrs (-> (uusc/extract-style-attrs shape)
                  (merge props size))]
    (html
     [:rect attrs])))

