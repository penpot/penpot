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
   [app.main.data.render-wasm :as drw]
   [app.main.store :as st]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
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

(defn init-skia
  [canvas]
  (let [init-fn (unchecked-get internal-module "_init")
        state   (init-fn (.-width ^js canvas)
                         (.-height ^js canvas))]
    (set! internal-gpu-state state)))

;; NOTE: This function can be called externally
;; by the button in the context lost component (shown
;; in viewport-wasm) or called internally by
;; on-webgl-context
(defn restore-canvas
  [canvas]
  (st/emit! (drw/context-restored))
  ;; We need to reinitialize skia when the
  ;; context is restored.
  (init-skia canvas))

;; Handles both events: webglcontextlost and
;; webglcontextrestored
(defn on-webgl-context
  [event]
  (dom/prevent-default event)
  (if (= (.-type event) "webglcontextlost")
    (st/emit! (drw/context-lost))
    (restore-canvas (dom/get-target event))))

(defn dispose-canvas
  [canvas]
  ;; TODO: perform corresponding cleaning
  (.removeEventListener canvas "webglcontextlost" on-webgl-context)
  (.removeEventListener canvas "webglcontextrestored" on-webgl-context))

(defn init-debug-webgl-context-state
  [context]
  (let [context-extension (.getExtension ^js context "WEBGL_lose_context")
        info-extension (.getExtension ^js context "WEBGL_debug_renderer_info")]
    (set! (.-penpotGL js/window) #js {:context context-extension
                                      :renderer info-extension})
    (js/console.log "WEBGL_lose_context" context-extension)
    (js/console.log "WEBGL_debug_renderer_info" info-extension)))

(defn setup-canvas
  [canvas]
  (let [gl      (unchecked-get internal-module "GL")
        context (.getContext ^js canvas "webgl2" canvas-options)

        ;; Register the context with emscripten
        handle  (.registerContext ^js gl context #js {"majorVersion" 2})
        _       (.makeContextCurrent ^js gl handle)]

    (when (dbg/enabled? :gl-context)
      (init-debug-webgl-context-state context))

    (.addEventListener canvas "webglcontextlost" on-webgl-context)
    (.addEventListener canvas "webglcontextrestored" on-webgl-context)

    (set! (.-width canvas) (.-clientWidth ^js canvas))
    (set! (.-height canvas) (.-clientHeight ^js canvas))

    (init-skia canvas)))

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
