;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.fills
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.config :as cfg]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc fills
  {::mf/wrap-props false}
  [props]

  (let [shape     (obj/get props "shape")
        render-id (obj/get props "render-id")]

    (when (or (some? (:fill-image shape))
              (#{:image :text} (:type shape))
              (> (count (:fills shape)) 1)
              (some :fill-color-gradient (:fills shape)))

      (let [{:keys [x y width height]} (:selrect shape)
            {:keys [metadata]} shape

            has-image? (or metadata (:fill-image shape))

            uri (cond
                  metadata
                  (cfg/resolve-file-media metadata)

                  (:fill-image shape)
                  (cfg/resolve-file-media (:fill-image shape)))

            embed (embed/use-data-uris [uri])
            transform (gsh/transform-str shape)

            ;; When true the image has not loaded yet
            loading? (and (some? uri) (not (contains? embed uri)))

            pattern-attrs (cond-> #js {:patternUnits "userSpaceOnUse"
                                       :x x
                                       :y y
                                       :height height
                                       :width width
                                       :data-loading loading?}
                            (= :path (:type shape))
                            (obj/set! "patternTransform" transform))
            type (:type shape)]

        (for [[shape-index shape] (d/enumerate (or (:position-data shape) [shape]))]
          [:* {:key (dm/str shape-index)}
           (for [[fill-index value] (-> (d/enumerate (:fills shape [])) reverse)]
             (when (some? (:fill-color-gradient value))
               (let [props #js {:id (dm/str "fill-color-gradient_" render-id "_" fill-index)
                                :key (dm/str fill-index)
                                :gradient (:fill-color-gradient value)
                                :shape shape}]
                 (case (d/name (:type (:fill-color-gradient value)))
                   "linear" [:> grad/linear-gradient props]
                   "radial" [:> grad/radial-gradient props]))))


           (let [fill-id (dm/str "fill-" shape-index "-" render-id)]
             [:> :pattern (-> (obj/clone pattern-attrs)
                              (obj/set! "id" fill-id))
              [:g
               (for [[fill-index value] (-> (d/enumerate (:fills shape [])) reverse)]
                 [:> :rect (-> (attrs/extract-fill-attrs value render-id fill-index type)
                               (obj/set! "key" (dm/str fill-index))
                               (obj/set! "width" width)
                               (obj/set! "height" height))])

               (when has-image?
                 [:image {:href (get embed uri uri)
                          :preserveAspectRatio "none"
                          :width width
                          :height height}])]])])))))
