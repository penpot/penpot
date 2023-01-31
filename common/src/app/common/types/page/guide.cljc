;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page.guide
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;; --- Page guides

(s/def ::id uuid?)
(s/def ::axis #{:x :y})
(s/def ::position ::us/safe-number)
(s/def ::frame-id (s/nilable uuid?))

(s/def ::guide
  (s/keys :req-un [::id
                   ::axis
                   ::position]
          :opt-un [::frame-id]))

(s/def ::guides
  (s/map-of uuid? ::guide))

