;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

 (ns app.render-wasm.serializers
   (:require
    [app.common.data :as d]
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
  (let [values (unchecked-get wasm/serializers "shape-type")
        default (unchecked-get values "rect")]
    (d/nilv (unchecked-get values (d/name type)) default)))

(defn translate-stroke-style
  [stroke-style]
  (let [values (unchecked-get wasm/serializers "stroke-style")
        default (unchecked-get values "solid")]
    (d/nilv (unchecked-get values (d/name stroke-style)) default)))

(defn translate-stroke-cap
  [stroke-cap]
  (let [values (unchecked-get wasm/serializers "stroke-cap")
        default (unchecked-get values "none")]
    (d/nilv (unchecked-get values (d/name stroke-cap)) default)))

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
  (let [values (unchecked-get wasm/serializers "constraint-h")
        default 5] ;; TODO: fix code in rust so we have a proper None variant
    (d/nilv (unchecked-get values (d/name type)) default)))

(defn translate-constraint-v
  [type]
  (let [values (unchecked-get wasm/serializers "constraint-v")
        default 5] ;; TODO: fix code in rust so we have a proper None variant
    (d/nilv (unchecked-get values (d/name type)) default)))

(defn translate-bool-type
  [bool-type]
  (let [values (unchecked-get wasm/serializers "bool-type")
        default (unchecked-get values "union")]
    (d/nilv (unchecked-get values (d/name bool-type)) default)))


(defn translate-blur-type
  [blur-type]
  (let [values (unchecked-get wasm/serializers "blur-type")
        default (unchecked-get values "layer-blur")]
    (d/nilv (unchecked-get values (d/name blur-type)) default)))

(defn translate-layout-flex-dir
  [flex-dir]
  (let [values (unchecked-get wasm/serializers "flex-direction")]
    (unchecked-get values (d/name flex-dir))))


(defn translate-layout-grid-dir
  [grid-dir]
  (let [values (unchecked-get wasm/serializers "grid-direction")]
    (unchecked-get values (d/name grid-dir))))

(defn translate-layout-align-items
  [align-items]
  (let [values (unchecked-get wasm/serializers "align-items")
        default (unchecked-get values "start")]
    (d/nilv (unchecked-get values (d/name align-items)) default)))

(defn translate-layout-align-content
  [align-content]
  (let [values (unchecked-get wasm/serializers "align-content")
        default (unchecked-get values "stretch")]
    (d/nilv (unchecked-get values (d/name align-content)) default)))

(defn translate-layout-justify-items
  [justify-items]
  (let [values (unchecked-get wasm/serializers "justify-items")
        default (unchecked-get values "start")]
    (d/nilv (unchecked-get values (d/name justify-items)) default)))

(defn translate-layout-justify-content
  [justify-content]
  (let [values (unchecked-get wasm/serializers "justify-content")
        default (unchecked-get values "stretch")]
    (d/nilv (unchecked-get values (d/name justify-content)) default)))

(defn translate-layout-wrap-type
  [wrap-type]
  (let [values (unchecked-get wasm/serializers "wrap-type")
        default (unchecked-get values "nowrap")]
    (d/nilv (unchecked-get values (d/name wrap-type)) default)))


(defn translate-grid-track-type
  [type]
  (let [values (unchecked-get wasm/serializers "grid-track-type")]
    (unchecked-get values (d/name type))))

(defn translate-layout-sizing
  [sizing]
  (let [values (unchecked-get wasm/serializers "sizing")
        default (unchecked-get values "fix")]
    (d/nilv (unchecked-get values (d/name sizing)) default)))

(defn translate-align-self
  [align-self]
  (let [values (unchecked-get wasm/serializers "align-self")
        default (unchecked-get values "none")]
    (d/nilv (unchecked-get values (d/name align-self)) default)))

(defn translate-justify-self
  [justify-self]
  (let [values (unchecked-get wasm/serializers "justify-self")
        default (unchecked-get values "none")]
    (d/nilv (unchecked-get values (d/name justify-self)) default)))

(defn translate-shadow-style
  [style]
  (let [values (unchecked-get wasm/serializers "shadow-style")
        default (unchecked-get values "drop-shadow")]
    (d/nilv (unchecked-get values (d/name style)) default)))

;; TODO: Find/Create a Rust enum for this
(defn translate-structure-modifier-type
  [type]
  (case type
    :remove-children 1
    :add-children    2
    :scale-content 3))

(defn translate-grow-type
  [grow-type]
  (let [values (unchecked-get wasm/serializers "grow-type")
        default (unchecked-get values "fixed")]
    (d/nilv (unchecked-get values (d/name grow-type)) default)))

(defn translate-vertical-align
  [vertical-align]
  (let [values (unchecked-get wasm/serializers "vertical-align")
        default (unchecked-get values "top")]
    (d/nilv (unchecked-get values (d/name vertical-align)) default)))

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
  (let [values (unchecked-get wasm/serializers "font-style")
        default (unchecked-get values "normal")]
    (case font-style
    ;; NOTE: normal == regular!
    ;; is it OK to keep those two values in our cljs model?
      "normal" (unchecked-get values "normal")
      "regular" (unchecked-get values "normal")
      "italic" (unchecked-get values "italic")
      default)))
