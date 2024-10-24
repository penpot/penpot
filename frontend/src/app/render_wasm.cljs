;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm
  "A WASM based render API"
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [promesa.core :as p]))

(def enabled?
  (contains? cf/flags :render-wasm))

(defonce ^:dynamic internal-module #js {})
(defonce ^:dynamic internal-gpu-state #js {})

(defn draw-objects
  [objects zoom vbox]
  (let [draw-rect    (unchecked-get internal-module "_draw_rect")
        translate    (unchecked-get internal-module "_translate")
        reset-canvas (unchecked-get internal-module "_reset_canvas")
        scale        (unchecked-get internal-module "_scale")
        flush        (unchecked-get internal-module "_flush")
        gpu-state    internal-gpu-state]

    (js/requestAnimationFrame
     (fn []
       (reset-canvas gpu-state)
       (scale gpu-state zoom zoom)

       (let [x (dm/get-prop vbox :x)
             y (dm/get-prop vbox :y)]
         (translate gpu-state (- x) (- y)))

       (run! (fn [shape]
               ;; (js/console.log "render-shape" (.-buffer shape))
               (let [selrect (dm/get-prop shape :selrect)
                     x1      (dm/get-prop selrect :x1)
                     y1      (dm/get-prop selrect :y1)
                     x2      (dm/get-prop selrect :x2)
                     y2      (dm/get-prop selrect :y2)]
                 ;; (prn (:id shape) selrect)
                 (draw-rect gpu-state x1 y1 x2 y2)))
             (vals objects))

       (flush gpu-state)))))

(defn cancel-draw
  [sem]
  (when (some? sem)
    (js/cancelAnimationFrame sem)))

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
