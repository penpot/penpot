;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.storage.impl
  "Storage backends abstraction layer."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [clojure.java.io :as io]
   [buddy.core.codecs :as bc])
  (:import
   java.nio.ByteBuffer
   java.util.UUID
   java.io.ByteArrayInputStream
   java.io.InputStream
   java.nio.file.Path
   java.nio.file.Files))

;; --- API Definition

(defmulti put-object (fn [cfg _ _] (:type cfg)))

(defmethod put-object :default
  [cfg _ _]
  (ex/raise :type :internal
            :code :invalid-storage-backend
            :context cfg))

(defmulti get-object (fn [cfg _] (:type cfg)))

(defmethod get-object :default
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

(defn- path->content-object
  [path]
  (let [size (Files/size path)]
    (reify
      IContentObject
      io/IOFactory
      (make-reader [_ opts]
	    (io/make-reader path opts))
      (make-writer [_ opts]
        (throw (UnsupportedOperationException. "not implemented")))
      (make-input-stream [_ opts]
        (io/make-input-stream path opts))
      (make-output-stream [_ opts]
        (throw (UnsupportedOperationException. "not implemented")))
      clojure.lang.Counted
      (count [_] size))))

(defn string->content-object
  [^String v]
  (let [data (.getBytes v "UTF-8")
        bais (ByteArrayInputStream. ^bytes data)]
    (reify
      IContentObject
      io/IOFactory
      (make-reader [_ opts]
	    (io/make-reader bais opts))
      (make-writer [_ opts]
        (throw (UnsupportedOperationException. "not implemented")))
      (make-input-stream [_ opts]
        (io/make-input-stream bais opts))
      (make-output-stream [_ opts]
        (throw (UnsupportedOperationException. "not implemented")))

      clojure.lang.Counted
      (count [_]
        (alength data)))))

(defn- input-stream->content-object
  [^InputStream is size]
  (reify
    IContentObject
    io/IOFactory
    (make-reader [_ opts]
	  (io/make-reader is opts))
    (make-writer [_ opts]
      (throw (UnsupportedOperationException. "not implemented")))
    (make-input-stream [_ opts]
      (io/make-input-stream is opts))
    (make-output-stream [_ opts]
      (throw (UnsupportedOperationException. "not implemented")))

      clojure.lang.Counted
      (count [_] size)))

(defn content-object
  ([data] (content-object data nil))
  ([data size]
   (cond
     (instance? java.nio.file.Path data)
     (path->content-object data)

     (instance? java.io.File data)
     (path->content-object (.toPath ^java.io.File data))

     (instance? String data)
     (string->content-object data)

     (instance? InputStream data)
     (do
       (when-not size
         (throw (UnsupportedOperationException. "size should be provided on InputStream")))
       (input-stream->content-object data size))

     :else
     (throw (UnsupportedOperationException. "type not supported")))))

(defn content-object?
  [v]
  (satisfies? IContentObject v))

(defn slurp-bytes
  [content]
  (us/assert content-object? content)
  (with-open [input  (io/input-stream content)
              output (java.io.ByteArrayOutputStream. (count content))]
    (io/copy input output)
    (.toByteArray output)))


