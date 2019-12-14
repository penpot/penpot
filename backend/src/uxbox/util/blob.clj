;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.blob
  "A generic blob storage encoding. Mainly used for
  page data, page options and txlog payload storage."
  (:require [uxbox.util.transit :as t])
  (:import
   io.vertx.core.buffer.Buffer
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   org.xerial.snappy.Snappy))

(defprotocol IDataToBytes
  (->bytes [data] "convert data to bytes"))

(extend-protocol IDataToBytes
  (Class/forName "[B")
  (->bytes [data] data)

  Buffer
  (->bytes [data] (.getBytes ^Buffer data))

  String
  (->bytes [data] (.getBytes ^String data "UTF-8")))

(defn str->bytes
  "Convert string to byte array."
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn bytes->str
  "Convert byte array to String."
  ([^bytes data]
   (bytes->str data "UTF-8"))
  ([^bytes data, ^String encoding]
   (String. data encoding)))

(defn buffer
  [^bytes data]
  (Buffer/buffer data))

(defn encode-with-json
  "A function used for encode data for transfer it to frontend."
  ([data] (encode-with-json data false))
  ([data verbose?]
   (let [type (if verbose? :json-verbose :json)]
     (-> (t/encode data {:type type})
         (Buffer/buffer)))))

(defn decode-from-json
  "A function used for parse data coming from frontend."
  [data]
  (-> (->bytes data)
      (t/decode {:type :json})))

(defn encode
  "A function used for encode data for persist in the database."
  [data]
  (let [data (t/encode data {:type :json})
        data-len (alength ^bytes data)
        cdata (Snappy/compress ^bytes data)]
    (with-open [^ByteArrayOutputStream baos (ByteArrayOutputStream. (+ (alength cdata) 2 4))
                ^DataOutputStream dos (DataOutputStream. baos)]
      (.writeShort dos (short 1)) ;; version number
      (.writeInt dos (int data-len))
      (.write dos ^bytes cdata (int 0) (alength cdata))
      (-> (.toByteArray baos)
          (buffer)))))

(declare decode-v1)

(defn decode
  "A function used for decode persisted blobs in the database."
  [data]
  (let [data (->bytes data)]
    (with-open [bais (ByteArrayInputStream. data)
                dis  (DataInputStream. bais)]
      (let [version (.readShort dis)
            udata-len (.readInt dis)]
        (when (not= version 1)
          (throw (ex-info "unsupported version" {:version version})))
        (decode-v1 data udata-len)))))

(defn- decode-v1
  [data udata-len]
  (let [^bytes output-ba (byte-array udata-len)]
    (Snappy/uncompress data 6 (- (alength data) 6) output-ba 0)
    (t/decode output-ba {:type :json})))
