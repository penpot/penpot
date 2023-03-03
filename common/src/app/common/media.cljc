;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.media
  (:require
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; We have added ".ttf" as string to solve a problem with chrome input selector
(def valid-font-types #{"font/ttf", ".ttf", "font/woff", "application/font-woff", "font/otf"})
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

(defn mtype->extension [mtype]
  ;; https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types
  (case mtype
    "image/apng"         ".apng"
    "image/avif"         ".avif"
    "image/gif"          ".gif"
    "image/jpeg"         ".jpg"
    "image/png"          ".png"
    "image/svg+xml"      ".svg"
    "image/webp"         ".webp"
    "application/zip"    ".zip"
    "application/penpot" ".penpot"
    "application/pdf"    ".pdf"
    nil))

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
    (re-seq #"(?i)(?:hairline|thin)" variant)               100
    (re-seq #"(?i)(?:extra\s*light|ultra\s*light)" variant) 200
    (re-seq #"(?i)(?:light)" variant)                       300
    (re-seq #"(?i)(?:normal|regular)" variant)              400
    (re-seq #"(?i)(?:medium)" variant)                      500
    (re-seq #"(?i)(?:semi\s*bold|demi\s*bold)" variant)     600
    (re-seq #"(?i)(?:extra\s*bold|ultra\s*bold)" variant)   800
    (re-seq #"(?i)(?:bold)" variant)                        700
    (re-seq #"(?i)(?:extra\s*black|ultra\s*black)" variant) 950
    (re-seq #"(?i)(?:black|heavy|solid)" variant)           900
    :else                                                   400))

(defn parse-font-style
  [variant]
  (if (re-seq #"(?i)(?:italic)" variant)
    "italic"
    "normal"))

(defn font-weight->name
  [weight]
  (case (long weight)
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
