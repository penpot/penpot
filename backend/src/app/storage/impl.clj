;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage.impl
  "Storage backends abstraction layer."
  (:require
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [buddy.core.codecs :as bc]
   [clojure.java.io :as io]
   [cuerdas.core :as str])
  (:import
   java.nio.ByteBuffer
   java.util.UUID
   java.io.ByteArrayInputStream
   java.io.InputStream
   java.nio.file.Files))

;; --- API Definition

(defmulti put-object (fn [cfg _ _] (:type cfg)))

(defmethod put-object :default
  [cfg _ _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

(defmulti copy-object (fn [cfg _ _] (:type cfg)))

(defmethod copy-object :default
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


(defprotocol IContentObject)

(defn- path->content
  [path]
  (let [size (Files/size path)]
    (reify
      IContentObject
      io/IOFactory
      (make-reader [_ opts]
	    (io/make-reader path opts))
      (make-writer [_ _]
        (throw (UnsupportedOperationException. "not implemented")))
      (make-input-stream [_ opts]
        (io/make-input-stream path opts))
      (make-output-stream [_ _]
        (throw (UnsupportedOperationException. "not implemented")))
      clojure.lang.Counted
      (count [_] size)

    java.lang.AutoCloseable
    (close [_]))))

(defn string->content
  [^String v]
  (let [data (.getBytes v "UTF-8")
        bais (ByteArrayInputStream. ^bytes data)]
    (reify
      IContentObject
      io/IOFactory
      (make-reader [_ opts]
	    (io/make-reader bais opts))
      (make-writer [_ _]
        (throw (UnsupportedOperationException. "not implemented")))
      (make-input-stream [_ opts]
        (io/make-input-stream bais opts))
      (make-output-stream [_ _]
        (throw (UnsupportedOperationException. "not implemented")))

      clojure.lang.Counted
      (count [_]
        (alength data))

    java.lang.AutoCloseable
    (close [_]))))

(defn- input-stream->content
  [^InputStream is size]
  (reify
    IContentObject
    io/IOFactory
    (make-reader [_ opts]
      (io/make-reader is opts))
    (make-writer [_ _]
      (throw (UnsupportedOperationException. "not implemented")))
    (make-input-stream [_ opts]
      (io/make-input-stream is opts))
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
     (path->content data)

     (instance? java.io.File data)
     (path->content (.toPath ^java.io.File data))

     (instance? String data)
     (string->content data)

     (bytes? data)
     (input-stream->content (ByteArrayInputStream. ^bytes data) (alength ^bytes data))

     (instance? InputStream data)
     (do
       (when-not size
         (throw (UnsupportedOperationException. "size should be provided on InputStream")))
       (input-stream->content data size))

     :else
     (throw (UnsupportedOperationException. "type not supported")))))

(defn content?
  [v]
  (satisfies? IContentObject v))

(defn slurp-bytes
  [content]
  (with-open [input  (io/input-stream content)
              output (java.io.ByteArrayOutputStream. (count content))]
    (io/copy input output)
    (.toByteArray output)))

(defn resolve-backend
  [{:keys [conn pool] :as storage} backend-id]
  (when backend-id
    (let [backend (get-in storage [:backends backend-id])]
      (when-not backend
        (ex/raise :type :internal
                  :code :backend-not-configured
                  :hint (str/fmt "backend '%s' not configured" backend-id)))
      (assoc backend
             :conn (or conn pool)
             :id backend-id))))

