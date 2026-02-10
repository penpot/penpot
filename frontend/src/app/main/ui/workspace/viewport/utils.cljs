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
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.formats :refer [format-number]]))

(defn format-viewbox
  "Format a viewbox to a string"
  [vbox]
  (dm/str (format-number (:x vbox 0)) " "
          (format-number (:y vbox 0)) " "
          (format-number (:width vbox 0)) " "
          (format-number (:height vbox 0))))

(defn get-cursor [cursor]
  (case cursor
    :hand (cur/get-static "hand")
    :comments (cur/get-static "comments")
    :create-artboard (cur/get-static "create-artboard")
    :create-rectangle (cur/get-static "create-rectangle")
    :create-ellipse (cur/get-static "create-ellipse")
    :pen (cur/get-static "pen")
    :pencil (cur/get-static "pencil")
    :create-shape (cur/get-static "create-shape")
    :duplicate (cur/get-static "duplicate")
    :zoom (cur/get-static "zoom")
    :zoom-in (cur/get-static "zoom-in")
    :zoom-out (cur/get-static "zoom-out")
    (cur/get-static "pointer-inner")))

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

(defn title-transform
  [{:keys [points] :as shape} zoom grid-edition?]
  (let [leftmost  (->> points (reduce left?))
        topmost   (->> points (remove #{leftmost}) (reduce top?))
        rightmost (->> points (remove #{leftmost topmost}) (reduce right?))]
    (when (and (some? leftmost) (some? topmost) (some? rightmost))
      (let [left-top (gpt/to-vec leftmost topmost)
            left-top-angle (gpt/angle left-top)

            top-right (gpt/to-vec topmost rightmost)
            top-right-angle (gpt/angle top-right)

            ;; Choose the position that creates the less angle between left-side and top-side
            [label-pos angle h-pos v-pos]
            (if (< (mth/abs left-top-angle) (mth/abs top-right-angle))
              [leftmost left-top-angle left-top (gpt/perpendicular left-top)]
              [topmost top-right-angle top-right (gpt/perpendicular top-right)])

            delta-x (if grid-edition? 40 0)
            delta-y (if grid-edition? 50 10)

            label-pos
            (-> label-pos
                (gpt/subtract (gpt/scale (gpt/unit v-pos) (/ delta-y zoom)))
                (gpt/subtract (gpt/scale (gpt/unit h-pos) (/ delta-x zoom))))]
        (dm/fmt "rotate(% %,%) scale(%, %) translate(%, %)"
                ;; rotate
                angle (:x label-pos) (:y label-pos)
                ;; scale
                (/ 1 zoom) (/ 1 zoom)
                ;; translate
                (* zoom (:x label-pos)) (* zoom (:y label-pos)))))))
