;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.blur
  (:require
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SPEC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::id uuid?)
(s/def ::type #{:layer-blur})
(s/def ::value ::us/safe-number)
(s/def ::hidden boolean?)

(s/def ::blur
  (s/keys :req-un [::id ::type ::value ::hidden]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sm/register!
 ^{::sm/type ::blur}
 [:map {:title "Blur"}
  [:id ::sm/uuid]
  [:type [:= :layer-blur]]
  [:value ::sm/safe-number]
  [:hidden :boolean]])
