;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm
  "A WASM based render API"
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.impl :as ctsi]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.render-wasm.helpers :as h]
   [promesa.core :as p]))

(defn initialize
  [enabled?]
  (set! app.common.types.shape.impl/enabled-wasm-ready-shape enabled?))

(defonce internal-module #js {})

(defn create-shape
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call internal-module "_create_shape"
            (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))))

(defn use-shape
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call internal-module "_use_shape"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-shape-selrect
  [selrect]
  (h/call internal-module "_set_shape_selrect"
          (dm/get-prop selrect :x1)
          (dm/get-prop selrect :y1)
          (dm/get-prop selrect :x2)
          (dm/get-prop selrect :y2)))

(defn set-shape-transform
  [transform]
  (h/call internal-module "_set_shape_transform"
          (dm/get-prop transform :a)
          (dm/get-prop transform :b)
          (dm/get-prop transform :c)
          (dm/get-prop transform :d)
          (dm/get-prop transform :e)
          (dm/get-prop transform :f)))

(defn set-shape-rotation
  [rotation]
  (h/call internal-module "_set_shape_rotation" rotation))

(defn set-shape-children
  [shape-ids]
  (h/call internal-module "_clear_shape_children")
  (run! (fn [id]
          (let [buffer (uuid/get-u32 id)]
            (h/call internal-module "_add_shape_child"
                    (aget buffer 0)
                    (aget buffer 1)
                    (aget buffer 2)
                    (aget buffer 3))))
        shape-ids))

(defn set-shape-fills
  [fills]
  (h/call internal-module "_clear_shape_fills")
  (run! (fn [fill]
          (let [opacity (:fill-opacity fill)
                color   (:fill-color fill)]
            (when ^boolean color
              (let [rgb     (js/parseInt (subs color 1) 16)
                    r       (bit-shift-right rgb 16)
                    g       (bit-and (bit-shift-right rgb 8) 255)
                    b       (bit-and rgb 255)]
                (h/call internal-module "_add_shape_solid_fill" r g b opacity)))))
        fills))

(defn- translate-blend-mode
  [blend-mode]
  (case blend-mode
    :normal 3
    :darken 16
    :multiply 24
    :color-burn 19
    :lighten 17
    :screen 14
    :color-dodge 18
    :overlay 15
    :soft-light 21
    :hard-light 20
    :difference 22
    :exclusion 23
    :hue 25
    :saturation 26
    :color 27
    :luminosity 28
    3))

(defn set-shape-blend-mode
  [blend-mode]
  ;; These values correspond to skia::BlendMode representation
  ;; https://rust-skia.github.io/doc/skia_safe/enum.BlendMode.html
  (h/call internal-module "_set_shape_blend_mode" (translate-blend-mode blend-mode)))

(defn set-objects
  [objects]
  (let [shapes        (into [] (vals objects))

        total-shapes  (count shapes)]
    (loop [index 0]
      (when (< index total-shapes)
        (let [shape      (nth shapes index)
              id         (dm/get-prop shape :id)
              selrect    (dm/get-prop shape :selrect)
              rotation   (dm/get-prop shape :rotation)
              transform  (dm/get-prop shape :transform)
              fills      (dm/get-prop shape :fills)
              children   (dm/get-prop shape :shapes)
              blend-mode (dm/get-prop shape :blend-mode)]
          (use-shape id)
          (set-shape-selrect selrect)
          (set-shape-rotation rotation)
          (set-shape-transform transform)
          (set-shape-fills fills)
          (set-shape-blend-mode blend-mode)
          (set-shape-children children)
          (recur (inc index)))))))

(defn draw-objects
  [zoom vbox]
  (js/requestAnimationFrame
   (fn []
     (let [pan-x (- (dm/get-prop vbox :x))
           pan-y (- (dm/get-prop vbox :y))]
       (h/call internal-module "_draw_all_shapes" zoom pan-x pan-y)))))

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
        handle  (.registerContext ^js gl context #js {"majorVersion" 2})]
    (.makeContextCurrent ^js gl handle)
    ;; Initialize Skia
    (^function init-fn (.-width ^js canvas)
                       (.-height ^js canvas))
    (set! (.-width canvas) (.-clientWidth ^js canvas))
    (set! (.-height canvas) (.-clientHeight ^js canvas))))

(defonce module
  (if (exists? js/dynamicImport)
    (let [uri (cf/resolve-static-asset "js/render_wasm.js")]
      (->> (js/dynamicImport (str uri))
           (p/mcat (fn [module]
                     (let [default (unchecked-get module "default")]
                       (default))))
           (p/fmap (fn [module]
                     (set! internal-module module)
                     true))
           (p/merr (fn [cause]
                     (js/console.error cause)
                     (p/resolved false)))))
    (p/resolved false)))

(set! app.common.types.shape.impl/wasm-create-shape create-shape)
(set! app.common.types.shape.impl/wasm-use-shape use-shape)
(set! app.common.types.shape.impl/wasm-set-shape-selrect set-shape-selrect)
(set! app.common.types.shape.impl/wasm-set-shape-transform set-shape-transform)
(set! app.common.types.shape.impl/wasm-set-shape-rotation set-shape-rotation)
(set! app.common.types.shape.impl/wasm-set-shape-fills set-shape-fills)
(set! app.common.types.shape.impl/wasm-set-shape-blend-mode set-shape-blend-mode)
(set! app.common.types.shape.impl/wasm-set-shape-children set-shape-children)
