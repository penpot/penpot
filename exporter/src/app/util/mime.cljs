;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.mime
  "Mimetype and file extension helpers."
  (:refer-clojure :exclude [get])
  (:require
   [cljs.core :as c]))

(defn get-extension
  [type]
  (case type
    :png  ".png"
    :jpeg ".jpg"
    :webp ".webp"
    :svg  ".svg"
    :pdf  ".pdf"
    :zip  ".zip"))

(defn get
  [type]
  (case type
    :zip  "application/zip"
    :pdf  "application/pdf"
    :svg  "image/svg+xml"
    :jpeg "image/jpeg"
    :png  "image/png"
    :webp "image/webp"))


