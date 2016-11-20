;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.blob
  "A generic blob storage encoding. Mainly used for
  page data, page options and txlog payload storage."
  (:require [uxbox.util.snappy :as snappy]))

(defn encode
  "Encode data into compressed blob."
  [data]
  (snappy/compress data))

(defn decode
  "Decode blob into string."
  [^bytes data]
  (snappy/uncompress data))

