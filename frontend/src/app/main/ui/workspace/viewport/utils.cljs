; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.viewport.utils
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.formats :refer [format-number]]
   [rumext.v2 :as mf]))

(defn format-viewbox
  "Format a viewbox to a string"
  [vbox]
  (dm/str (format-number (:x vbox 0)) " "
          (format-number (:y vbox 0)) " "
          (format-number (:width vbox 0)) " "
          (format-number (:height vbox 0))))

(defn pan-translation
  "Translate a settled SVG viewBox in screen pixels while its scale is constant."
  [base vbox vport]
  (when (and base vbox (not (identical? base vbox))
             (= (:width base) (:width vbox)) (= (:height base) (:height vbox))
             (pos? (:width vport)) (pos? (:height vport)))
    (let [number-at (fn [box k] (js/parseFloat (format-number (get box k 0))))
          scale     (min (/ (:width vport) (number-at base :width))
                         (/ (:height vport) (number-at base :height)))
          dx        (* scale (- (number-at base :x) (number-at vbox :x)))
          dy        (* scale (- (number-at base :y) (number-at vbox :y)))]
      (when (and (js/Number.isFinite dx) (js/Number.isFinite dy))
        (dm/str "translate(" dx "px," dy "px)")))))

(defn zoom-transform
  "Map the settled SVG to the current xMidYMid meet camera in CSS pixels."
  [base vbox {vw :width vh :height}]
  (when (and base vbox (pos? vw) (pos? vh) (not= (:width base) (:width vbox)))
    (let [n     (fn [box k] (js/parseFloat (format-number (get box k))))
          bw    (n base :width)
          bh    (n base :height)
          w     (n vbox :width)
          h     (n vbox :height)
          scale (min (/ vw w) (/ vh h))
          ratio (/ scale (min (/ vw bw) (/ vh bh)))
          dx    (+ (* 0.5 (- (* vw (- 1 ratio)) (* scale (- w bw))))
                   (* scale (- (n base :x) (n vbox :x))))
          dy    (+ (* 0.5 (- (* vh (- 1 ratio)) (* scale (- h bh))))
                   (* scale (- (n base :y) (n vbox :y))))]
      (when (and (<= 0.5 ratio 2) (js/Number.isFinite dx) (js/Number.isFinite dy))
        (dm/str "matrix(" ratio ",0,0," ratio "," dx "," dy ")")))))

(defn use-render-window
  "Keep nearby roots mounted across small pans and shrink the window on zoom-in."
  [{:keys [x y width height] :as vbox}]
  (let [ref (mf/use-ref nil)
        old (mf/ref-val ref)]
    (when (and vbox (pos? width) (pos? height)
               (or (nil? old) (> (:width old) (* 2 width)) (> (:height old) (* 2 height))
                   (< x (:x old)) (< y (:y old))
                   (> (+ x width) (:x2 old)) (> (+ y height) (:y2 old))))
      (mf/set-ref-val! ref (grc/make-rect (- x (* width 0.25)) (- y (* height 0.25))
                                          (* width 1.5) (* height 1.5))))
    (when vbox (mf/ref-val ref))))

(defn get-cursor [cursor]
  (case cursor
    :hand (cur/get-static "hand")
    :comments (cur/get-static "comments")
    :create-artboard (cur/get-static "create-artboard")
    :create-rectangle (cur/get-static "create-rectangle")
    :create-ellipse (cur/get-static "create-ellipse")
    :pen (cur/get-static "pen")
    :pencil (cur/get-static "pencil")
    :draw-path (cur/get-static "draw")
    :edit-path (cur/get-static "move")
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
  (let [closey? (mth/close? (:y cand) (:y cur) 0.01)]
    (cond
      (and closey? (< (:x cand) (:x cur))) cand
      closey?                              cur
      (< (:y cand) (:y cur))               cand
      :else                                cur)))

(defn right?
  [cur cand]
  (let [closex? (mth/close? (:x cand) (:x cur) 0.01)]
    (cond
      (and closex? (< (:y cand) (:y cur))) cand
      closex?                              cur
      (> (:x cand) (:x cur))               cand
      :else                                cur)))

(defn title-transform-use-width?
  [{:keys [rotation] :as shape}]
  (let [side       (mth/ceil (/ (- rotation 45) 90))
        use-width? (even? side)]
    use-width?))

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
