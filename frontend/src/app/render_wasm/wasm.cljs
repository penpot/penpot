;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.wasm
  (:require ["./api/shared.js" :as shared]))

(defonce internal-frame-id nil)
(defonce internal-module #js {})
(defonce serializers
  #js {:blur-type (unchecked-get shared "RawBlurType")
       :blend-mode (unchecked-get shared "RawBlendMode")
       :bool-type (unchecked-get shared "RawBoolType")
       :font-style (unchecked-get shared "RawFontStyle")
       :flex-direction (unchecked-get shared "RawFlexDirection")
       :grid-direction (unchecked-get shared "RawGridDirection")
       :grow-type (unchecked-get shared "RawGrowType")
       :align-items (unchecked-get shared "RawAlignItems")
       :align-self (unchecked-get shared "RawAlignSelf")
       :align-content (unchecked-get shared "RawAlignContent")
       :justify-items (unchecked-get shared "RawJustifyItems")
       :justify-content (unchecked-get shared "RawJustifyContent")
       :justify-self (unchecked-get shared "RawJustifySelf")
       :wrap-type (unchecked-get shared "RawWrapType")
       :grid-track-type (unchecked-get shared "RawGridTrackType")
       :shadow-style (unchecked-get shared "RawShadowStyle")
       :stroke-style (unchecked-get shared "RawStrokeStyle")
       :stroke-cap (unchecked-get shared "RawStrokeCap")
       :shape-type (unchecked-get shared "RawShapeType")
       :constraint-h (unchecked-get shared "RawConstraintH")
       :constraint-v (unchecked-get shared "RawConstraintV")
       :sizing (unchecked-get shared "RawSizing")
       :vertical-align (unchecked-get shared "RawVerticalAlign")
       :fill-data (unchecked-get shared "RawFillData")
       :text-align (unchecked-get shared "RawTextAlign")
       :text-direction (unchecked-get shared "RawTextDirection")
       :text-decoration (unchecked-get shared "RawTextDecoration")
       :text-transform (unchecked-get shared "RawTextTransform")
       :segment-data (unchecked-get shared "RawSegmentData")
       :stroke-linecap (unchecked-get shared "RawStrokeLineCap")
       :stroke-linejoin (unchecked-get shared "RawStrokeLineJoin")
       :fill-rule (unchecked-get shared "RawFillRule")})

(defonce context-initialized? false)
