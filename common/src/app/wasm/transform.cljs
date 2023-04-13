;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.wasm.transform
  (:require-macros [app.wasm.impl.embed :as embed])
  (:require
   [app.common.logging :as log]
   [app.common.data.macros :as dm]
   [app.wasm.impl.loader :as loader]
   [app.wasm.impl.types :as types]
   [app.wasm.common :as common]
   [promesa.core :as p]))

(defonce instance nil)
(defonce memory nil)
(defonce transform-input nil)
(defonce transform-output nil)

(defn set-matrix!
  [matrix]
  (let [^js matrix' (.-matrix transform-input)]
    (common/set-matrix! matrix' matrix)))

(defn set-transform!
  [tf]
  (let [^js matrix' (.-transform transform-input)]
    (common/set-matrix! matrix' tf)))

(defn set-transform-inverse!
  [tfi]
  (let [^js matrix' (.-transformInverse transform-input)]
    (common/set-matrix! matrix' tfi)))

(defn set-vector!
  [vector]
  (let [^js vector' (.-vector transform-input)]
    (common/set-point! vector' vector)))

(defn set-center!
  [center]
  (let [^js center' (.-center transform-input)]
    (common/set-point! center' center)))

(defn set-origin!
  [origin]
  (let [^js origin' (.-origin transform-input)]
    (common/set-point! origin' origin)))

(defn set-should-transform!
  [should-transform]
  (set! (.-shouldTransform transform-input) should-transform))

(defn set-rotation!
  [rotation]
  (set! (.-rotation transform-input) rotation))

(defn add-move-modifier!
  [modifier]
  (let [vector (dm/get-prop modifier :vector)]
    (set-vector! vector)
    (.addMove instance (dm/get-prop modifier :order))))

(defn add-resize-modifier!
  [modifier]
  (let [tf     (dm/get-prop modifier :transform)
        tfi    (dm/get-prop modifier :transform-inverse)
        vector (dm/get-prop modifier :vector)
        origin (dm/get-prop modifier :origin)]
    (set-origin! origin)
    (set-vector! vector)

    (when ^boolean tf (set-transform! tf))
    (when ^boolean tfi (set-transform-inverse! tfi))
    (set-should-transform! ^boolean (or (some? tf)
                                        (some? tfi)))

    (.addResize instance (dm/get-prop modifier :order))))

(defn add-rotation-modifier!
  [modifier]
  (let [center   (dm/get-prop modifier :center)
        rotation (dm/get-prop modifier :rotation)]
    (set-center! center)
    (set-rotation! rotation)
    (.addRotation instance (dm/get-prop modifier :order))))

(defn add-modifier!
  [modifier]
  (let [type (dm/get-prop modifier :type)]
    (case type
      :move (add-move-modifier! modifier)
      :resize (add-resize-modifier! modifier)
      :rotation (add-rotation-modifier! modifier))))

(defn modifiers->transform
  [modifiers]
  (.reset ^js instance)
  (run! add-modifier! modifiers)
  (.compute ^js instance)
  (.-matrix ^js transform-output))

(defonce wasm:module
  (embed/read-as-base64 "app/wasm/transform.wasm"))

(defn- init-proxies!
  [asm]
  (set! transform-input (types/from asm "transformInput" "TransformInput"))
  (set! transform-output (types/from asm "transformOutput" "TransformOutput")))

(defn- initialized
  [wobj]
  (set! instance wobj)
  (set! memory (.-memory wobj))
  (init-proxies! wobj))

(defn init!
  "Loads WebAssembly module"
  []
  (->> (loader/loadFromBase64 wasm:module)
       (p/fmap initialized)))
