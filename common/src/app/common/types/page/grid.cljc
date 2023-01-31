;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page.grid
  (:require
   [app.common.spec :as us]
   [app.common.types.page.grid.color :as-alias grid-color]
   [clojure.spec.alpha :as s]))

;; --- Board grids


(s/def ::grid-color/color string?)
(s/def ::grid-color/opacity ::us/safe-number)

(s/def ::size (s/nilable ::us/safe-integer))
(s/def ::item-length (s/nilable ::us/safe-number))

(s/def ::color (s/keys :req-un [::grid-color/color
                                ::grid-color/opacity]))
(s/def ::type #{:stretch :left :center :right})
(s/def ::gutter (s/nilable ::us/safe-integer))
(s/def ::margin (s/nilable ::us/safe-integer))

(s/def ::square
  (s/keys :req-un [::size
                   ::color]))

(s/def ::column
  (s/keys :req-un [::color]
          :opt-un [::size
                   ::type
                   ::item-length
                   ::margin
                   ::gutter]))

(s/def ::row ::column)

(s/def ::saved-grids
  (s/keys :opt-un [::square
                   ::row
                   ::column]))

