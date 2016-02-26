(ns uxbox.ui.shapes.line
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as ush]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.util.dom :as dom]))

(defmethod uusc/render-shape :builtin/line
  [{:keys [id x1 y1 x2 y2] :as shape}]
  (let [key (str id)
        props (select-keys shape [:x1 :x2 :y2 :y1])
        attrs (-> (uusc/extract-style-attrs shape)
                  (merge {:id key :key key})
                  (merge props))]
    (html
     [:line attrs])))
