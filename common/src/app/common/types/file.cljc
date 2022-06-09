;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.file
  (:require
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.page :as ctp]
   [clojure.spec.alpha :as s]))

(s/def :internal.media-object/name string?)
(s/def :internal.media-object/width ::us/safe-integer)
(s/def :internal.media-object/height ::us/safe-integer)
(s/def :internal.media-object/mtype string?)

;; NOTE: This is marked as nilable for backward compatibility, but
;; right now is just exists or not exists. We can thin in a gradual
;; migration and then mark it as not nilable.
(s/def :internal.media-object/path (s/nilable string?))

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   :internal.media-object/width
                   :internal.media-object/height
                   :internal.media-object/mtype]
          :opt-un [:internal.media-object/path]))

(s/def ::colors
  (s/map-of uuid? ::ctc/color))

(s/def ::recent-colors
  (s/coll-of ::ctc/recent-color :kind vector?))

(s/def ::typographies
  (s/map-of uuid? :ctst/typography))

(s/def ::pages
  (s/coll-of uuid? :kind vector?))

(s/def ::media
  (s/map-of uuid? ::media-object))

(s/def ::pages-index
  (s/map-of uuid? ::ctp/page))

(s/def ::components
  (s/map-of uuid? ::ctp/container))

(s/def ::data
  (s/keys :req-un [::pages-index
                   ::pages]
          :opt-un [::colors
                   ::recent-colors
                   ::typographies
                   ::media]))
