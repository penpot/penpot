;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-v2.rs
  (:require
   ["./rs.js" :as render-v2]
   [app.common.colors :as cc]
   [app.config :as cf]
   [beicon.v2.core :as rx]
   [goog.object :as gobj]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(defonce ^:dynamic internal-module #js {})
(defonce ^:dynamic gpu-state #js {})
(defonce ^:dynamic shapes-ptr nil)
(defonce ^:dynamic shapes-size nil)

(defn draw-canvas [vbox zoom objects]
  (let [draw-rect (gobj/get ^js internal-module "_draw_rect")
        translate (gobj/get ^js internal-module "_translate")
        reset-canvas (gobj/get ^js internal-module "_reset_canvas")
        scale (gobj/get ^js internal-module "_scale")
        flush (gobj/get ^js internal-module "_flush")
        draw-shapes (gobj/get ^js internal-module "_draw_shapes")]
        (js/requestAnimationFrame (fn []
                                    (draw-shapes gpu-state shapes-ptr shapes-size zoom (- (:x vbox)) (- (:y vbox)))))))

(defn set-objects
  [vbox zoom objects]
  (let [alloc-rects (gobj/get ^js internal-module "_alloc_rects")
        free_rects (gobj/get ^js internal-module "_free_rects")
        supported-shapes (filter (fn [shape] (not= (:type shape) :frame)) (vals objects))
        heap (gobj/get ^js internal-module "HEAPF32")
        ;; Each F32 are 4 bytes
        ;; Each rect has:
        ;;  - 4 F32 for points coordinates
        ;;  - 4 F32 for color
        ;; rect-size (* 8 4)
        rect-size (* 8 4)
        ;; kk (data gpu-state)
        ]
    (when shapes-ptr
      (free_rects shapes-ptr shapes-size))

    (let [ptr (alloc-rects (count supported-shapes))]
      (doseq [[shape index] (zipmap supported-shapes (range))]
        (let [sr (:selrect shape)
              [r g b] (cc/hex->rgb (-> shape :fills first :fill-color))
              alpha (-> shape :fills first :fill-opacity)
              mem (js/Float32Array. (.-buffer heap) (+ ptr (* rect-size index)) rect-size)]
          (set! shapes-ptr ptr)
          (set! shapes-size (count supported-shapes))
          (.set mem (js/Float32Array. (clj->js [(:x1 sr) (:y1 sr) (:x2 sr) (:y2 sr) r g b (or alpha 1)]))))))
    (draw-canvas vbox zoom objects)))

(defn set-objects-benchmark
  [vbox zoom]
  (let [alloc-rects (gobj/get ^js internal-module "_alloc_rects")
        free_rects (gobj/get ^js internal-module "_free_rects")
        shape-count 10000
        heap (gobj/get ^js internal-module "HEAPF32")
        ;; Each F32 are 4 bytes
        ;; Each rect has:
        ;;  - 4 F32 for points coordinates
        ;;  - 4 F32 for color
        ;; rect-size (* 8 4)
        rect-bytes (* 8 4)
        ]
    (when shapes-ptr
      (free_rects shapes-ptr shape-count))

    (let
      [ptr (alloc-rects shape-count)]
       (doseq [index (take shape-count (iterate inc 0))]
        (let [mem (js/Float32Array. (.-buffer heap) (+ ptr (* rect-bytes index)) rect-bytes)
              x1 (rand-int 4096)
              x2 (+ x1 (rand-int 256))
              y1 (rand-int 4096)
              y2 (+ y1 (rand-int 256))]
          (set! shapes-ptr ptr)
          (set! shapes-size shape-count)
          ;; (.set mem (js/Float32Array. (clj->js [0 0 64 64 255 0 0 1])))
          ;; (.set mem (js/Float32Array. (clj->js [(* index 72) 0 (+ (* index 72) 64) 64 255 0 0 1])))
          (.set mem (js/Float32Array. (clj->js [x1 y1 x2 y2 (rand-int 255) (rand-int 255) (rand-int 255) 1])))
        )))
    (draw-canvas vbox zoom nil)))

(defn set-canvas
  [canvas vbox zoom objects]
  (let [gl (gobj/get ^js internal-module "GL")
        context (.getContext canvas "webgl2" {;; "antialias" true
                                              ;; "depth" true
                                              ;; "stencil" false
                                              "alpha" true})
        ;; Register the context with emscripten
        handle (.registerContext gl context {"majorVersion" 2})
        _ (.makeContextCurrent gl handle)
        ;; Initialize Skia
        state (._init ^js internal-module (.-width canvas) (.-height canvas))]

    (set! (.-width canvas) (.-clientWidth canvas))
    (set! (.-height canvas) (.-clientHeight canvas))
    (set! gpu-state state)
    (set-objects-benchmark vbox zoom)))
    ;; (set-objects vbox zoom objects)))

(defn on-init
  [module']
  (set! internal-module module')
  )

(defn init
  []
  (p/then (render-v2) #(on-init %)))
