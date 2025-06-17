;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.fills
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gst]
   [app.config :as cf]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def no-repeat-padding 1.05)

(mf/defc internal-fills
  {::mf/wrap-props false}
  [props]
  (let [shape      (unchecked-get props "shape")
        render-id  (unchecked-get props "render-id")

        type       (dm/get-prop shape :type)
        image      (get shape :fill-image)
        fills      (get shape :fills [])

        selrect    (dm/get-prop shape :selrect)

        bounds     (when (cfh/text-shape? shape)
                     (gst/shape->rect shape))

        metadata   (get shape :metadata)

        x          (dm/get-prop selrect :x)
        y          (dm/get-prop selrect :y)
        width      (dm/get-prop selrect :width)
        height     (dm/get-prop selrect :height)

        has-image? (or (some? metadata)
                       (some? image))

        uri        (cond
                     (some? metadata)
                     (cf/resolve-file-media metadata)

                     (some? image)
                     (cf/resolve-file-media image))

        uris       (into [uri]
                         (comp
                          (keep :fill-image)
                          (map cf/resolve-file-media))
                         fills)
        embed       (embed/use-data-uris uris)
        transform   (gsh/transform-str shape)

        pat-props   #js {:patternUnits (if (= :text type) "objectBoundingBox" "userSpaceOnUse")
                         :x (when-not (= :text type) x)
                         :y (when-not (= :text type) y)
                         :width width
                         :height height}

        pat-props   (if (or (= :path type) (= :bool type))
                      (obj/set! pat-props "patternTransform" transform)
                      pat-props)]

    (for [[obj-index obj] (d/enumerate (or (:position-data shape) [shape]))]
      [:* {:key (dm/str obj-index)}
       (for [[fill-index value] (reverse (d/enumerate (get obj :fills [])))]
         (when (some? (:fill-color-gradient value))
           (let [gradient  (:fill-color-gradient value)

                 from-p (-> (gpt/point (+ x (* width (:start-x gradient)))
                                       (+ y (* height (:start-y gradient)))))
                 to-p   (-> (gpt/point (+ x (* width (:end-x gradient)))
                                       (+ y (* height (:end-y gradient)))))

                 gradient
                 (cond-> gradient
                   (some? bounds)
                   (assoc
                    :start-x (/ (- (:x from-p) (:x bounds)) (:width bounds))
                    :start-y (/ (- (:y from-p) (:y bounds)) (:height bounds))
                    :end-x   (/ (- (:x to-p) (:x bounds)) (:width bounds))
                    :end-y   (/ (- (:y to-p) (:y bounds)) (:height bounds))))

                 props #js {:id (dm/str "fill-color-gradient-" render-id "-" fill-index)
                            :key (dm/str fill-index)
                            :gradient gradient
                            :shape obj}]
             (case (d/name (:type gradient))
               "linear" [:> grad/linear-gradient props]
               "radial" [:> grad/radial-gradient props]))))


       (let [fill-id (dm/str "fill-" obj-index "-" render-id)]
         [:> :pattern (-> (obj/clone pat-props)
                          (obj/set! "id" fill-id)
                          (cond-> (and has-image? (nil? bounds))
                            (-> (obj/set! "width" (* width no-repeat-padding))
                                (obj/set! "height" (* height no-repeat-padding))))
                          (cond-> (some? bounds)
                            (-> (obj/set! "width" (:width bounds))
                                (obj/set! "height" (:height bounds)))))
          [:g
           (for [[fill-index value] (reverse (d/enumerate (get obj :fills [])))]
             (let [style (attrs/get-fill-style value fill-index render-id type)
                   props #js {:key (dm/str fill-index)
                              :width (d/nilv (:width bounds) width)
                              :height (d/nilv (:height bounds) height)
                              :style style}]
               (if (:fill-image value)
                 (let [uri (cf/resolve-file-media (:fill-image value))
                       keep-ar? (-> value :fill-image :keep-aspect-ratio)
                       image-props #js {:id (dm/str "fill-image-" render-id "-" fill-index)
                                        :href (get embed uri uri)
                                        :preserveAspectRatio (if keep-ar? "xMidYMid slice" "none")
                                        :width width
                                        :height height
                                        :key (dm/str fill-index)
                                        :opacity (:fill-opacity value)}]
                   [:> :image image-props])
                 [:> :rect props])))

           (when ^boolean has-image?
             [:g
              ;; We add this shape to add a padding so the patter won't repeat
              ;; Issue: https://tree.taiga.io/project/penpot/issue/5583
              [:rect {:x 0
                      :y 0
                      :width (* width no-repeat-padding)
                      :height (* height no-repeat-padding)
                      :fill "none"}]
              [:image {:href uri
                       :preserveAspectRatio "none"
                       :x 0
                       :y 0
                       :width width
                       :height height}]])]])])))

(mf/defc fills
  {::mf/wrap-props false}
  [props]
  (let [shape     (unchecked-get props "shape")
        type      (dm/get-prop shape :type)
        image     (:fill-image shape)
        fills     (:fills shape [])]

    (when (or (some? image)
              (or (= type :image)
                  (= type :text))
              (> (count fills) 1)
              (some :fill-color-gradient fills)
              (some :fill-image fills))
      [:> internal-fills props])))
