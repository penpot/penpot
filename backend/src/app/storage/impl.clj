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
   [buddy.core.codecs :as bc]
   [buddy.core.hash :as bh]
   [clojure.java.io :as jio]
   [datoteka.io :as io])
  (:import
   java.nio.ByteBuffer
   java.nio.file.Files
   java.nio.file.Path
   java.util.UUID))

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
    (string? id) (parse-uuid id)
    (uuid? id)   id
    :else        (ex/raise :type :internal
                           :code :invalid-id-type
                           :hint "id should be string or uuid")))

(defprotocol IContentObject
  (get-size [_] "get object size"))

(defprotocol IContentHash
  (get-hash [_] "get precalculated hash"))

(defn- path->content
  [^Path path ^long size]
  (reify
    IContentObject
    (get-size [_] size)

    jio/IOFactory
    (make-reader [this opts]
      (jio/make-reader this opts))
    (make-writer [_ _]
      (throw (UnsupportedOperationException. "not implemented")))
    (make-input-stream [_ _]
      (-> (io/input-stream path)
          (io/bounded-input-stream size)))
    (make-output-stream [_ _]
      (throw (UnsupportedOperationException. "not implemented")))))

(defn- bytes->content
  [^bytes data ^long size]
  (reify
    IContentObject
    (get-size [_] size)

    jio/IOFactory
    (make-reader [this opts]
      (jio/make-reader this opts))
    (make-writer [_ _]
      (throw (UnsupportedOperationException. "not implemented")))
    (make-input-stream [_ _]
      (-> (io/bytes-input-stream data)
          (io/bounded-input-stream size)))
    (make-output-stream [_ _]
      (throw (UnsupportedOperationException. "not implemented")))))

(defn content
  ([data] (content data nil))
  ([data size]
   (cond
     (instance? java.nio.file.Path data)
     (path->content data (or size (Files/size data)))

     (instance? java.io.File data)
     (content (.toPath ^java.io.File data) size)

     (instance? String data)
     (let [data (.getBytes data "UTF-8")]
       (bytes->content data (alength data)))

     (bytes? data)
     (bytes->content data (or size (alength ^bytes data)))

     ;; (instance? InputStream data)
     ;; (do
     ;;   (when-not size
     ;;     (throw (UnsupportedOperationException. "size should be provided on InputStream")))
     ;;   (make-content data size))

     :else
     (throw (IllegalArgumentException. "invalid argument type")))))

(defn wrap-with-hash
  [content ^String hash]
  (when-not (satisfies? IContentObject content)
    (throw (UnsupportedOperationException. "`content` should be an instance of IContentObject")))

  (when-not (satisfies? jio/IOFactory content)
    (throw (UnsupportedOperationException. "`content` should be an instance of IOFactory")))

  (reify
    IContentObject
    (get-size [_] (get-size content))

    IContentHash
    (get-hash [_] hash)

    jio/IOFactory
    (make-reader [_ opts]
      (jio/make-reader content opts))
    (make-writer [_ opts]
      (jio/make-writer content opts))
    (make-input-stream [_ opts]
      (jio/make-input-stream content opts))
    (make-output-stream [_ opts]
      (jio/make-output-stream content opts))))

(defn content?
  [v]
  (satisfies? IContentObject v))

(defn calculate-hash
  [resource]
  (let [result (with-open [input (io/input-stream resource)]
                 (-> (bh/blake2b-256 input)
                     (bc/bytes->hex)))]
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
