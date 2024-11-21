;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.api
  "A WASM based render API"
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.render-wasm.helpers :as h]
   [app.util.functions :as fns]
   [promesa.core :as p]))

(defonce internal-frame-id nil)
(defonce internal-module #js {})

;; This should never be called from the outside.
;; This function receives a "time" parameter that we're not using but maybe in the future could be useful (it is the time since
;; the window started rendering elements so it could be useful to measure time between frames).
(defn- render
  [_]
  (h/call internal-module "_render")
  (set! internal-frame-id nil))

(defn cancel-render
  []
  (when internal-frame-id
    (js/cancelAnimationFrame internal-frame-id)
    (set! internal-frame-id nil)))

(defn request-render
  []
  (when internal-frame-id (cancel-render))
  (set! internal-frame-id (js/requestAnimationFrame render)))

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

(def debounce-render (fns/debounce render 100))

(defn set-view
  [zoom vbox]
  (h/call internal-module "_set_view" zoom (- (:x vbox)) (- (:y vbox)))
  (h/call internal-module "_navigate")
  (debounce-render))

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
          (recur (inc index))))))
  (request-render))

(def ^:private canvas-options
  #js {:antialias false
       :depth true
       :stencil true
       :alpha true})

(defn clear-canvas
  []
  ;; TODO: perform corresponding cleaning
  )

(defn resize-canvas
  [width height]
  (h/call internal-module "_resize_canvas" width height))

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
                       (.-height ^js canvas)
                       1)
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
