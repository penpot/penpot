;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-v2.rs
  (:require
   ["./rs.js" :as render-v2]
   [app.config :as cf]
   [beicon.v2.core :as rx]
   [goog.object :as gobj]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(defonce ^:dynamic internal-module #js {})

(defn set-canvas
  [canvas vbox objects]
  (let [gl (gobj/get ^js internal-module "GL")
        context (.getContext canvas "webgl2" {"antialias" true
                                              "depth" true
                                              "stencil" true
                                              "alpha" true})
        _ (js/console.log "context" context)
        ;; Register the context with emscripten
        handle (.registerContext gl context {"majorVersion" 2})
        _ (.makeContextCurrent gl handle)
        ;; Initialize Skia
        state (._init ^js internal-module (.-width canvas) (.-height canvas))
        draw_rect (gobj/get ^js internal-module "_draw_rect")
        resize_surface (gobj/get ^js internal-module "_resize_surface")]

    (set! (.-width canvas) (.-clientWidth canvas))
    (set! (.-height canvas) (.-clientHeight canvas))
    
    ;; (resize_surface state (.-clientWidth canvas) (.-clientHeight canvas))
    
    (doseq [shape (vals objects)]
      (let [sr (:selrect shape)]
        (println "-----" (:x1 sr) (:y1 sr) (:x2 sr) (:y2 sr))
        ;; TODO: scale y translate con el vbox
        (draw_rect state (:x1 sr) (:y1 sr) (:x2 sr) (:y2 sr))))

    #_(draw_rect state 100 100 500 500)
    (println "set-canvas ok" (.-width canvas) (.-height canvas))))

(defn on-init
  [module']
  (set! internal-module module')
  (println "on-init ok"))

(defn init
  []
  (p/then (render-v2) #(on-init %)))
