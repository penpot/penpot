;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.gradient
  "Converts gradient token values into the gradient of a fill or a
  stroke. The tokenscript gradient token type resolves and checks the
  values first."
  (:require
   [app.common.math :as mth]
   [app.config :as cf]
   [app.main.data.tinycolor :as tinycolor]))

(defn enabled?
  "Checks whether penpot shows and applies gradient tokens. They need the
  `token-gradient` flag. They also need the `tokenscript` flag, because
  only tokenscript resolves them."
  []
  (and (contains? cf/flags :token-gradient)
       (contains? cf/flags :tokenscript)))

(defn paint-tokens
  "Gives the tokens of `tokens-by-type` that fill a shape. These are the
  color tokens, and the gradient tokens when they are enabled."
  [tokens-by-type]
  (cond-> (vec (:color tokens-by-type))
    (enabled?) (into (:gradient tokens-by-type))))

(defn paint-token?
  "Checks whether `token` fills a shape. A color token does. A gradient
  token does when gradient tokens are enabled."
  [{:keys [type]}]
  (or (= :color type)
      (and (= :gradient type) (enabled?))))

(def default-angle
  "The angle of a linear gradient that has no angle: top to bottom, as in
  CSS."
  180)

(defn- linear-points
  "Works out where a linear gradient of `angle` degrees starts and ends
  on a shape, as fractions of its size. As in CSS, 0 goes up and 90 goes
  to the right. But the angle is relative to the box of the shape, as
  for every gradient of a penpot fill. On a shape that is not square, 45
  runs from corner to corner (CSS `to top right`), not at a true 45
  degrees."
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
  "Converts a resolved color stop into a stop of a penpot gradient."
  [{:keys [color position]}]
  (let [tc (tinycolor/valid-color color)]
    {:color   (tinycolor/->hex-string tc)
     :opacity (tinycolor/alpha tc)
     :offset  position}))

(defn value->gradient
  "Converts a resolved gradient token value into a penpot gradient, with
  its stops sorted by position. A radial gradient goes from the center
  of the shape to its edge."
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
  "Checks whether `color` still paints `value`, so that the shape still
  shows the token. `value` is the resolved value of a color or a
  gradient token. `color` is a fill or stroke color with its `:color` or
  `:gradient`."
  [value color]
  (if (map? value)
    (let [gradient (value->gradient value)]
      (and (some? gradient)
           (= gradient (:gradient color))))
    (and (some? value)
         (= value (:color color)))))
