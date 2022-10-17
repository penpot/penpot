;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.file.media-object
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::width ::us/safe-integer)
(s/def ::height ::us/safe-integer)
(s/def ::mtype string?)

;; NOTE: This is marked as nilable for backward compatibility, but
;; right now is just exists or not exists. We can thin in a gradual
;; migration and then mark it as not nilable.
(s/def ::path (s/nilable string?))

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::mtype]
          :opt-un [::path]))

