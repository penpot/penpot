;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-v2
  (:require
   ["./render_v2.js" :as render-v2]
   [app.config :as cf]
   [goog.object :as gobj]
   [promesa.core :as p]))

(defn enabled?
  []
  (contains? cf/flags :render-v2))

(defonce ^:dynamic internal-module #js {})
(defonce ^:dynamic gpu-state #js {})

(defn draw-canvas [vbox zoom objects]
  (let [draw-rect (gobj/get ^js internal-module "_draw_rect")
        translate (gobj/get ^js internal-module "_translate")
        reset-canvas (gobj/get ^js internal-module "_reset_canvas")
        scale (gobj/get ^js internal-module "_scale")
        flush (gobj/get ^js internal-module "_flush")]
    (js/requestAnimationFrame (fn []
                                (reset-canvas gpu-state)
                                (scale gpu-state zoom zoom)
                                (translate gpu-state (- (:x vbox)) (- (:y vbox)))
                                (doseq [shape (vals objects)]
                                  (let [sr (:selrect shape)]
                                    (draw-rect gpu-state (:x1 sr) (:y1 sr) (:x2 sr) (:y2 sr))))
                                (flush gpu-state)))))

(defn set-canvas
  [canvas vbox zoom objects]
  (let [gl (gobj/get ^js internal-module "GL")
        context (.getContext canvas "webgl2" {"antialias" true
                                              "depth" true
                                              "stencil" true
                                              "alpha" true})
        ;; Register the context with emscripten
        handle (.registerContext gl context {"majorVersion" 2})
        _ (.makeContextCurrent gl handle)
        ;; Initialize Skia
        state (._init ^js internal-module (.-width canvas) (.-height canvas))]

    (set! (.-width canvas) (.-clientWidth canvas))
    (set! (.-height canvas) (.-clientHeight canvas))
    (set! gpu-state state))
  (draw-canvas vbox zoom objects))

(defn on-init
  [module']
  (set! internal-module module'))

(defn init
  []
  (p/then (render-v2) #(on-init %)))