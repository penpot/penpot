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

(defn use-image-uri
  [media]
  (let [uri              (mf/use-memo (mf/deps (:id media))
                                      #(cfg/resolve-file-media media))
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

    {:uri (or @data-uri uri)
     :loading (not (some? @data-uri))}))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]

  (let [shape (unchecked-get props "shape")
        {:keys [id x y width height rotation metadata]} shape
        {:keys [uri loading]} (use-image-uri metadata)]

    (let [transform (geom/transform-matrix shape)
          props (-> (attrs/extract-style-attrs shape)
                    (obj/merge!
                     #js {:x x
                          :y y
                          :transform transform
                          :width width
                          :height height
                          :preserveAspectRatio "none"})
                    (cond-> loading
                      (obj/set! "data-loading" "true")))

          on-drag-start (fn [event]
                          ;; Prevent browser dragging of the image
                          (dom/prevent-default event))]

      [:> "image" (obj/merge!
                   props
                   #js {:xlinkHref uri
                        :onDragStart on-drag-start})])))
