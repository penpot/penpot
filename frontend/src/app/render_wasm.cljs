;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm
  "A WASM based render API"
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape.impl]
   [app.config :as cf]
   [promesa.core :as p]))

(def enabled?
  (contains? cf/flags :render-wasm))

(set! app.common.types.shape.impl/enabled-wasm-ready-shape enabled?)

(defonce internal-module #js {})
(defonce internal-gpu-state #js {})

;; TODO: remove the `take` once we have the dynamic data structure in Rust
(def xform
  (comp
   (remove cfh/root?)
   (take 2048)))

;; Size in number of f32 values that represents the shape selrect (
(def rect-size 4)

(defn set-objects
  [objects]
  ;; FIXME: maybe change the name of `_shapes_buffer` (?)
  (let [get-shapes-buffer-ptr
        (unchecked-get internal-module "_shapes_buffer")

        heap
        (unchecked-get internal-module "HEAPF32")

        shapes
        (into [] xform (vals objects))

        total-shapes
        (count shapes)

        heap-offset
        (get-shapes-buffer-ptr)

        heap-size
        (* rect-size total-shapes)

        mem
        (js/Float32Array. (.-buffer heap)
                          heap-offset
                          heap-size)]

    (loop [index 0]
      (when (< index total-shapes)
        (let [shape (nth shapes index)]
          (.set ^js mem (.-buffer shape) (* index rect-size))
          (recur (inc index)))))))

(defn draw-objects
  [zoom vbox]
  (let [draw-all-shapes (unchecked-get internal-module "_draw_all_shapes")]
    (js/requestAnimationFrame
     (fn []
       (let [pan-x (- (dm/get-prop vbox :x))
             pan-y (- (dm/get-prop vbox :y))]
         (draw-all-shapes internal-gpu-state zoom pan-x pan-y))))))

(defn cancel-draw
  [frame-id]
  (when (some? frame-id)
    (js/cancelAnimationFrame frame-id)))

(def ^:private canvas-options
  #js {:antialias true
       :depth true
       :stencil true
       :alpha true})

(defn clear-canvas
  []
  ;; TODO: perform corresponding cleaning
  )

(defn assign-canvas
  [canvas]
  (let [gl      (unchecked-get internal-module "GL")
        init-fn (unchecked-get internal-module "_init")

        context (.getContext ^js canvas "webgl2" canvas-options)

        ;; Register the context with emscripten
        handle  (.registerContext ^js gl context #js {"majorVersion" 2})
        _       (.makeContextCurrent ^js gl handle)

        ;; Initialize Skia
        state   (init-fn (.-width ^js canvas)
                         (.-height ^js canvas))]

    (set! (.-width canvas) (.-clientWidth ^js canvas))
    (set! (.-height canvas) (.-clientHeight ^js canvas))
    (set! internal-gpu-state state)))

(defonce module
  (->> (js/dynamicImport "/js/render_wasm.js")
       (p/mcat (fn [module]
                 (let [default (unchecked-get module "default")]
                   (default))))
       (p/fmap (fn [module]
                 (set! internal-module module)
                 true))
       (p/merr (fn [cause]
                 (js/console.error cause)
                 (p/resolved false)))))
