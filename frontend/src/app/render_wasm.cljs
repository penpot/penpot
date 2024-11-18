;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm
  "A WASM based render API"
  (:require
   [app.common.colors :as cc]
   [app.common.data.macros :as dm]
   [app.common.types.shape.impl :as ctsi]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [promesa.core :as p]))

(defn initialize
  [enabled?]
  (set! app.common.types.shape.impl/enabled-wasm-ready-shape enabled?))

(defonce internal-module #js {})

;; TODO: remove the `take` once we have the dynamic data structure in Rust
(def xform
  (comp
   (take 2048)))

(defn create-shape
  [id]
  (let [buffer       (uuid/get-u32 id)
        create-shape (unchecked-get internal-module "_create_shape")]
    (^function create-shape (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))))

(defn use-shape
  [id]
  (let [buffer    (uuid/get-u32 id)
        use-shape (unchecked-get internal-module "_use_shape")]
    (^function use-shape (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))))

(defn set-shape-selrect
  [selrect]
  (let [x1 (:x1 selrect)
        y1 (:y1 selrect)
        x2 (:x2 selrect)
        y2 (:y2 selrect)
        set-shape-selrect (unchecked-get internal-module "_set_shape_selrect")]
    (^function set-shape-selrect x1 y1 x2 y2)))

(defn set-shape-transform
  [transform]
  (let [a (:a transform)
        b (:b transform)
        c (:c transform)
        d (:d transform)
        e (:e transform)
        f (:f transform)
        set-shape-transform (unchecked-get internal-module "_set_shape_transform")]
    (^function set-shape-transform a b c d e f)))

(defn set-shape-rotation
  [rotation]
  (let [set-shape-rotation (unchecked-get internal-module "_set_shape_rotation")]
    (^function set-shape-rotation rotation)))

(defn set-shape-children
  [shape_ids]
  (let [clear-shape-children (unchecked-get internal-module "_clear_shape_children")
        add-shape-child      (unchecked-get internal-module "_add_shape_child")]
    (^function clear-shape-children)
    (doseq [id shape_ids]
      (let [buffer (uuid/get-u32 id)]
        (^function add-shape-child (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))))))

(defn set-shape-fills
  [fills]
  (let [clear-shape-fills (unchecked-get internal-module "_clear_shape_fills")
        add-shape-fill    (unchecked-get internal-module "_add_shape_solid_fill")]
    (^function clear-shape-fills)
    (doseq [fill (filter #(contains? % :fill-color) fills)]
      (let [a       (:fill-opacity fill)
            [r g b] (cc/hex->rgb (:fill-color fill))]
        (^function add-shape-fill r g b a)))))

(defn set-shape-blend-mode
  [blend-mode]
  ;; These values correspond to skia::BlendMode representation
  ;; https://rust-skia.github.io/doc/skia_safe/enum.BlendMode.html
  (let [encoded-blend (case blend-mode
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
                        3)
        set-shape-blend-mode (unchecked-get internal-module "_set_shape_blend_mode")]
    (^function set-shape-blend-mode encoded-blend)))

(defn set-objects
  [objects]
  (let [shapes        (into [] xform (vals objects))
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
     (let [pan-x           (- (dm/get-prop vbox :x))
           pan-y           (- (dm/get-prop vbox :y))
           draw-all-shapes (unchecked-get internal-module "_draw_all_shapes")]
       (^function draw-all-shapes zoom pan-x pan-y)))))

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
