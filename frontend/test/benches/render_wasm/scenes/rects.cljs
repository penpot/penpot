;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.rects
  "Rectangle fixture, the smallest benchmark workload.

  Distributes rectangles over the canvas with varied sizes, translucent
  fills and centered strokes, all drawn from the scope's seeded generator.
  Ticket20 wraps `build` with case declarations; the path and effect
  fixtures arrive in tickets07/08."
  (:require
   [benches.render-wasm.scenes.builder :as sb :include-macros true]))

(def default-params
  {:count 1000
   :width 1920
   :height 1080
   :min-size 20
   :max-size 100})

(defn rect-defaults
  "Attribute generators for one rectangle of the workload."
  [{:keys [width height min-size max-size]}]
  {:x       (sb/gen-int 0 width)
   :y       (sb/gen-int 0 height)
   :width   (sb/gen-int min-size max-size)
   :height  (sb/gen-int min-size max-size)
   :fills   (sb/gen-vector (sb/gen-fill))
   :strokes (sb/gen-vector (sb/gen-stroke 10))
   :r1      (sb/gen-int 0 24)
   :r2      (sb/gen-int 0 24)
   :r3      (sb/gen-int 0 24)
   :r4      (sb/gen-int 0 24)})

(defn build
  "Builds the seeded rectangle fixture. `params` requires `:seed`; the other
  keys default to `default-params`."
  [params]
  (let [params (merge default-params params)]
    (sb/fixture {:seed (:seed params)
                 :root {:x 0
                        :y 0
                        :width (:width params)
                        :height (:height params)}
                 :defaults {:rect (rect-defaults params)}}
                (doseq [_ (range (:count params))]
                  (sb/rect)))))
