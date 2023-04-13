(ns app.wasm.transform
  (:require
   [app.common.data.macros :as dm]
   [app.util.wasm :as wasm]
   [app.util.wasm.types :as types]
   [app.wasm.common :as common]
   [promesa.core :as p]))

(defonce instance nil)
(defonce memory nil)
(defonce transform-input nil)
(defonce transform-output nil)

(defn- init-proxies
  [asm]
  (js/console.log "init transform proxies")
  (set! transform-input (types/from asm "transformInput" "TransformInput"))
  (set! transform-output (types/from asm "transformOutput" "TransformOutput")))

(defn set-transform-matrix
  [matrix]
  (let [^js matrix' (.-matrix transform-input)]
    (common/set-matrix matrix' matrix)))

(defn set-transform-transform
  [tf]
  (let [^js matrix' (.-transform transform-input)]
    (common/set-matrix matrix' tf)))

(defn set-transform-transform-inverse
  [tfi]
  (let [^js matrix' (.-transformInverse transform-input)]
    (common/set-matrix matrix' tfi)))

(defn set-transform-vector
  [vector]
  (let [^js vector' (.-vector transform-input)]
    (common/set-point vector' vector)))

(defn set-transform-center
  [center]
  (let [^js center' (.-center transform-input)]
    (common/set-point center' center)))

(defn set-transform-origin
  [origin]
  (let [^js origin' (.-origin transform-input)]
    (common/set-point origin' origin)))

(defn set-transform-should-transform
  [should-transform]
  (set! (.-shouldTransform transform-input) should-transform))

(defn set-transform-rotation
  [rotation]
  (set! (.-rotation transform-input) rotation))

(defn add-move-modifier
  [modifier]
  (let [vector (dm/get-prop modifier :vector)]
    (set-transform-vector vector)
    (.addMove instance (dm/get-prop modifier :order))))

(defn add-resize-modifier
  [modifier]
  (let [tf     (dm/get-prop modifier :transform)
        tfi    (dm/get-prop modifier :transform-inverse)
        vector (dm/get-prop modifier :vector)
        origin (dm/get-prop modifier :origin)]
    (set-transform-origin origin)
    (set-transform-vector vector)
    (when (some? tf) (set-transform-transform tf))
    (when (some? tfi) (set-transform-transform-inverse tfi))
    (set-transform-should-transform ^boolean (or (some? tf) (some? tfi)))
    (.addResize instance (dm/get-prop modifier :order))))

(defn add-rotation-modifier
  [modifier]
  (let [center   (dm/get-prop modifier :center)
        rotation (dm/get-prop modifier :rotation)]
    (set-transform-center center)
    (set-transform-rotation rotation)
    (.addRotation instance (dm/get-prop modifier :order))))

(defn add-modifier
  [modifier]
  (let [type (dm/get-prop modifier :type)]
    (case type
      :move (add-move-modifier modifier)
      :resize (add-resize-modifier modifier)
      :rotation (add-rotation-modifier modifier))))

(defn modifiers->transform
  [modifiers]
  (.reset ^js instance)
  (run! add-modifier modifiers)
  (.compute ^js instance)
  (.-matrix ^js transform-output))

(defn init!
  "Loads WebAssembly module"
  []
  (p/then
   (wasm/load "wasm/transform.release.wasm")
   (fn [asm]
     (js/console.log asm)
     (set! instance asm)
     (set! memory asm.memory)
     (init-proxies asm))))
