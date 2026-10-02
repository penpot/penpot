;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.rects
  "Rectangle scene, the smallest benchmark workload.

  Distributes rectangles over the canvas with varied sizes, translucent
  fills and centered strokes, all drawn from the scope's seeded generator.
  The `defscene` body produces the scene snapshot; `defcase` declares
  the scene and its standard cases. The three cases share parameters, so
  they render the same scene."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.builder :as sb :include-macros true]
   [benches.render-wasm.declarations :as decl :include-macros true]
   [benches.render-wasm.runtime.camera :as camera]))

;; This namespace is also loaded by Node discovery. Keep operation helpers
;; portable: browser API/helper imports belong in browser adapters supplied
;; through rtx, not in this require list or its transitive dependencies.
;; See declarations' dependency contract before adding a capability.

(def ^:const ^:private default-params
  {:count 1000
   :width 1920
   :height 1080
   :min-size 20
   :max-size 100})

(def ^:private schema:params
  "Workload parameters. Sizes are finite. `:min-size` > `:max-size` is rejected"
  [:and
   [:map {:closed true}
    [:count [:int {:min 1 :max 100000}]]
    [:width ::sm/positive-safe-number]
    [:height ::sm/positive-safe-number]
    [:min-size ::sm/positive-safe-number]
    [:max-size ::sm/positive-safe-number]]
   [:fn {:error/message "min-size exceeds max-size"}
    (fn [{:keys [min-size max-size]}]
      (<= min-size max-size))]])

(defn- rect-defaults
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

(decl/defscene :rects
  {:version 1
   :description "Seeded rectangles with translucent fills and centered strokes"
   :params-schema schema:params}
  [params]
  (let [params (merge default-params params)]
    (sb/scene {:seed (:seed params)
               :root {:x 0
                      :y 0
                      :width (:width params)
                      :height (:height params)}
               :defaults {:rect (rect-defaults params)}}
              (doseq [_ (range (:count params))]
                (sb/rect)))))

(def ^:private base-view
  {:scale 1 :x 0 :y 0})

(decl/defcase :rects/load
  {:params default-params
   :view base-view
   :context :fresh}
  [rtx]
  (camera/load! rtx))

(decl/defcase :rects/pan
  {:params default-params
   :view base-view
   :context :reuse
   :completion :render-full
   :operation {:steps 20 :dx 200 :dy 0 :settle-ms 100}}
  [rtx]
  (camera/pan! rtx))

(decl/defcase :rects/zoom
  {:params default-params
   :view base-view
   :context :reuse
   :completion :render-full
   :operation {:steps 20 :factor 1.5 :settle-ms 100}}
  [rtx]
  (camera/zoom! rtx))
