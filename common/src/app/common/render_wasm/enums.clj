;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.render-wasm.enums
  "Serializer enum table from `shared.js`")

(def ^:private serializer-exports
  [["raster-format" "RasterFormat"]
   ["blur-type" "RawBlurType"]
   ["blend-mode" "RawBlendMode"]
   ["bool-type" "RawBoolType"]
   ["font-style" "RawFontStyle"]
   ["flex-direction" "RawFlexDirection"]
   ["grid-direction" "RawGridDirection"]
   ["grow-type" "RawGrowType"]
   ["align-items" "RawAlignItems"]
   ["align-self" "RawAlignSelf"]
   ["align-content" "RawAlignContent"]
   ["justify-items" "RawJustifyItems"]
   ["justify-content" "RawJustifyContent"]
   ["justify-self" "RawJustifySelf"]
   ["wrap-type" "RawWrapType"]
   ["grid-track-type" "RawGridTrackType"]
   ["shadow-style" "RawShadowStyle"]
   ["guide-kind" "RawGuideKind"]
   ["stroke-style" "RawStrokeStyle"]
   ["stroke-cap" "RawStrokeCap"]
   ["shape-type" "RawShapeType"]
   ["constraint-h" "RawConstraintH"]
   ["constraint-v" "RawConstraintV"]
   ["sizing" "RawSizing"]
   ["vertical-align" "RawVerticalAlign"]
   ["fill-data" "RawFillData"]
   ["text-align" "RawTextAlign"]
   ["text-direction" "RawTextDirection"]
   ["text-decoration" "RawTextDecoration"]
   ["text-transform" "RawTextTransform"]
   ["multiple-state" "MultipleState"]
   ["transform-entry-kind" "RawTransformEntryKind"]
   ["segment-data" "RawSegmentData"]
   ["stroke-linecap" "RawStrokeLineCap"]
   ["stroke-linejoin" "RawStrokeLineJoin"]
   ["fill-rule" "RawFillRule"]])

(defmacro serializers
  [alias]
  (let [alias (name alias)]
    `(cljs.core/js-obj
      ~@(mapcat (fn [[key export]]
                  [key (symbol alias export)])
                serializer-exports))))
