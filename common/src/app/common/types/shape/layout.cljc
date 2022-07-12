;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.shape.layout
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

(s/def ::layout boolean?)
(s/def ::layout-dir #{:right :left :top :bottom})
(s/def ::layout-gap ::us/safe-number)
(s/def ::layout-type #{:packed :space-between :space-around})
(s/def ::layout-wrap-type #{:wrap :no-wrap})
(s/def ::layout-padding-type #{:simple :multiple})

(s/def ::p1 ::us/safe-number)
(s/def ::p2 ::us/safe-number)
(s/def ::p3 ::us/safe-number)
(s/def ::p4 ::us/safe-number)

(s/def ::layout-padding
  (s/keys :req-un [::p1]
          :opt-un [::p2 ::p3 ::p4]))

(s/def ::layout-h-orientation #{:left :center :right})
(s/def ::layout-v-orientation #{:top :center :bottom})

(s/def ::layout-container-props
  (s/keys :opt-un [::layout
                   ::layout-dir
                   ::layout-gap
                   ::layout-type
                   ::layout-wrap-type
                   ::layout-padding-type
                   ::layout-padding
                   ::layout-h-orientation
                   ::layout-v-orientation]))

(s/def ::layout-margin (s/keys :req-un [::m1]
                               :opt-un [::m2 ::m3 ::m4]))

(s/def ::layout-margin-type #{:simple :multiple})
(s/def ::layout-h-behavior #{:fill :fix :auto})
(s/def ::layout-v-behavior #{:fill :fix :auto})
(s/def ::layout-max-h ::us/safe-number)
(s/def ::layout-min-h ::us/safe-number)
(s/def ::layout-max-w ::us/safe-number)
(s/def ::layout-min-w ::us/safe-number)

(s/def ::layout-child-props
  (s/keys :opt-un [::layout-margin
                   ::layout-margin-type
                   ::layout-h-behavior
                   ::layout-v-behavior
                   ::layout-max-h
                   ::layout-min-h
                   ::layout-max-w
                   ::layout-min-w]))
