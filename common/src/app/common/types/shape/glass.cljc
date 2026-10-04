;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.shape.glass
  "Liquid glass backdrop effect. Parameters follow Figma's Glass effect.
  Rendered only by the WASM renderer."
  (:require
   [app.common.schema :as sm]))

(def schema:glass
  [:map {:title "Glass"}
   [:id ::sm/uuid]
   [:hidden :boolean]
   [:refraction [::sm/number {:min 0 :max 1}]]
   [:depth ::sm/non-negative-safe-number]
   [:dispersion [::sm/number {:min 0 :max 1}]]
   [:frost ::sm/non-negative-safe-number]
   [:splay [::sm/number {:min 0 :max 1}]]
   [:light-intensity [::sm/number {:min 0 :max 1}]]
   [:light-angle ::sm/safe-number]])
