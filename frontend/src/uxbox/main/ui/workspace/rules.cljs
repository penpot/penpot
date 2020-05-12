;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.rules
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.math :as mth]
   [uxbox.util.object :as obj]))

(defn- calculate-step-size
  [zoom]
  (cond
    (< 0 zoom 0.008) 10000
    (< 0.008 zoom 0.015) 5000
    (< 0.015 zoom 0.04) 2500
    (< 0.04 zoom 0.07) 1000
    (< 0.07 zoom 0.2) 500
    (< 0.2 zoom 0.5) 250
    (< 0.5 zoom 1) 100
    (< 1 zoom 2) 50
    (< 2 zoom 4) 25
    (< 4 zoom 6) 10
    (< 6 zoom 15) 5
    (< 15 zoom 25) 2
    (< 25 zoom) 1
    :else 1))

(defn draw-rule!
  [dctx {:keys [zoom size start count type] :or {count 200}}]
  (let [txfm (- (* (- 0 start) zoom) 20)
        minv (mth/round start)
        maxv (mth/round (+ start (/ size zoom)))
        step (calculate-step-size zoom)]
    (obj/set! dctx "fillStyle" "#E8E9EA")
    (if (= type :horizontal)
      (do
        (.fillRect dctx 0 0 size 20)
        (.translate dctx txfm 0))
      (do
        (.fillRect dctx 0 0 20 size)
        (.translate dctx 0 txfm)))

    (obj/set! dctx "font" "12px serif")
    (obj/set! dctx "fillStyle" "#7B7D85")
    (obj/set! dctx "strokeStyle" "#7B7D85")
    (obj/set! dctx "textAlign" "center")

    (loop [i minv]
      (when (< i maxv)
        (let [pos (+ (* i zoom) 0)]
          (when (= (mod i step) 0)
            (.save dctx)
            (if (= type :horizontal)
              (do
                (.fillText dctx (str i) pos 13))
              (do
                (.translate dctx 12 pos)
                (.rotate dctx (/ (* 270 js/Math.PI) 180))
                (.fillText dctx (str i) 0 0)))
            (.restore dctx))
          (recur (inc i)))))

    (let [path (js/Path2D.)]
      (loop [i minv]
        (if (> i maxv)
          (.stroke dctx path)
          (let [pos (+ (* i zoom) 0)]
            (when (= (mod i step) 0)
              (if (= type :horizontal)
                (do
                  (.moveTo path pos 17)
                  (.lineTo path pos 20))
                (do
                  (.moveTo path 17 pos)
                  (.lineTo path 20 pos))))
            (recur (inc i))))))))


(mf/defc horizontal-rule
  [{:keys [zoom vbox vport] :as props}]
  (let [canvas (mf/use-ref)
        width  (- (:width vport) 20)]
    (mf/use-layout-effect
     (mf/deps zoom width (:x vbox))
     (fn []
       (let [node (mf/ref-val canvas)
             dctx (.getContext ^js node "2d")]
         (obj/set! node "width" width)
         (draw-rule! dctx {:zoom zoom
                           :type :horizontal
                           :size width
                           :start (:x vbox)}))))

    [:canvas.horizontal-rule
     {:ref canvas
      :width width
      :height 20}]))

(mf/defc vertical-rule
  [{:keys [zoom vbox vport] :as props}]
  (let [canvas (mf/use-ref)
        height  (- (:height vport) 20)]
    (mf/use-layout-effect
     (mf/deps zoom height (:y vbox))
     (fn []
       (let [node (mf/ref-val canvas)
             dctx (.getContext ^js node "2d")]
         (obj/set! node "height" height)
         (draw-rule! dctx {:zoom zoom
                           :type :vertical
                           :size height
                           :count 100
                           :start (:y vbox)}))))

    [:canvas.vertical-rule
     {:ref canvas
      :width 20
      :height height}]))
