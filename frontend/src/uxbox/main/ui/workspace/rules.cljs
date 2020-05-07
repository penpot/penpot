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
   [uxbox.util.object :as obj]))

(def STEP-PADDING 20)

(mf/defc horizontal-rule
  [{:keys [zoom size]}]
  (let [canvas (mf/use-ref)
        {:keys [x viewport-width width]} size]

    (mf/use-layout-effect
     (mf/deps viewport-width width x zoom)
     (fn []
       (let [node (mf/ref-val canvas)
             dctx (.getContext node "2d")

             btm 1
             trx (- (* (- 0 x) zoom) 50)

             min-val (js/Math.round x)
             max-val (js/Math.round (+ x (/ viewport-width zoom)))

             tmp0 (js/Math.abs (- max-val min-val))
             tmp1 (js/Math.round (/ tmp0 200))
             btm  (max (* btm (* 10 tmp1)) 1)]

         (obj/set! node "width" viewport-width)

         (obj/set! dctx "fillStyle" "#E8E9EA")
         (.fillRect dctx 0 0 viewport-width 20)

         (.save dctx)
         (.translate dctx trx 0)

         (obj/set! dctx "font" "12px serif")
         (obj/set! dctx "fillStyle" "#7B7D85")
         (obj/set! dctx "strokeStyle" "#7B7D85")
         (obj/set! dctx "textAlign" "center")

         (loop [i min-val]
           (when (< i max-val)
             (let [pos (+ (* i zoom) 50)]
               (when (= (mod i btm) 0)
                 (.fillText dctx (str i) (- pos 0) 13))
               (recur (+ i 1)))))

         (let [path (js/Path2D.)]
           (loop [i min-val]
             (if (> i max-val)
               (.stroke dctx path)
               (let [pos (+ (* i zoom) 50)]
                 (when (= (mod i btm) 0)
                   (.moveTo path pos 17)
                   (.lineTo path pos STEP-PADDING))
                 (recur (inc i))))))

         (.restore dctx))))

    [:canvas.horizontal-rule {:ref canvas :width (:viewport-width size) :height 20}]))


;; --- Vertical Rule (Component)

(mf/defc vertical-rule
  {::mf/wrap [mf/memo #(mf/throttle % 60)]}
  [{:keys [zoom size]}]
  (let [canvas (mf/use-ref)
        {:keys [y height viewport-height]} size]
    (mf/use-layout-effect
     (mf/deps height y zoom)
     (fn []
       (let [node (mf/ref-val canvas)
             dctx (.getContext node "2d")

             btm 1
             try (- (* (- 0 y) zoom) 50)

             min-val (js/Math.round y)
             max-val (js/Math.round (+ y (/ viewport-height zoom)))

             tmp0 (js/Math.abs (- max-val min-val))
             tmp1 (js/Math.round (/ tmp0 100))
             btm  (max (* btm (* 10 tmp1)) 1)]

         (obj/set! node "height" viewport-height)

         (obj/set! dctx "fillStyle" "#E8E9EA")
         (.fillRect dctx 0 0 20 viewport-height)

         (obj/set! dctx "font" "11px serif")
         (obj/set! dctx "fillStyle" "#7B7D85")
         (obj/set! dctx "strokeStyle" "#7B7D85")
         (obj/set! dctx "textAlign" "center")

         (.translate dctx 0 try)

         (loop [i min-val]
           (when (< i max-val)
             (let [pos (+ (* i zoom) 50)]
               (when (= (mod i btm) 0)
                 (.save dctx)
                 (.translate dctx 12 pos)
                 (.rotate dctx (/ (* 270 js/Math.PI) 180))
                 (.fillText dctx (str i) 0 0)
                 (.restore dctx))
               (recur (inc i)))))

         (let [path (js/Path2D.)]
           (loop [i min-val]
             (if (> i max-val)
               (.stroke dctx path)
               (let [pos (+ (* i zoom) 50)]
                 (when (= (mod i btm) 0)
                   (.moveTo path 17 pos)
                   (.lineTo path STEP-PADDING pos))
                 (recur (inc i)))))))))

    [:canvas.vertical-rule {:ref canvas :width 20 :height height}]))

