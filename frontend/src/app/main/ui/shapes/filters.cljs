;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.shapes.filters
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.color :as color]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]))

(defn get-filter-id []
  (str "filter_" (uuid/next)))

(defn filter-str
  [filter-id shape]

  (when (seq (:shadow shape))
    (str/fmt "url(#$0)" [filter-id])))

(mf/defc drop-shadow-filter
  [{:keys [filter-id filter shape]}]

  (let [{:keys [x y width height]} (:selrect shape)
        {:keys [fid color opacity offset-x offset-y blur spread]} filter

        filter-x (min x (+ x offset-x (- spread) (- blur) -5))
        filter-y (min y (+ y offset-y (- spread) (- blur) -5))
        filter-width (+ width (mth/abs offset-x) (* spread 2) (* blur 2) 10)
        filter-height (+ height (mth/abs offset-x) (* spread 2) (* blur 2) 10)

        [r g b a] (color/hex->rgba color opacity)
        [r g b] [(/ r 255) (/ g 255) (/ b 255)]
        color-matrix (str/fmt "0 0 0 0 $0 0 0 0 0 $1 0 0 0 0 $2 0 0 0 $3 0" [r g b a])]
    [:filter {:id filter-id
              :x filter-x :y filter-y
              :width filter-width :height filter-height
              :filterUnits "userSpaceOnUse"
              :color-interpolation-filters "sRGB"}
     [:feFlood {:flood-opacity 0 :result "BackgroundImageFix"}]
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "dilate"
                       :in "SourceAlpha"
                       :result "effect1_dropShadow"}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]

     [:feColorMatrix {:type "matrix" :values color-matrix}]

     [:feBlend {:mode "normal"
                :in2 "BackgroundImageFix"
                :result "effect1_dropShadow"}]

     [:feBlend {:mode "normal"
                :in "SourceGraphic"
                :in2 "effect1_dropShadow"
                :result "shape"}]]))

(mf/defc filters
  [{:keys [filter-id shape]}]
  [:defs
   (for [{:keys [id type hidden] :as filter} (:shadow shape)]
     (when (not hidden)
       [:& drop-shadow-filter {:key id
                               :filter-id filter-id
                               :filter filter
                               :shape shape}]))])
