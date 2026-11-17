;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.gradient
  "Gradient token values, once the tokenscript gradient token type has
  resolved and checked them: turning them into the gradient of a fill or
  a stroke."
  (:require
   [app.common.math :as mth]
   [app.config :as cf]
   [app.main.data.tinycolor :as tinycolor]))

(defn enabled?
  "Whether gradient tokens are shown and applied: they are behind the
  `token-gradient` flag, and only tokenscript resolves them."
  []
  (and (contains? cf/flags :token-gradient)
       (contains? cf/flags :tokenscript)))

(defn paint-tokens
  "The tokens of `tokens-by-type` that fill a shape: its color tokens,
  and its gradient tokens when they are enabled."
  [tokens-by-type]
  (cond-> (vec (:color tokens-by-type))
    (enabled?) (into (:gradient tokens-by-type))))

(defn paint-token?
  "Whether `token` fills a shape: a color token, or a gradient token when
  they are enabled."
  [{:keys [type]}]
  (or (= :color type)
      (and (= :gradient type) (enabled?))))

(def default-angle
  "The angle of a linear gradient without one: top to bottom, as in CSS."
  180)

(defn- linear-points
  "Where a linear gradient of `angle` degrees starts and ends on a shape,
  as fractions of its size. 0 goes up and 90 to the right, as in CSS,
  but the angle is relative to the shape's box, like every gradient of a
  penpot fill: on a shape that is not square, 45 runs from corner to
  corner (CSS `to top right`), not at a true 45 degrees."
  [angle]
  (let [radians (mth/radians angle)
        dx      (mth/sin radians)
        dy      (- (mth/cos radians))
        half    (/ (+ (mth/abs dx) (mth/abs dy)) 2)
        point   (fn [direction]
                  (mth/precision (+ 0.5 (* direction half)) 4))]
    {:start-x (point (- dx))
     :start-y (point (- dy))
     :end-x   (point dx)
     :end-y   (point dy)}))

(def ^:private radial-points
  {:start-x 0.5
   :start-y 0.5
   :end-x   0.5
   :end-y   1})

(defn value->stop
  "Transform a resolved color stop into the stop of a penpot gradient"
  [{:keys [color position]}]
  (let [tc (tinycolor/valid-color color)]
    {:color   (tinycolor/->hex-string tc)
     :opacity (tinycolor/alpha tc)
     :offset  position}))

(defn value->gradient
  "Transform a resolved gradient token value into penpot gradient data
  structure, with its stops sorted by position. A radial gradient goes
  from the center of the shape to its edge."
  [value]
  (when (and (map? value) (sequential? (:stops value)))
    (let [{:keys [kind angle stops]} value]
      (merge
       {:type  (keyword kind)
        :width 1
        :stops (mapv value->stop (sort-by :position stops))}
       (if (= "linear" kind)
         (linear-points (or angle default-angle))
         radial-points)))))

(defn paints?
  "Whether `value`, the resolved value of a color or a gradient token, is
  what `color` (a fill or stroke color with its `:color` or `:gradient`)
  paints: the token is still the one a shape shows."
  [value color]
  (if (map? value)
    (let [gradient (value->gradient value)]
      (and (some? gradient)
           (= gradient (:gradient color))))
    (and (some? value)
         (= value (:color color)))))
