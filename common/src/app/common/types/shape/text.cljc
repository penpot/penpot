;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.text
  (:require
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.shape.text.position-data :as-alias position-data]
   [clojure.spec.alpha :as s]))

(s/def ::type #{"root" "paragraph-set" "paragraph"})
(s/def ::text string?)
(s/def ::key string?)
(s/def ::fill-color string?)
(s/def ::fill-opacity ::us/safe-number)
(s/def ::fill-color-gradient (s/nilable ::ctc/gradient))

(s/def ::content
  (s/nilable
   (s/or :text-container
         (s/keys :req-un [::type]
                 :opt-un [::key
                          ::children])
         :text-content
         (s/keys :req-un [::text]))))

(s/def ::children
  (s/coll-of ::content
             :kind vector?
             :min-count 1))

(s/def ::position-data
  (s/coll-of ::position-data-element
             :kind vector?
             :min-count 1))

(s/def ::position-data-element
  (s/keys :req-un [::position-data/x
                   ::position-data/y
                   ::position-data/width
                   ::position-data/height]
          :opt-un [::position-data/fill-color
                   ::position-data/fill-opacity
                   ::position-data/font-family
                   ::position-data/font-size
                   ::position-data/font-style
                   ::position-data/font-weight
                   ::position-data/rtl
                   ::position-data/text
                   ::position-data/text-decoration
                   ::position-data/text-transform]))

(s/def ::position-data/x ::us/safe-number)
(s/def ::position-data/y ::us/safe-number)
(s/def ::position-data/width ::us/safe-number)
(s/def ::position-data/height ::us/safe-number)

(s/def ::position-data/fill-color ::fill-color)
(s/def ::position-data/fill-opacity ::fill-opacity)
(s/def ::position-data/fill-color-gradient ::fill-color-gradient)

(s/def ::position-data/font-family string?)
(s/def ::position-data/font-size string?)
(s/def ::position-data/font-style string?)
(s/def ::position-data/font-weight string?)
(s/def ::position-data/rtl boolean?)
(s/def ::position-data/text string?)
(s/def ::position-data/text-decoration string?)
(s/def ::position-data/text-transform string?)

