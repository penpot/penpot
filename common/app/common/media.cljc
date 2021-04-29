;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.media
  (:require
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(def valid-font-types #{"font/ttf" "font/woff", "font/otf"})
(def valid-image-types #{"image/jpeg", "image/png", "image/webp", "image/gif", "image/svg+xml"})
(def str-image-types (str/join "," valid-image-types))
(def str-font-types (str/join "," valid-font-types))

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
    :jpg  "image/jpeg"
    :webp "image/webp"
    :gif "image/gif"
    :svg  "image/svg+xml"
    "application/octet-stream"))

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


(defn parse-font-weight
  [variant]
  (cond
    (re-seq #"(?i)(?:hairline|thin)" variant)           100
    (re-seq #"(?i)(?:extra light|ultra light)" variant) 200
    (re-seq #"(?i)(?:light)" variant)                   300
    (re-seq #"(?i)(?:normal|regular)" variant)          400
    (re-seq #"(?i)(?:medium)" variant)                  500
    (re-seq #"(?i)(?:semi bold|demi bold)" variant)     600
    (re-seq #"(?i)(?:bold)" variant)                    700
    (re-seq #"(?i)(?:extra bold|ultra bold)" variant)   800
    (re-seq #"(?i)(?:black|heavy)" variant)             900
    (re-seq #"(?i)(?:extra black|ultra black)" variant) 950
    :else                                               400))

(defn parse-font-style
  [variant]
  (if (re-seq #"(?i)(?:italic)" variant)
    "italic"
    "normal"))

(defn font-weight->name
  [weight]
  (case weight
    100 "Hairline"
    200 "Extra Light"
    300 "Light"
    400 "Regular"
    500 "Medium"
    600 "Semi Bold"
    700 "Bold"
    800 "Extra Bold"
    900 "Black"
    950 "Extra Black"))
