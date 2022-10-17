;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page.flow
  (:require
   [clojure.spec.alpha :as s]))

;; --- Interaction Flows

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::starting-frame uuid?)

(s/def ::flow
  (s/keys :req-un [::id
                   ::name
                   ::starting-frame]))

(s/def ::flows
  (s/coll-of ::flow :kind vector?))

