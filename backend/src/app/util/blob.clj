;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.blob
  "A generic blob storage encoding. Mainly used for
  page data, page options and txlog payload storage."
  (:require [app.util.transit :as t])
  (:import
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   net.jpountz.lz4.LZ4Factory
   net.jpountz.lz4.LZ4FastDecompressor
   net.jpountz.lz4.LZ4Compressor))

(defprotocol IDataToBytes
  (->bytes [data] "convert data to bytes"))

(extend-protocol IDataToBytes
  (Class/forName "[B")
  (->bytes [data] data)

  String
  (->bytes [data] (.getBytes ^String data "UTF-8")))

(def lz4-factory (LZ4Factory/fastestInstance))

(defn encode
  [data]
  (let [data (t/encode data {:type :json})
        data-len (alength ^bytes data)
        cp (.fastCompressor ^LZ4Factory lz4-factory)
        max-len (.maxCompressedLength cp data-len)
        cdata (byte-array max-len)
        clen (.compress ^LZ4Compressor cp ^bytes data 0 data-len cdata 0 max-len)]
    (with-open [^ByteArrayOutputStream baos (ByteArrayOutputStream. (+ (alength cdata) 2 4))
                ^DataOutputStream dos (DataOutputStream. baos)]
      (.writeShort dos (short 1)) ;; version number
      (.writeInt dos (int data-len))
      (.write dos ^bytes cdata (int 0) clen)
      (.toByteArray baos))))

(declare decode-v1)

(defn decode
  "A function used for decode persisted blobs in the database."
  [data]
  (let [data (->bytes data)]
    (with-open [bais (ByteArrayInputStream. data)
                dis  (DataInputStream. bais)]
      (let [version (.readShort dis)
            udata-len (.readInt dis)]
        (case version
          1 (decode-v1 data udata-len)
          (throw (ex-info "unsupported version" {:version version})))))))

(defn- decode-v1
  [^bytes cdata ^long udata-len]
  (let [^LZ4FastDecompressor dcp (.fastDecompressor ^LZ4Factory lz4-factory)
        ^bytes udata (byte-array udata-len)]
    (.decompress dcp cdata 6 udata 0 udata-len)
    (t/decode udata {:type :json})))

