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
  (:require
   [app.config :as cfg]
   [app.util.transit :as t]
   [taoensso.nippy :as n])
  (:import
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   com.github.luben.zstd.Zstd
   net.jpountz.lz4.LZ4Factory
   net.jpountz.lz4.LZ4FastDecompressor
   net.jpountz.lz4.LZ4Compressor))

(def lz4-factory (LZ4Factory/fastestInstance))

(declare decode-v1)
(declare decode-v2)
(declare encode-v1)
(declare encode-v2)

(def default-version
  (:default-blob-version cfg/config 1))

(defn encode
  ([data] (encode data nil))
  ([data {:keys [version] :or {version default-version}}]
   (case version
     1 (encode-v1 data)
     2 (encode-v2 data)
     (throw (ex-info "unsupported version" {:version version})))))

(defn decode
  "A function used for decode persisted blobs in the database."
  [^bytes data]
  (with-open [bais (ByteArrayInputStream. data)
              dis  (DataInputStream. bais)]
    (let [version (.readShort dis)
          ulen    (.readInt dis)]
      (case version
        1 (decode-v1 data ulen)
        2 (decode-v2 data ulen)
        (throw (ex-info "unsupported version" {:version version}))))))

;; --- IMPL

(defn- encode-v1
  [data]
  (let [data  (t/encode data {:type :json})
        dlen  (alength ^bytes data)
        cp    (.fastCompressor ^LZ4Factory lz4-factory)
        mlen  (.maxCompressedLength cp dlen)
        cdata (byte-array mlen)
        clen  (.compress ^LZ4Compressor cp ^bytes data 0 dlen cdata 0 mlen)]
    (with-open [^ByteArrayOutputStream baos (ByteArrayOutputStream. (+ (alength cdata) 2 4))
                ^DataOutputStream dos (DataOutputStream. baos)]
      (.writeShort dos (short 1)) ;; version number
      (.writeInt dos (int dlen))
      (.write dos ^bytes cdata (int 0) clen)
      (.toByteArray baos))))

(defn- decode-v1
  [^bytes cdata ^long ulen]
  (let [dcp   (.fastDecompressor ^LZ4Factory lz4-factory)
        udata (byte-array ulen)]
    (.decompress ^LZ4FastDecompressor dcp cdata 6 ^bytes udata 0 ulen)
    (t/decode udata {:type :json})))

(defn- encode-v2
  [data]
  (let [data  (n/fast-freeze data)
        dlen  (alength data)
        mlen  (Zstd/compressBound dlen)
        cdata (byte-array mlen)
        clen  (Zstd/compressByteArray ^bytes cdata 0 mlen
                                      ^bytes data 0 dlen
                                      8)]
    (with-open [^ByteArrayOutputStream baos (ByteArrayOutputStream. (+ (alength cdata) 2 4))
                ^DataOutputStream dos (DataOutputStream. baos)]
      (.writeShort dos (short 2)) ;; version number
      (.writeInt dos (int dlen))
      (.write dos ^bytes cdata (int 0) clen)
      (.toByteArray baos))))

(defn- decode-v2
  [^bytes cdata ^long ulen]
  (let [udata (byte-array ulen)]
    (Zstd/decompressByteArray ^bytes udata 0 ulen
                              ^bytes cdata 6 (- (alength cdata) 6))
    (n/fast-thaw udata)))
