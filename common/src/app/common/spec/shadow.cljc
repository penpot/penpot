;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.shadow
  (:require
   [app.common.spec :as us]
   [app.common.spec.color :as color]
   [clojure.spec.alpha :as s]))


;;; SHADOW EFFECT

(s/def ::id uuid?)
(s/def ::style #{:drop-shadow :inner-shadow})
(s/def ::color ::color/color)
(s/def ::offset-x ::us/safe-number)
(s/def ::offset-y ::us/safe-number)
(s/def ::blur ::us/safe-number)
(s/def ::spread ::us/safe-number)
(s/def ::hidden boolean?)

(s/def ::shadow-props
  (s/keys :req-un [:internal.shadow/id
                   :internal.shadow/style
                   :internal.shadow/color
                   :internal.shadow/offset-x
                   :internal.shadow/offset-y
                   :internal.shadow/blur
                   :internal.shadow/spread
                   :internal.shadow/hidden]))

(s/def ::shadow
  (s/coll-of ::shadow-props :kind vector?))

