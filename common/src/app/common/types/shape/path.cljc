;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.path
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::command keyword?)
(s/def ::params (s/nilable (s/map-of keyword? any?)))

(s/def ::command-item
  (s/keys :req-un [::command]
          :opt-un [::params]))

(s/def ::content
  (s/coll-of ::command-item :kind vector?))

