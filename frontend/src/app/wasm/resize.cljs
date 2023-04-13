(ns app.wasm.resize
  (:require 
   [app.common.data.macros :as dm]
   [app.util.wasm :as wasm]
   [app.util.wasm.types :as types]
   [app.wasm.common :as common]
   [promesa.core :as p]))

(defonce instance nil)
(defonce memory nil)
(defonce resize-input nil)
(defonce resize-output nil)

(defn- init-proxies
  [asm]
  (js/console.log "init resize proxies")
  (set! resize-input (types/from asm "resizeInput" "ResizeInput"))
  (set! resize-output (types/from asm "resizeOutput" "ResizeOutput")))

(defn- resize-get-handler
  [handler]
  (case handler
    :right 0
    :bottom-right 1
    :bottom 2
    :bottom-left 3
    :left 4
    :top-left 5
    :top 6
    :top-right 7))

(defn resize-start
  [handler initial]
  (common/set-point (.-start resize-input) initial)
  (set! (.-handler resize-input) (resize-get-handler handler)))

(defn resize-update-transforms
  [shape]
  (common/set-matrix (.-transform resize-input) (dm/get-prop shape :transform))
  (common/set-matrix (.-transformInverse resize-input) (dm/get-prop shape :transform-inverse)))

(defn resize-update
  [shape point point-snap lock? center?]
  (let [transform (dm/get-prop shape :transform)]
    (set! (.-rotation resize-input) (or (dm/get-prop shape :rotation) 0))
    (set! (.-shouldLock resize-input) (if lock? 1 0))
    (set! (.-shouldCenter resize-input) (if center? 1 0))
    (common/set-rect (.-selRect resize-input) (:selrect shape))
    (common/set-point (.-current resize-input) point)
    (common/set-point (.-snap resize-input) point-snap)
    (set! (. resize-input -shouldTransform) (some? transform))
    (when (some? transform) (resize-update-transforms shape))
    (.resize instance)))

(defn init!
  "Loads WebAssembly module"
  []
  (p/then
   (wasm/load "wasm/resize.release.wasm")
   (fn [asm]
     (js/console.log asm)
     (set! instance asm)
     (set! memory asm.memory)
     (init-proxies asm))))
