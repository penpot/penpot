;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.export
  (:require
   [app.common.schema :as sm]))

(def types #{:png :jpeg :webp :svg :pdf})
(def renderers #{:default :rasterizer :render-wasm})

(def schema:export
  [:map {:title "ShapeExport"}
   [:type [::sm/one-of types]]
   [:scale ::sm/safe-number]
   [:suffix :string]
   [:quality {:optional true} [::sm/int {:min 0 :max 100}]]
   [:renderer {:optional true} [::sm/one-of renderers]]])
