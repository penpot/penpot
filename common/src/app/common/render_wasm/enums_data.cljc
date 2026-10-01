;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.render-wasm.enums-data
  "Pure enum discriminant tables for WASM batch encode (JVM + JS).

  Values match `render-wasm` `Raw*` enums consumed by `_set_shapes_batch`.
  Kept separate from `shared.js` so the backend can encode without WASM."
  (:require
   [app.common.data :as d]))

(def shape-type
  {:frame 0 :group 1 :bool 2 :rect 3 :path 4 :text 5 :circle 6 :svg-raw 7})

(def blend-mode
  {:normal 3 :screen 14 :overlay 15 :darken 16 :lighten 17
   :color-dodge 18 :color-burn 19 :hard-light 20 :soft-light 21
   :difference 22 :exclusion 23 :multiply 24 :hue 25 :saturation 26
   :color 27 :luminosity 28})

;; Matches Rust RawConstraintH/V (None = 0).
(def constraint-h
  {:none 0 :left 1 :right 2 :leftright 3 :center 4 :scale 5})

(def constraint-v
  {:none 0 :top 1 :bottom 2 :topbottom 3 :center 4 :scale 5})

(def shadow-style
  {:drop-shadow 0 :inner-shadow 1})

(def bool-type
  {:union 0 :difference 1 :intersection 2 :exclude 3})

(def grow-type
  {:fixed 0 :auto-width 1 :auto-height 2})

(def sizing
  {:fill 0 :fix 1 :auto 2})

(def flex-direction
  {:row 0 :row-reverse 1 :column 2 :column-reverse 3})

(def wrap-type
  {:wrap 0 :nowrap 1})

(def align-items
  {:start 0 :end 1 :center 2 :stretch 3})

(def align-content
  {:start 0 :end 1 :center 2
   :space-between 3 :space-around 4 :space-evenly 5 :stretch 6})

(def justify-items
  {:start 0 :end 1 :center 2 :stretch 3})

(def justify-content
  {:start 0 :end 1 :center 2
   :space-between 3 :space-around 4 :space-evenly 5 :stretch 6})

(def align-self
  {:none 0 :auto 1 :start 2 :end 3 :center 4 :stretch 5})

(def stroke-style
  {:solid 0 :dotted 1 :dashed 2 :mixed 3})

(def stroke-cap
  {:none 0 :line-arrow 1 :triangle-arrow 2 :square-marker 3
   :circle-marker 4 :diamond-marker 5 :round 6 :square 7})

(defn- lookup
  [table value default-key]
  (let [k (cond
            (keyword? value) value
            (string? value) (keyword value)
            :else value)
        default (get table default-key)]
    (d/nilv (get table k) default)))

(defn translate-shape-type [type] (lookup shape-type type :rect))
(defn translate-blend-mode [mode] (lookup blend-mode mode :normal))
(defn translate-constraint-h [type] (lookup constraint-h type :none))
(defn translate-constraint-v [type] (lookup constraint-v type :none))
(defn translate-shadow-style [style] (lookup shadow-style style :drop-shadow))
(defn translate-bool-type [type] (lookup bool-type type :union))
(defn translate-grow-type [type] (lookup grow-type type :fixed))
(defn translate-layout-sizing [type] (lookup sizing type :fix))
(defn translate-layout-flex-dir [type] (lookup flex-direction type :row))
(defn translate-layout-wrap-type [type] (lookup wrap-type type :nowrap))
(defn translate-layout-align-items [type] (lookup align-items type :start))
(defn translate-layout-align-content [type] (lookup align-content type :stretch))
(defn translate-layout-justify-items [type] (lookup justify-items type :start))
(defn translate-layout-justify-content [type] (lookup justify-content type :stretch))
(defn translate-align-self [type] (lookup align-self type :none))
(defn translate-stroke-style [type] (lookup stroke-style type :solid))
(defn translate-stroke-cap [type] (lookup stroke-cap type :none))
