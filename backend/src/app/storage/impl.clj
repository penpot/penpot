;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage.impl
  "Storage backends abstraction layer."
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [buddy.core.codecs :as bc]
   [buddy.core.hash :as bh]
   [clojure.java.io :as io])
  (:import
   java.nio.ByteBuffer
   java.util.UUID
   java.io.ByteArrayInputStream
   java.io.InputStream
   java.nio.file.Files
   org.apache.commons.io.input.BoundedInputStream
   ))

;; --- API Definition

(defmulti put-object (fn [cfg _ _] (:type cfg)))

(defmethod put-object :default
  [cfg _ _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

(defmulti get-object-data (fn [cfg _] (:type cfg)))

(defmethod get-object-data :default
  [cfg _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

(defmulti get-object-bytes (fn [cfg _] (:type cfg)))

(defmethod get-object-bytes :default
  [cfg _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

(defmulti get-object-url (fn [cfg _ _] (:type cfg)))

(defmethod get-object-url :default
  [cfg _ _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))


(defmulti del-object (fn [cfg _] (:type cfg)))

(defmethod del-object :default
  [cfg _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

(defmulti del-objects-in-bulk (fn [cfg _] (:type cfg)))

(defmethod del-objects-in-bulk :default
  [cfg _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

;; --- HELPERS

(defn uuid->hex
  [^UUID v]
  (let [buffer (ByteBuffer/allocate 16)]
    (.putLong buffer (.getMostSignificantBits v))
    (.putLong buffer (.getLeastSignificantBits v))
    (bc/bytes->hex (.array buffer))))

(defn id->path
  [id]
  (let [tokens (->> (uuid->hex id)
                    (re-seq #"[\w\d]{2}"))
        prefix (take 2 tokens)
        suffix (drop 2 tokens)]
    (str (apply str (interpose "/" prefix))
         "/"
         (apply str suffix))))

(defn coerce-id
  [id]
  (cond
    (string? id) (uuid/uuid id)
    (uuid? id) id
    :else (ex/raise :type :internal
                    :code :invalid-id-type
                    :hint "id should be string or uuid")))

(defprotocol IContentObject
  (size [_] "get object size"))

(defprotocol IContentHash
  (get-hash [_] "get precalculated hash"))

(defn- make-content
  [^InputStream is ^long size]
  (reify
    IContentObject
    (size [_] size)

    io/IOFactory
    (make-reader [this opts]
      (io/make-reader this opts))
    (make-writer [_ _]
      (throw (UnsupportedOperationException. "not implemented")))
    (make-input-stream [_ _]
      (doto (BoundedInputStream. is size)
        (.setPropagateClose false)))
    (make-output-stream [_ _]
      (throw (UnsupportedOperationException. "not implemented")))

    clojure.lang.Counted
    (count [_] size)

    java.lang.AutoCloseable
    (close [_]
      (.close is))))

(defn content
  ([data] (content data nil))
  ([data size]
   (cond
     (instance? java.nio.file.Path data)
     (make-content (io/input-stream data)
                   (Files/size data))

     (instance? java.io.File data)
     (content (.toPath ^java.io.File data) nil)

     (instance? String data)
     (let [data (.getBytes data "UTF-8")
           bais (ByteArrayInputStream. ^bytes data)]
       (make-content bais (alength data)))

     (bytes? data)
     (let [size (alength ^bytes data)
           bais (ByteArrayInputStream. ^bytes data)]
       (make-content bais size))

     (instance? InputStream data)
     (do
       (when-not size
         (throw (UnsupportedOperationException. "size should be provided on InputStream")))
       (make-content data size))

     :else
     (throw (UnsupportedOperationException. "type not supported")))))

(defn wrap-with-hash
  [content ^String hash]
  (when-not (satisfies? IContentObject content)
    (throw (UnsupportedOperationException. "`content` should be an instance of IContentObject")))

  (when-not (satisfies? io/IOFactory content)
    (throw (UnsupportedOperationException. "`content` should be an instance of IOFactory")))

  (reify
    IContentObject
    (size [_] (size content))

    IContentHash
    (get-hash [_] hash)

    io/IOFactory
    (make-reader [_ opts]
      (io/make-reader content opts))
    (make-writer [_ opts]
      (io/make-writer content opts))
    (make-input-stream [_ opts]
      (io/make-input-stream content opts))
    (make-output-stream [_ opts]
      (io/make-output-stream content opts))

    clojure.lang.Counted
    (count [_] (count content))

    java.lang.AutoCloseable
    (close [_]
      (.close ^java.lang.AutoCloseable content))))

(defn content?
  [v]
  (satisfies? IContentObject v))

(defn slurp-bytes
  [content]
  (with-open [input  (io/input-stream content)
              output (java.io.ByteArrayOutputStream. (count content))]
    (io/copy input output)
    (.toByteArray output)))

(defn calculate-hash
  [path-or-stream]
  (let [result (cond
                 (instance? InputStream path-or-stream)
                 (let [result (-> (bh/blake2b-256 path-or-stream)
                                  (bc/bytes->hex))]
                   (.reset path-or-stream)
                   result)

                 :else
                 (with-open [is (io/input-stream path-or-stream)]
                   (-> (bh/blake2b-256 is)
                       (bc/bytes->hex))))]
    (str "blake2b:" result)))

(defn resolve-backend
  [{:keys [conn pool executor] :as storage} backend-id]
  (let [backend (get-in storage [:backends backend-id])]
    (when-not backend
      (ex/raise :type :internal
                :code :backend-not-configured
                :hint (dm/fmt "backend '%' not configured" backend-id)))
    (assoc backend
           :executor executor
           :conn (or conn pool)
           :id backend-id)))
