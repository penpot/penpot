;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.media
  (:require
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(def valid-media-types
  #{"image/jpeg", "image/png", "image/webp", "image/gif", "image/svg+xml"})

(def str-media-types (str/join "," valid-media-types))

(defn format->extension
  [format]
  (case format
    :png  ".png"
    :jpeg ".jpg"
    :webp ".webp"
    :gif ".gif"
    :svg  ".svg"))

(defn format->mtype
  [format]
  (case format
    :png  "image/png"
    :jpeg "image/jpeg"
    :webp "image/webp"
    :gif "image/gif"
    :svg  "image/svg+xml"))

(defn mtype->format
  [mtype]
  (case mtype
    "image/png"     :png
    "image/jpeg"    :jpeg
    "image/webp"    :webp
    "image/gif"     :gif
    "image/svg+xml" :svg
    nil))

(def max-file-size (* 5 1024 1024))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::mtype string?)
(s/def ::uri string?)

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::mtype
                   ::created-at
                   ::modified-at
                   ::uri]))

