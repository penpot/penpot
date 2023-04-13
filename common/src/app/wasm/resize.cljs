;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.wasm.resize
  (:require-macros [app.wasm.impl.embed :as embed])
  (:require
   [app.common.data.macros :as dm]
   [app.wasm.impl.loader :as loader]
   [app.wasm.impl.types :as types]
   [app.wasm.common :as common]
   [promesa.core :as p]))

(defonce instance nil)
(defonce memory nil)
(defonce resize-input nil)
(defonce resize-output nil)

(defn- translate-handler
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

(defn initialize!
  [handler initial]
  (common/set-point! (.-start resize-input) initial)
  (set! (.-handler resize-input) (translate-handler handler)))

(defn- update-transforms!
  [shape]
  (common/set-matrix! (.-transform resize-input) (get shape :transform))
  (common/set-matrix! (.-transformInverse resize-input) (get shape :transform-inverse)))

(defn update!
  [shape point point-snap lock? center?]
  (let [transform (get shape :transform)]
    (set! (.-rotation resize-input)        (or (get shape :rotation) 0))
    (set! (.-shouldLock resize-input)      (if lock? 1 0))
    (set! (.-shouldCenter resize-input)    (if center? 1 0))
    (set! (.-shouldTransform resize-input) (some? transform))

    (common/set-rect!  (.-selRect resize-input) (:selrect shape))
    (common/set-point! (.-current resize-input) point)
    (common/set-point! (.-snap resize-input) point-snap)

    (when ^boolean transform
      (update-transforms! shape))

    (.resize instance)))

(defonce wasm:module
  (embed/read-as-base64 "app/wasm/resize.wasm"))

(defn- init-proxies!
  [asm]
  (set! resize-input (types/from asm "resizeInput" "ResizeInput"))
  (set! resize-output (types/from asm "resizeOutput" "ResizeOutput")))

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
