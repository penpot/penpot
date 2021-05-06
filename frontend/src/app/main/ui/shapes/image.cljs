;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.image
  (:require
   [app.common.geom.shapes :as geom]
   [app.config :as cfg]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height rotation metadata]} shape
        uri              (cfg/resolve-file-media metadata)
        embed-resources? (mf/use-ctx muc/embed-ctx)
        data-uri         (mf/use-state (when (not embed-resources?) uri))]

    (mf/use-effect
     (mf/deps uri)
     (fn []
       (if embed-resources?
         (->> (http/send! {:method :get
                           :uri uri
                           :response-type :blob})
              (rx/map :body)
              (rx/mapcat wapi/read-file-as-data-url)
              (rx/subs #(reset! data-uri  %))))))

    (let [transform (geom/transform-matrix shape)
          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x x
                          :y y
                          :transform transform
                          :width width
                          :height height
                          :preserveAspectRatio "none"}))
          on-drag-start (fn [event]
                          ;; Prevent browser dragging of the image
                          (dom/prevent-default event))]

      (if (nil? @data-uri)
        [:> "rect" (obj/merge!
                    props
                    #js {:fill "#E8E9EA"
                         :stroke "#000000"})]
        [:> "image" (obj/merge!
                     props
                     #js {:xlinkHref @data-uri
                          :onDragStart on-drag-start})]))))
