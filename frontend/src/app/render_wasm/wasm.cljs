;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.render-wasm.wasm
  (:require ["./api/shared.js" :as shared]))

(defonce internal-frame-id nil)
(defonce internal-module #js {})

;; Reference to the HTML canvas element.
(defonce canvas nil)

;; Reference to the Emscripten GL context wrapper.
(defonce gl-context-handle nil)

;; Reference to the actual WebGL Context returned
;; by the `.getContext` method of the canvas.
(defonce gl-context nil)

(defonce context-initialized? false)
(defonce context-lost? (atom false))

(defonce serializers
  #js {:blur-type shared/RawBlurType
       :blend-mode shared/RawBlendMode
       :bool-type shared/RawBoolType
       :font-style shared/RawFontStyle
       :flex-direction shared/RawFlexDirection
       :grid-direction shared/RawGridDirection
       :grow-type shared/RawGrowType
       :align-items shared/RawAlignItems
       :align-self shared/RawAlignSelf
       :align-content shared/RawAlignContent
       :justify-items shared/RawJustifyItems
       :justify-content shared/RawJustifyContent
       :justify-self shared/RawJustifySelf
       :wrap-type shared/RawWrapType
       :grid-track-type shared/RawGridTrackType
       :shadow-style shared/RawShadowStyle
       :stroke-style shared/RawStrokeStyle
       :stroke-cap shared/RawStrokeCap
       :shape-type shared/RawShapeType
       :constraint-h shared/RawConstraintH
       :constraint-v shared/RawConstraintV
       :sizing shared/RawSizing
       :vertical-align shared/RawVerticalAlign
       :fill-data shared/RawFillData
       :text-align shared/RawTextAlign
       :text-direction shared/RawTextDirection
       :text-decoration shared/RawTextDecoration
       :text-transform shared/RawTextTransform
       :segment-data shared/RawSegmentData
       :stroke-linecap shared/RawStrokeLineCap
       :stroke-linejoin shared/RawStrokeLineJoin
       :fill-rule shared/RawFillRule})
