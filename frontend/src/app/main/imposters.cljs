;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.imposters
  (:require ["react-dom/server" :as rds]
            [app.common.data.macros :as dm]
            [app.common.geom.rect :as grc]
            [app.common.geom.shapes :as gsh]
            [app.main.data.workspace.state-helpers :as wsh]
            [app.main.fonts :as fonts]
            [app.main.refs :as refs]
            [app.main.render :as render]
            [app.main.store :as st]
            [app.main.ui.shapes.text.fontfaces :as ff]
            [app.util.imposters :as imps]
            [app.util.thumbnails :as th]
            [beicon.core :as rx]
            [rumext.v2 :as mf]))

(defn render
  "Render the frame and store it in the imposter map"
  ([id shape objects]
   (render id shape objects nil))
  ([id shape objects fonts]
   (let [object-id          (dm/str id)
         shape              (if (nil? shape) (get objects id) shape)
         fonts              (if (nil? fonts) (ff/shape->fonts shape objects) fonts)

         all-children       (deref (refs/all-children-objects id))

         bounds
         (if (:show-content shape)
           (gsh/shapes->rect (cons shape all-children))
           (-> shape :points grc/points->rect))

         x                  (dm/get-prop bounds :x)
         y                  (dm/get-prop bounds :y)
         width              (dm/get-prop bounds :width)
         height             (dm/get-prop bounds :height)

         viewbox            (dm/fmt "% % % %" x y width height)

         [fixed-width fixed-height] (th/get-proportional-size width height)

         data (rds/renderToStaticMarkup
               (mf/element render/frame-imposter-svg
                           {:objects objects
                            :frame shape
                            :vbox viewbox
                            :width width
                            :height height
                            :show-thumbnails? false}))]
     (->> (fonts/render-font-styles-cached fonts)
          (rx/catch rx/empty)
          (rx/map (fn [styles] #js {:id object-id
                                    :data data
                                    :viewbox viewbox
                                    :width fixed-width
                                    :height fixed-height
                                    :styles styles}))))))

(defn render-by-id
  "Render the shape by its id (IMPORTANT! id as uuid, not string)"
  [id]
  (dm/assert! "expected uuid" (uuid? id))
  (let [objects  (wsh/lookup-page-objects @st/state)
        shape    (get objects id)
        fonts    (ff/shape->fonts shape objects)]
    (render id shape objects fonts)))

(defn init!
  "Initializes the render function"
  []
  (imps/init! render-by-id))
