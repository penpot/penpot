;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage.s3
  "Storage backends abstraction layer."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.storage.impl :as impl]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig])
  (:import
   java.time.Duration
   java.util.Collection
   software.amazon.awssdk.core.sync.RequestBody
   software.amazon.awssdk.regions.Region
   software.amazon.awssdk.services.s3.S3Client
   software.amazon.awssdk.services.s3.model.Delete
   software.amazon.awssdk.services.s3.model.CopyObjectRequest
   software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
   software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
   software.amazon.awssdk.services.s3.model.GetObjectRequest
   software.amazon.awssdk.services.s3.model.ObjectIdentifier
   software.amazon.awssdk.services.s3.model.PutObjectRequest
   software.amazon.awssdk.services.s3.presigner.S3Presigner
   software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
   software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest))

(declare put-object)
(declare copy-object)
(declare get-object)
(declare get-object-url)
(declare del-object-in-bulk)
(declare build-s3-client)
(declare build-s3-presigner)

;; --- BACKEND INIT

(s/def ::region #{:eu-central-1})
(s/def ::bucket ::us/string)
(s/def ::prefix ::us/string)

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt-un [::region ::bucket ::prefix]))

(defmethod ig/prep-key ::backend
  [_ {:keys [prefix] :as cfg}]
  (cond-> (d/without-nils cfg)
    prefix (assoc :prefix prefix)))

(defmethod ig/init-key ::backend
  [_ cfg]
  ;; Return a valid backend data structure only if all optional
  ;; parameters are provided.
  (when (and (contains? cfg :region)
             (string? (:bucket cfg)))
    (let [client    (build-s3-client cfg)
          presigner (build-s3-presigner cfg)]
      (assoc cfg
             :client client
             :presigner presigner
             :type :s3))))

(s/def ::type ::us/keyword)
(s/def ::client #(instance? S3Client %))
(s/def ::presigner #(instance? S3Presigner %))
(s/def ::backend
  (s/keys :req-un [::region ::bucket ::client ::type ::presigner]
          :opt-un [::prefix]))

;; --- API IMPL

(defmethod impl/put-object :s3
  [backend object content]
  (put-object backend object content))

(defmethod impl/copy-object :s3
  [backend src-object dst-object]
  (copy-object backend src-object dst-object))

(defmethod impl/get-object-data :s3
  [backend object]
  (get-object backend object))

(defmethod impl/get-object-url :s3
  [backend object options]
  (get-object-url backend object options))

(defmethod impl/del-objects-in-bulk :s3
  [backend ids]
  (del-object-in-bulk backend ids))

;; --- HELPERS

(defn- ^Region lookup-region
  [region]
  (case region
    :eu-central-1 Region/EU_CENTRAL_1))

(defn- build-s3-client
  [{:keys [region]}]
  (.. (S3Client/builder)
      (region (lookup-region region))
      (build)))

(defn- build-s3-presigner
  [{:keys [region]}]
  (.. (S3Presigner/builder)
      (region (lookup-region region))
      (build)))

(defn- put-object
  [{:keys [client bucket prefix]} {:keys [id] :as object} content]
  (let [path    (str prefix (impl/id->path id))
        mdata   (meta object)
        mtype   (:content-type mdata "application/octet-stream")
        request (.. (PutObjectRequest/builder)
                    (bucket bucket)
                    (contentType mtype)
                    (key path)
                    (build))
        content (RequestBody/fromInputStream (io/input-stream content)
                                             (count content))]
    (.putObject ^S3Client client
                ^PutObjectRequest request
                ^RequestBody content)))

(defn- copy-object
  [{:keys [client bucket prefix]} src-object dst-object]
  (let [source-path  (str prefix (impl/id->path (:id src-object)))
        source-mdata (meta src-object)
        source-mtype (:content-type source-mdata "application/octet-stream")
        dest-path    (str prefix (impl/id->path (:id dst-object)))

        request      (.. (CopyObjectRequest/builder)
                         (copySource (u/query-encode (str bucket "/" source-path)))
                         (destinationBucket bucket)
                         (destinationKey dest-path)
                         (contentType source-mtype)
                         (build))]

    (.copyObject ^S3Client client
                 ^CopyObjectRequest request)))

(defn- get-object
  [{:keys [client bucket prefix]} {:keys [id]}]
  (let [gor (.. (GetObjectRequest/builder)
                (bucket bucket)
                (key (str prefix (impl/id->path id)))
                (build))
        obj (.getObject ^S3Client client ^GetObjectRequest gor)]
    (io/input-stream obj)))

(def default-max-age
  (dt/duration {:minutes 10}))

(defn- get-object-url
  [{:keys [presigner bucket prefix]} {:keys [id]} {:keys [max-age] :or {max-age default-max-age}}]
  (us/assert dt/duration? max-age)
  (let [gor  (.. (GetObjectRequest/builder)
                 (bucket bucket)
                 (key (str prefix (impl/id->path id)))
                 (build))
        gopr (.. (GetObjectPresignRequest/builder)
                 (signatureDuration ^Duration max-age)
                 (getObjectRequest ^GetObjectRequest gor)
                 (build))
        pgor (.presignGetObject ^S3Presigner presigner ^GetObjectPresignRequest gopr)]
    (u/uri (str (.url ^PresignedGetObjectRequest pgor)))))

(defn- del-object-in-bulk
  [{:keys [bucket client prefix]} ids]
  (let [oids (map (fn [id]
                    (.. (ObjectIdentifier/builder)
                        (key (str prefix (impl/id->path id)))
                        (build)))
                  ids)
        delc (.. (Delete/builder)
                 (objects ^Collection oids)
                 (build))
        dor  (.. (DeleteObjectsRequest/builder)
                 (bucket bucket)
                 (delete ^Delete delc)
                 (build))
        dres (.deleteObjects ^S3Client client
                             ^DeleteObjectsRequest dor)]
    (when (.hasErrors ^DeleteObjectsResponse dres)
      (let [errors (seq (.errors ^DeleteObjectsResponse dres))]
        (ex/raise :type :s3-error
                  :code :error-on-bulk-delete
                  :context errors)))))
