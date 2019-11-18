;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.snappy
  "A lightweight abstraction layer for snappy compression library."
  (:import
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.InputStream
   java.io.OutputStream
   org.xerial.snappy.Snappy
   org.xerial.snappy.SnappyFramedInputStream
   org.xerial.snappy.SnappyFramedOutputStream))

(defn compress
  "Compress data unsing snappy compression algorithm."
  [^bytes data]
  (Snappy/compress data))

(defn uncompress
  "Uncompress data using snappy compression algorithm."
  [^bytes data]
  (Snappy/uncompress data))

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
