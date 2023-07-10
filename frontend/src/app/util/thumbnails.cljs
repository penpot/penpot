;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.thumbnails
  (:require
   [app.common.math :as mth]))

(def ^:const min-size 250)
(def ^:const max-size 2000)

(defn get-proportional-size
  "Returns a proportional size given a width and height and some size constraints."
  ([width height]
   (get-proportional-size width height min-size max-size min-size max-size))
  ([width height min-size max-size]
   (get-proportional-size width height min-size max-size min-size max-size))
  ([width height min-width max-width min-height max-height]
   (let [[fixed-width fixed-height]
          (if (> width height)
            [(mth/clamp width min-width max-width)
             (/ (* height (mth/clamp width min-width max-width)) width)]
            [(/ (* width (mth/clamp height min-height max-height)) height)
             (mth/clamp height min-height max-height)])]
      [fixed-width fixed-height])))


