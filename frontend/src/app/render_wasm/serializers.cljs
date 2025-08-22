;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.serializers
  (:require
   [app.common.uuid :as uuid]
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
  (case type
    :frame   0
    :group   1
    :bool    2
    :rect    3
    :path    4
    :text    5
    :circle  6
    :svg-raw 7
    :image   8))

(defn translate-stroke-style
  [stroke-style]
  (case stroke-style
    :dotted 1
    :dashed 2
    :mixed  3
    0))

(defn translate-stroke-cap
  [stroke-cap]
  (case stroke-cap
    :line-arrow 1
    :triangle-arrow 2
    :square-marker 3
    :circle-marker 4
    :diamond-marker 5
    :round 6
    :square 7
    0))


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
  (case type
    :left      0
    :right     1
    :leftright 2
    :center    3
    :scale     4))

(defn translate-constraint-v
  [type]
  (case type
    :top       0
    :bottom    1
    :topbottom 2
    :center    3
    :scale     4))

(defn translate-bool-type
  [bool-type]
  (case bool-type
    :union 0
    :difference 1
    :intersection 2
    :exclude 3
    0))

(defn translate-blur-type
  [blur-type]
  (case blur-type
    :layer-blur 1
    0))

(defn translate-layout-flex-dir
  [flex-dir]
  (case flex-dir
    :row            0
    :row-reverse    1
    :column         2
    :column-reverse 3))

(defn translate-layout-grid-dir
  [flex-dir]
  (case flex-dir
    :row    0
    :column 1))

(defn translate-layout-align-items
  [align-items]
  (case align-items
    :start   0
    :end     1
    :center  2
    :stretch 3
    0))

(defn translate-layout-align-content
  [align-content]
  (case align-content
    :start         0
    :end           1
    :center        2
    :space-between 3
    :space-around  4
    :space-evenly  5
    :stretch       6
    6))

(defn translate-layout-justify-items
  [justify-items]
  (case justify-items
    :start   0
    :end     1
    :center  2
    :stretch 3
    0))

(defn translate-layout-justify-content
  [justify-content]
  (case justify-content
    :start         0
    :end           1
    :center        2
    :space-between 3
    :space-around  4
    :space-evenly  5
    :stretch       6
    6))

(defn translate-layout-wrap-type
  [wrap-type]
  (case wrap-type
    :wrap   0
    :nowrap 1
    1))

(defn translate-grid-track-type
  [type]
  (case type
    :percent 0
    :flex 1
    :auto 2
    :fixed 3))

(defn translate-layout-sizing
  [value]
  (case value
    :fill 0
    :fix  1
    :auto 2
    1))

(defn translate-align-self
  [value]
  (when value
    (case value
      :auto    0
      :start   1
      :end     2
      :center  3
      :stretch 4)))

(defn translate-justify-self
  [value]
  (when value
    (case value
      :auto    0
      :start   1
      :end     2
      :center  3
      :stretch 4)))

(defn translate-shadow-style
  [style]
  (case style
    :drop-shadow 0
    :inner-shadow 1
    0))

(defn translate-structure-modifier-type
  [type]
  (case type
    :remove-children 1
    :add-children    2
    :scale-content 3))

(defn translate-grow-type
  [grow-type]
  (case grow-type
    :auto-width 1
    :auto-height 2
    0))

(defn translate-vertical-align
  [vertical-align]
  (case vertical-align
    "top" 0
    "center" 1
    "bottom" 2
    0))

(defn translate-text-align
  [text-align]
  (case text-align
    "left" 0
    "center" 1
    "right" 2
    "justify" 3
    0))

(defn translate-text-transform
  [text-transform]
  (case text-transform
    "none" 0
    "uppercase" 1
    "lowercase" 2
    "capitalize" 3
    nil 0
    0))

(defn translate-text-decoration
  [text-decoration]
  (case text-decoration
    "none" 0
    "underline" 1
    "line-through" 2
    "overline" 3
    nil 0
    0))

(defn translate-text-direction
  [text-direction]
  (case text-direction
    "ltr" 0
    "rtl" 1
    nil 0
    0))

(defn translate-font-style
  [font-style]
  (case font-style
    "normal" 0
    "regular" 0
    "italic" 1
    0))
