;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.path.format
  (:require
   [app.common.math :as mth]
   [app.common.path.commands :as upc]
   [app.common.path.subpaths :refer [pt=]]
   [app.util.array :as arr]))

(def path-precision 3)

(defn- join-params
  ([a]
   (js* "\"\"+~{}"
        (mth/precision a path-precision)))
  ([a b]
   (js* "\"\"+~{}+\",\"+~{}"
        (mth/precision a path-precision)
        (mth/precision b path-precision)))
  ([a b c]
   (js* "\"\"+~{}+\",\"+~{}+\",\"+~{}"
        (mth/precision a path-precision)
        (mth/precision b path-precision)
        (mth/precision c path-precision)))
  ([a b c d]
   (js* "\"\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}"
        (mth/precision a path-precision)
        (mth/precision b path-precision)
        (mth/precision c path-precision)
        (mth/precision d path-precision)
        ))
  ([a b c d e]
   (js* "\"\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}"
        (mth/precision a path-precision)
        (mth/precision b path-precision)
        (mth/precision c path-precision)
        (mth/precision d path-precision)
        (mth/precision e path-precision)))
  ([a b c d e f]
   (js* "\"\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}"
        (mth/precision a path-precision)
        (mth/precision b path-precision)
        (mth/precision c path-precision)
        (mth/precision d path-precision)
        (mth/precision e path-precision)
        (mth/precision f path-precision)
        ))
  ([a b c d e f g]
   (js* "\"\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}+\",\"+~{}"
        (mth/precision a path-precision)
        (mth/precision b path-precision)
        (mth/precision c path-precision)
        (mth/precision d path-precision)
        (mth/precision e path-precision)
        (mth/precision f path-precision)
        (mth/precision g path-precision))))

(defn- translate-params
  [command {:keys [x y] :as params}]
  (case command
    (:move-to :line-to :smooth-quadratic-bezier-curve-to)
    (join-params x y)

    :close-path
    ""

    (:line-to-horizontal :line-to-vertical)
    (:value params)

    :curve-to
    (let [{:keys [c1x c1y c2x c2y]} params]
      (join-params c1x c1y c2x c2y x y))

    (:smooth-curve-to :quadratic-bezier-curve-to)
    (let [{:keys [cx cy]} params]
      (join-params cx cy x y))

    :elliptical-arc
    (let [{:keys [rx ry x-axis-rotation large-arc-flag sweep-flag]} params]
      (join-params rx ry x-axis-rotation large-arc-flag sweep-flag x y))

    ""))

(defn- translate-command
  [cname]
  (case cname
    :move-to "M"
    :close-path "Z"
    :line-to "L"
    :line-to-horizontal "H"
    :line-to-vertical "V"
    :curve-to "C"
    :smooth-curve-to "S"
    :quadratic-bezier-curve-to "Q"
    :smooth-quadratic-bezier-curve-to "T"
    :elliptical-arc "A"
    ""))


(defn- command->string
  [{:keys [command relative params]}]
  (let [cmd (cond-> (translate-command command)
              relative (.toLowerCase))
        prm (translate-params command params)]
    (js* "~{} + ~{}" cmd prm)))

(defn- set-point
  [command {:keys [x y]}]
  (update command :params assoc :x x :y y))

(defn format-path [content]
  (let [result (make-array (count content))]
    (reduce (fn [last-move current]
              (let [point         (upc/command->point current)
                    current-move? (= :move-to (:command current))
                    last-move     (if current-move? point last-move)]

                (if (and (not current-move?) (pt= last-move point))
                  (arr/conj! result (command->string (set-point current last-move)))
                  (arr/conj! result (command->string current)))

                (when (and (not current-move?) (pt= last-move point))
                  (arr/conj! result "Z"))

                last-move))
            nil
            content)
    (.join ^js result "")))
