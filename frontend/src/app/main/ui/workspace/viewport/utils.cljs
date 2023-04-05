; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.utils
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.main.ui.cursors :as cur]
   [app.main.ui.formats :refer [format-number]]))

(defn format-viewbox [vbox]
  (dm/str (format-number(:x vbox 0)) " "
          (format-number (:y vbox 0)) " "
          (format-number (:width vbox 0)) " "
          (format-number (:height vbox 0))))

(defn get-cursor [cursor]
  (case cursor
    :hand cur/hand
    :comments cur/comments
    :create-artboard cur/create-artboard
    :create-rectangle cur/create-rectangle
    :create-ellipse cur/create-ellipse
    :pen cur/pen
    :pencil cur/pencil
    :create-shape cur/create-shape
    :duplicate cur/duplicate
    :zoom cur/zoom
    :zoom-in cur/zoom-in
    :zoom-out cur/zoom-out
    cur/pointer-inner))

;; Ensure that the label has always the same font
;; size, regardless of zoom
;; https://css-tricks.com/transforms-on-svg-elements/
(defn text-transform
  [{:keys [x y]} zoom]
  (let [inv-zoom (/ 1 zoom)]
    (dm/fmt "scale(%, %) translate(%, %)" inv-zoom inv-zoom (* zoom x) (* zoom y))))

(defn left?
  [cur cand]
  (let [closex? (mth/close? (:x cand) (:x cur) 0.01)]
    (cond
      (and closex? (< (:y cand) (:y cur))) cand
      closex?                              cur
      (< (:x cand) (:x cur))               cand
      :else                                cur)))

(defn top?
  [cur cand]
  (let [closey? (mth/close? (:y cand) (:y cur))]
    (cond
      (and closey? (< (:x cand) (:x cur))) cand
      closey?                              cur
      (< (:y cand) (:y cur))               cand
      :else                                cur)))

(defn right?
  [cur cand]
  (let [closex? (mth/close? (:x cand) (:x cur))]
    (cond
      (and closex? (< (:y cand) (:y cur))) cand
      closex?                              cur
      (> (:x cand) (:x cur))               cand
      :else                                cur)))

(defn title-transform [{:keys [points] :as shape} zoom]
  (let [leftmost  (->> points (reduce left?))
        topmost   (->> points (remove #{leftmost}) (reduce top?))
        rightmost (->> points (remove #{leftmost topmost}) (reduce right?))

        left-top (gpt/to-vec leftmost topmost)
        left-top-angle (gpt/angle left-top)

        top-right (gpt/to-vec topmost rightmost)
        top-right-angle (gpt/angle top-right)

        ;; Choose the position that creates the less angle between left-side and top-side
        [label-pos angle v-pos]
        (if (< (mth/abs left-top-angle) (mth/abs top-right-angle))
          [leftmost left-top-angle (gpt/perpendicular left-top)]
          [topmost top-right-angle (gpt/perpendicular top-right)])


        label-pos
        (gpt/subtract label-pos (gpt/scale (gpt/unit v-pos) (/ 10 zoom)))]

    (dm/fmt "rotate(% %,%) scale(%, %) translate(%, %)"
            ;; rotate
            angle (:x label-pos) (:y label-pos)
            ;; scale
            (/ 1 zoom) (/ 1 zoom)
            ;; translate
            (* zoom (:x label-pos)) (* zoom (:y label-pos)))))
