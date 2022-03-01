;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.color
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;; TODO: waiting clojure 1.11 to rename this all :internal.stuff to a
;; more consistent name.

;; TODO: maybe define ::color-hex-string with proper hex color spec?

;; --- GRADIENTS

(s/def ::id uuid?)

(s/def :internal.gradient.stop/color string?)
(s/def :internal.gradient.stop/opacity ::us/safe-number)
(s/def :internal.gradient.stop/offset ::us/safe-number)

(s/def :internal.gradient/type #{:linear :radial})
(s/def :internal.gradient/start-x ::us/safe-number)
(s/def :internal.gradient/start-y ::us/safe-number)
(s/def :internal.gradient/end-x ::us/safe-number)
(s/def :internal.gradient/end-y ::us/safe-number)
(s/def :internal.gradient/width ::us/safe-number)

(s/def :internal.gradient/stop
  (s/keys :req-un [:internal.gradient.stop/color
                   :internal.gradient.stop/opacity
                   :internal.gradient.stop/offset]))

(s/def :internal.gradient/stops
  (s/coll-of :internal.gradient/stop :kind vector?))

(s/def ::gradient
  (s/keys :req-un [:internal.gradient/type
                   :internal.gradient/start-x
                   :internal.gradient/start-y
                   :internal.gradient/end-x
                   :internal.gradient/end-y
                   :internal.gradient/width
                   :internal.gradient/stops]))

;;; --- COLORS

(s/def :internal.color/name string?)
(s/def :internal.color/path (s/nilable string?))
(s/def :internal.color/value (s/nilable string?))
(s/def :internal.color/color (s/nilable string?))
(s/def :internal.color/opacity (s/nilable ::us/safe-number))
(s/def :internal.color/gradient (s/nilable ::gradient))

(s/def ::color
  (s/keys :opt-un [::id
                   :internal.color/name
                   :internal.color/path
                   :internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))

(s/def ::recent-color
  (s/keys :opt-un [:internal.color/value
                   :internal.color/color
                   :internal.color/opacity
                   :internal.color/gradient]))




