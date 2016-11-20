;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.snappy
  "A lightweight abstraction layer for snappy compression library."
  (:require [buddy.core.codecs :as codecs])
  (:import org.xerial.snappy.Snappy
           org.xerial.snappy.SnappyFramedInputStream
           org.xerial.snappy.SnappyFramedOutputStream

           java.io.OutputStream
           java.io.InputStream))


(defn compress
  "Compress data unsing snappy compression algorithm."
  [data]
  (-> (codecs/to-bytes data)
      (Snappy/compress)))

(defn uncompress
  "Uncompress data using snappy compression algorithm."
  [data]
  (-> (codecs/to-bytes data)
      (Snappy/uncompress)))

(defn input-stream
  "Create a Snappy framed input stream."
  [^InputStream istream]
  (SnappyFramedInputStream. istream))

(defn output-stream
  "Create a Snappy framed output stream."
  ([ostream]
   (output-stream ostream nil))
  ([^OutputStream ostream {:keys [block-size] :or {block-size 65536}}]
   (SnappyFramedOutputStream. ostream (int block-size) 1.0)))
