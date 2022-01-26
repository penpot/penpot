;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.export
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))


(s/def ::suffix string?)
(s/def ::scale ::us/safe-number)
(s/def ::type keyword?)

(s/def ::export
  (s/keys :req-un [::type
                   ::suffix
                   ::scale]))


