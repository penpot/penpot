;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.shadow
  (:require
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.shape.shadow.color :as-alias shadow-color]
   [clojure.spec.alpha :as s]))

;;; SHADOW EFFECT

(s/def ::id (s/nilable uuid?))
(s/def ::style #{:drop-shadow :inner-shadow})
(s/def ::offset-x ::us/safe-number)
(s/def ::offset-y ::us/safe-number)
(s/def ::blur ::us/safe-number)
(s/def ::spread ::us/safe-number)
(s/def ::hidden boolean?)

(s/def ::color string?)
(s/def ::opacity ::us/safe-number)
(s/def ::gradient (s/nilable ::ctc/gradient))
(s/def ::file-id (s/nilable uuid?))
(s/def ::ref-id (s/nilable uuid?))

(s/def ::shadow-color/color
  (s/keys :opt-un [::color
                   ::opacity
                   ::gradient
                   ::file-id
                   ::id]))

(s/def ::shadow-props
  (s/keys :req-un [::id
                   ::style
                   ::shadow-color/color
                   ::offset-x
                   ::offset-y
                   ::blur
                   ::spread
                   ::hidden]))

(s/def ::shadow
  (s/coll-of ::shadow-props :kind vector?))

