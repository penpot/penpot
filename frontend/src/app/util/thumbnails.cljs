;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.thumbnails
  (:require
   [app.common.math :as mth]))

(def ^:const max-recommended-size (mth/pow 2 11)) ;; 2^11 = 2048
(def ^:const max-absolute-size (mth/pow 2 14)) ;; 2^14 = 16384

(def ^:const min-size 1)
(def ^:const max-size max-recommended-size)

(def ^:const min-aspect-ratio 0.5)
(def ^:const max-aspect-ratio 2.0)

(defn get-aspect-ratio
  "Returns the aspect ratio of a given width and height."
  [width height]
  (/ width height))

(defn get-size-from
  [ref-size opp-size clamped-size]
  (/ (* opp-size clamped-size) ref-size))

(defn get-height-from-width
  ([width height clamped-width]
   (get-size-from width height clamped-width)))

(defn get-width-from-height
  ([width height clamped-height]
   (get-size-from height width clamped-height)))

(defn get-proportional-size
  "Returns a proportional size given a width and height and some size constraints."
  ([width height]
   (get-proportional-size width height min-size max-size min-size max-size))
  ([width height min-size max-size]
   (get-proportional-size width height min-size max-size min-size max-size))
  ([width height min-width max-width min-height max-height]
   (let [clamped-width  (mth/clamp width min-width max-width)
         clamped-height (mth/clamp height min-height max-height)]
     (if (> width height)
       [clamped-width (get-height-from-width width height clamped-width)]
       [(get-width-from-height width height clamped-height) clamped-height]))))

(defn get-relative-size
  "Returns a recommended size given a width and height."
  [width height]
  (let [aspect-ratio (get-aspect-ratio width height)]
    (if (or (< aspect-ratio min-aspect-ratio) (> aspect-ratio max-aspect-ratio))
      (get-proportional-size width height min-size max-absolute-size)
      (get-proportional-size width height min-size max-recommended-size))))
