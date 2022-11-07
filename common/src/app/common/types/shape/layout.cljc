;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.layout
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

(s/def ::layout  #{:flex :grid})
(s/def ::layout-flex-dir #{:row :reverse-row :column :reverse-column})
(s/def ::layout-gap-type #{:simple :multiple})
(s/def ::layout-gap ::us/safe-number)
(s/def ::layout-align-items #{:start :end :center :strech})
(s/def ::layout-align-content #{:start :end :center :space-between :space-around :strech})
(s/def ::layout-justify-content #{:start :center :end :space-between :space-around})
(s/def ::layout-wrap-type #{:wrap :no-wrap})
(s/def ::layout-padding-type #{:simple :multiple})

(s/def ::p1 ::us/safe-number)
(s/def ::p2 ::us/safe-number)
(s/def ::p3 ::us/safe-number)
(s/def ::p4 ::us/safe-number)

(s/def ::layout-padding
  (s/keys :req-un [::p1]
          :opt-un [::p2 ::p3 ::p4]))

(s/def ::row-gap ::us/safe-number)
(s/def ::column-gap ::us/safe-number)
(s/def ::layout-type #{:flex :grid})

(s/def ::layout-gap
  (s/keys :req-un [::row-gap ::column-gap]))

(s/def ::layout-container-props
  (s/keys :opt-un [::layout
                   ::layout-flex-dir
                   ::layout-gap
                   ::layout-gap-type
                   ::layout-type
                   ::layout-wrap-type
                   ::layout-padding-type
                   ::layout-padding
                   ::layout-justify-content
                   ::layout-align-items
                   ::layout-align-content]))

(s/def ::m1 ::us/safe-number)
(s/def ::m2 ::us/safe-number)
(s/def ::m3 ::us/safe-number)
(s/def ::m4 ::us/safe-number)

(s/def ::layout-margin (s/keys :req-un [::m1]
                               :opt-un [::m2 ::m3 ::m4]))

(s/def ::layout-margin-type #{:simple :multiple})
(s/def ::layout-h-behavior #{:fill :fix :auto})
(s/def ::layout-v-behavior #{:fill :fix :auto})
(s/def ::layout-align-self #{:start :end :center :strech :baseline})
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
                   ::layout-min-w
                   ::layout-align-self]))
