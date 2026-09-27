;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.rects
  "Rectangle scene, the smallest benchmark workload.

  Distributes rectangles over the canvas with varied sizes, translucent
  fills and centered strokes, all drawn from the scope's seeded generator.
  `build` produces the scene snapshot; `defscene` and `defcase` declare
  the scene and its standard cases. The three cases share parameters, so
  they render the same scene. The path and effect scenes arrive in
  tickets07/08.

  Operation bodies are deferred to tickets05/06. The intended threaded
  shape is:

    (defcase :rects/pan :rects {...}
      (-> rtx
          (restore! base-view)
          (pan! {:frames 20 :distance [120 60] :settle-ms 100})
          (drain! :full)))

  where `rtx` is the injected runtime and each operation contributes its
  plain-data identity to the collected case."
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.scenes.builder :as sb :include-macros true]
   [benches.render-wasm.scenes.core :as core :include-macros true]))

(def ^:private default-params
  {:count 1000
   :width 1920
   :height 1080
   :min-size 20
   :max-size 100})

(def ^:private schema:params
  "Workload parameters. Sizes are finite (`pos?` accepts Infinity, which JSON
  cannot carry and the generator cannot honor); `:min-size` above `:max-size`
  is rejected so parameter sweeps cannot silently measure a reversed range."
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

(defn build
  "Builds the seeded rectangle scene. `params` requires `:seed`; the other
  keys default to the standard case parameters."
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

(core/defscene :rects
  {:version 1
   :description "Seeded rectangles with translucent fills and centered strokes"
   :params-schema schema:params}
  build)

(def ^:private base-view
  {:scale 1 :x 0 :y 0})

(core/defcase :rects/load :rects
  {:params default-params
   :view base-view
   :context :fresh})

(core/defcase :rects/pan :rects
  {:params default-params
   :view base-view
   :context :reuse
   :completion :render-full})

(core/defcase :rects/zoom :rects
  {:params default-params
   :view base-view
   :context :reuse
   :completion :render-full})
