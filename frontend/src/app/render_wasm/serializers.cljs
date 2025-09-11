;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

 (ns app.render-wasm.serializers
   (:require
    [app.common.uuid :as uuid]
    [app.render-wasm.wasm :as wasm]
    [cuerdas.core :as str]))

(defn u8
  [value]
  (let [u8-arr (js/Uint8Array. 1)]
    (aset u8-arr 0 value)
    u8-arr))

(defn f32->u8
  [value]
  (let [f32-arr (js/Float32Array. 1)]
    (aset f32-arr 0 value)
    (js/Uint8Array. (.-buffer f32-arr))))

(defn i32->u8
  [value]
  (let [i32-arr (js/Int32Array. 1)]
    (aset i32-arr 0 value)
    (js/Uint8Array. (.-buffer i32-arr))))

(defn bool->u8
  [value]
  (let [result (js/Uint8Array. 1)]
    (aset result 0 (if value 1 0))
    result))

(defn uuid->u8
  [id]
  (let [buffer (uuid/get-u32 id)
        u32-arr (js/Uint32Array. 4)]
    (aset u32-arr 0 (aget buffer 0))
    (aset u32-arr 1 (aget buffer 1))
    (aset u32-arr 2 (aget buffer 2))
    (aset u32-arr 3 (aget buffer 3))
    (js/Uint8Array. (.-buffer u32-arr))))

(defn serialize-uuid
  [id]
  (try
    (if (nil? id)
      (do
        [uuid/zero])
      (let [as-uuid (uuid/uuid id)]
        (uuid/get-u32 as-uuid)))
    (catch :default _e
      [uuid/zero])))

(defn translate-shape-type
  [type]
  (let [values (:shape-type wasm/serializers)
        default (:rect values)]
    (get values type default)))

(defn translate-stroke-style
  [stroke-style]
  (let [values (:stroke-style wasm/serializers)
        default (:solid values)]
    (get values stroke-style default)))

(defn translate-stroke-cap
  [stroke-cap]
  (let [values (:stroke-cap wasm/serializers)
        default (:none values)]
    (get values stroke-cap default)))

(defn serialize-path-attrs
  [svg-attrs]
  (reduce
   (fn [acc [key value]]
     (str/concat
      acc
      (str/kebab key) "\0"
      value "\0")) "" svg-attrs))

(defn translate-blend-mode
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

(defn translate-constraint-h
  [type]
  (let [values (:constraint-h wasm/serializers)
        default 5] ;; TODO: fix code in rust so we have a proper None variant
    (get values type default)))

(defn translate-constraint-v
  [type]
  (let [values (:constraint-v wasm/serializers)
        default 5] ;; TODO: fix code in rust so we have a proper None variant
    (get values type default)))

(defn translate-bool-type
  [bool-type]
  (let [values (:bool-type wasm/serializers)
        default (:union values)]
    (get values bool-type default)))


(defn translate-blur-type
  [blur-type]
  (let [values (:blur-type wasm/serializers)
        default (:none values)]
    (get values blur-type default)))

(defn translate-layout-flex-dir
  [flex-dir]
  (let [values (:flex-direction wasm/serializers)]
    (get values flex-dir)))


(defn translate-layout-grid-dir
  [grid-dir]
  (let [values (:grid-direction wasm/serializers)]
    (get values grid-dir)))

(defn translate-layout-align-items
  [align-items]
  (let [values (:align-items wasm/serializers)
        default (:start values)]
    (get values align-items default)))

(defn translate-layout-align-content
  [align-content]
  (let [values (:align-content wasm/serializers)
        default (:stretch values)]
    (get values align-content default)))

(defn translate-layout-justify-items
  [justify-items]
  (let [values (:justify-items wasm/serializers)
        default (:start values)]
    (get values justify-items default)))

(defn translate-layout-justify-content
  [justify-content]
  (let [values (:justify-content wasm/serializers)
        default (:stretch values)]
    (get values justify-content default)))

(defn translate-layout-wrap-type
  [wrap-type]
  (let [values (:wrap-type wasm/serializers)
        default (:nowrap values)]
    (get values wrap-type default)))


(defn translate-grid-track-type
  [type]
  (let [values (:grid-track-type wasm/serializers)]
    (get values type)))

(defn translate-layout-sizing
  [value]
  (let [values (:sizing wasm/serializers)
        default (:fix values)]
    (get values value default)))

(defn translate-align-self
  [value]
  (let [values (:align-self wasm/serializers)]
    (get values value)))

(defn translate-justify-self
  [value]
  (let [values (:justify-self wasm/serializers)]
    (get values value)))

(defn translate-shadow-style
  [style]
  (let [values (:shadow-style wasm/serializers)
        default (:drop-shadow values)]
    (get values style default)))

;; TODO: Find/Create a Rust enum for this
(defn translate-structure-modifier-type
  [type]
  (case type
    :remove-children 1
    :add-children    2
    :scale-content 3))

(defn translate-grow-type
  [grow-type]
  (let [values (:grow-type wasm/serializers)
        default (:fixed values)]
    (get values grow-type default)))

(defn translate-vertical-align
  [vertical-align]
  (let [values (:vertical-align wasm/serializers)
        default (:top values)]
    (get values vertical-align default)))

;; TODO: Find/Create a Rust enum for this
(defn translate-text-align
  [text-align]
  (case text-align
    "left" 0
    "center" 1
    "right" 2
    "justify" 3
    0))

;; TODO: Find/Create a Rust enum for this
(defn translate-text-transform
  [text-transform]
  (case text-transform
    "none" 0
    "uppercase" 1
    "lowercase" 2
    "capitalize" 3
    nil 0
    0))

;; TODO: Find/Create a Rust enum for this
(defn translate-text-decoration
  [text-decoration]
  (case text-decoration
    "none" 0
    "underline" 1
    "line-through" 2
    "overline" 3
    nil 0
    0))

;; TODO: Find/Create a Rust enum for this
(defn translate-text-direction
  [text-direction]
  (case text-direction
    "ltr" 0
    "rtl" 1
    nil 0
    0))

(defn translate-font-style
  [font-style]
  (let [values (:font-style wasm/serializers)
        default (:normal values)]
    (case font-style
    ;; NOTE: normal == regular!
    ;; is it OK to keep those two values in our cljs model?
      "normal" (:normal values)
      "regular" (:normal values)
      "italic" (:italic values)
      default)))
