;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.bytes
  "Bytes & Byte Streams helpers"
  (:require
   [clojure.java.io :as io]
   [datoteka.fs :as fs]
   [yetti.adapter :as yt])
  (:import
   com.github.luben.zstd.ZstdInputStream
   com.github.luben.zstd.ZstdOutputStream
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   java.io.OutputStream
   java.io.InputStream
   java.lang.AutoCloseable
   org.apache.commons.io.IOUtils
   org.apache.commons.io.input.BoundedInputStream))

;; TODO: migrate to datoteka.io

(set! *warn-on-reflection* true)

(def ^:const default-buffer-size
  (:xnio/buffer-size yt/defaults))

(defn input-stream?
  [s]
  (instance? InputStream s))

(defn output-stream?
  [s]
  (instance? OutputStream s))

(defn data-input-stream?
  [s]
  (instance? DataInputStream s))

(defn data-output-stream?
  [s]
  (instance? DataOutputStream s))

(defn copy!
  [src dst & {:keys [offset size buffer-size]
              :or {offset 0 buffer-size default-buffer-size}}]
  (let [^bytes buff (byte-array buffer-size)]
    (if size
      (IOUtils/copyLarge ^InputStream src ^OutputStream dst (long offset) (long size) buff)
      (IOUtils/copyLarge ^InputStream src ^OutputStream dst buff))))

(defn write-to-file!
  [src dst & {:keys [size]}]
  (with-open [^OutputStream output (io/output-stream dst)]
    (cond
      (bytes? src)
      (if size
        (with-open [^InputStream input (ByteArrayInputStream. ^bytes src)]
          (with-open [^InputStream input (BoundedInputStream. input (or size (alength ^bytes src)))]
            (copy! input output :size size)))

        (do
          (IOUtils/writeChunked ^bytes src output)
          (.flush ^OutputStream output)
          (alength ^bytes src)))

      (instance? InputStream src)
      (copy! src output :size size)

      :else
      (throw (IllegalArgumentException. "invalid arguments")))))

(defn read-as-bytes
  "Read input stream as byte array."
  [input & {:keys [size]}]
  (cond
    (instance? InputStream input)
    (with-open [output (ByteArrayOutputStream. (or size (.available ^InputStream input)))]
      (copy! input output :size size)
      (.toByteArray output))

    (fs/path? input)
    (with-open [input  (io/input-stream input)
                output (ByteArrayOutputStream. (or size (.available input)))]
      (copy! input output :size size)
      (.toByteArray output))

    :else
    (throw (IllegalArgumentException. "invalid arguments"))))

(defn bytes-input-stream
  "Creates an instance of ByteArrayInputStream."
  [^bytes data]
  (ByteArrayInputStream. data))

(defn bounded-input-stream
  [input size & {:keys [close?] :or {close? true}}]
  (doto (BoundedInputStream. ^InputStream input ^long size)
    (.setPropagateClose close?)))

(defn zstd-input-stream
  ^InputStream
  [input]
  (ZstdInputStream. ^InputStream input))

(defn zstd-output-stream
  ^OutputStream
  [output & {:keys [level] :or {level 0}}]
  (ZstdOutputStream. ^OutputStream output (int level)))

(defn data-input-stream
  ^DataInputStream
  [input]
  (DataInputStream. ^InputStream input))

(defn data-output-stream
  ^DataOutputStream
  [output]
  (DataOutputStream. ^OutputStream output))

(defn close!
  [^AutoCloseable stream]
  (.close stream))
