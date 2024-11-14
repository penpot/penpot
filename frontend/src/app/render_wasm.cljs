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
   [app.main.features :as features]
   [app.main.store :as st]
   [app.util.object :as obj]
   [promesa.core :as p]))

(def enabled?
  (features/active-feature? @st/state "render-wasm/v1"))

(set! app.common.types.shape.impl/enabled-wasm-ready-shape enabled?)

(defonce internal-module #js {})

;; TODO: remove the `take` once we have the dynamic data structure in Rust
(def xform
  (comp
   (take 2048)))

(defn create-shape
  [id]
  (let [buffer (uuid/uuid->u32 id)]
    (._create_shape ^js internal-module (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))))

(defn use-shape
  [id]
  (let [buffer (uuid/uuid->u32 id)]
    (._use_shape ^js internal-module (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))))

(defn set-shape-selrect
  [selrect]
  (let [x1 (:x1 selrect)
        y1 (:y1 selrect)
        x2 (:x2 selrect)
        y2 (:y2 selrect)]
    (._set_shape_selrect ^js internal-module x1 y1 x2 y2)))

(defn set-shape-transform
  [transform]
  (let [a (:a transform)
        b (:b transform)
        c (:c transform)
        d (:d transform)
        e (:e transform)
        f (:f transform)]
    (._set_shape_transform ^js internal-module a b c d e f)))

(defn set-shape-rotation
  [rotation]
  (._set_shape_rotation ^js internal-module rotation))

(defn set-shape-children
  [shape_ids]
  (._clear_shape_children ^js internal-module)
  (doseq [id shape_ids]
    (let [buffer (uuid/uuid->u32 id)]
      (._add_shape_child ^js internal-module (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3)))))

(defn set-shape-fills
  [fills]
  (._clear_shape_fills ^js internal-module)
  (doseq [fill (filter #(contains? % :fill-color) fills)]
    (let [a       (:fill-opacity fill)
          [r g b] (cc/hex->rgb (:fill-color fill))]
      (._add_shape_solid_fill ^js internal-module r g b a))))

(defn set-objects
  [objects]
  (let [shapes        (into [] xform (vals objects))
        total-shapes  (count shapes)]
    (loop [index 0]
      (when (< index total-shapes)
        (let [shape     (nth shapes index)
              id        (dm/get-prop shape :id)
              selrect   (dm/get-prop shape :selrect)
              rotation  (dm/get-prop shape :rotation)
              transform (dm/get-prop shape :transform)
              fills     (dm/get-prop shape :fills)
              children  (dm/get-prop shape :shapes)]
          (use-shape id)
          (set-shape-selrect selrect)
          (set-shape-rotation rotation)
          (set-shape-transform transform)
          (set-shape-fills fills)
          (set-shape-children children)
          (recur (inc index)))))))

(defn draw-objects
  [zoom vbox]
  (js/requestAnimationFrame
   (fn []
     (let [pan-x (- (dm/get-prop vbox :x))
           pan-y (- (dm/get-prop vbox :y))]
       (._draw_all_shapes ^js internal-module zoom pan-x pan-y)))))

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
    (init-fn (.-width ^js canvas)
             (.-height ^js canvas))
    (set! (.-width canvas) (.-clientWidth ^js canvas))
    (set! (.-height canvas) (.-clientHeight ^js canvas))

    (obj/set! js/window "shape_list" (fn [] ((unchecked-get internal-module "_shape_list"))))))

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

(set! app.common.types.shape.impl/wasm-create-shape create-shape)
(set! app.common.types.shape.impl/wasm-use-shape use-shape)
(set! app.common.types.shape.impl/wasm-set-shape-selrect set-shape-selrect)
(set! app.common.types.shape.impl/wasm-set-shape-transform set-shape-transform)
(set! app.common.types.shape.impl/wasm-set-shape-rotation set-shape-rotation)
(set! app.common.types.shape.impl/wasm-set-shape-fills set-shape-fills)
(set! app.common.types.shape.impl/wasm-set-shape-children set-shape-children)
