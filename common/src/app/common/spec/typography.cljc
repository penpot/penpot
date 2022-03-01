;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.typography
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::path (s/nilable string?))
(s/def ::font-id string?)
(s/def ::font-family string?)
(s/def ::font-variant-id string?)
(s/def ::font-size string?)
(s/def ::font-weight string?)
(s/def ::font-style string?)
(s/def ::line-height string?)
(s/def ::letter-spacing string?)
(s/def ::text-transform string?)

(s/def ::typography
  (s/keys :req-un [::id
                   ::name
                   ::font-id
                   ::font-family
                   ::font-variant-id
                   ::font-size
                   ::font-weight
                   ::font-style
                   ::line-height
                   ::letter-spacing
                   ::text-transform]
          :opt-un [::path]))


