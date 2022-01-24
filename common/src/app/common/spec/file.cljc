;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.file
  (:require
   [app.common.spec :as us]
   [app.common.spec.color :as color]
   [app.common.spec.page :as page]
   [app.common.spec.typography]
   [clojure.spec.alpha :as s]))

(s/def :internal.media-object/name string?)
(s/def :internal.media-object/width ::us/safe-integer)
(s/def :internal.media-object/height ::us/safe-integer)
(s/def :internal.media-object/mtype string?)

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   :internal.media-object/width
                   :internal.media-object/height
                   :internal.media-object/mtype]))

(s/def ::colors
  (s/map-of uuid? ::color/color))

(s/def ::recent-colors
  (s/coll-of ::color/recent-color :kind vector?))

(s/def ::typographies
  (s/map-of uuid? :app.common.spec.typography/typography))

(s/def ::pages
  (s/coll-of uuid? :kind vector?))

(s/def ::media
  (s/map-of uuid? ::media-object))

(s/def ::pages-index
  (s/map-of uuid? ::page/page))

(s/def ::components
  (s/map-of uuid? ::page/container))

(s/def ::data
  (s/keys :req-un [::pages-index
                   ::pages]
          :opt-un [::colors
                   ::recent-colors
                   ::typographies
                   ::media]))
