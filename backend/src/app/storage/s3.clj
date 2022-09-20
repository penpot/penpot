;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage.s3
  "S3 Storage backend implementation."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.storage.impl :as impl]
   [app.storage.tmp :as tmp]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   java.io.FilterInputStream
   java.io.InputStream
   java.nio.ByteBuffer
   java.time.Duration
   java.util.Collection
   java.util.Optional
   java.util.concurrent.Semaphore
   org.reactivestreams.Subscriber
   org.reactivestreams.Subscription
   software.amazon.awssdk.core.ResponseBytes
   software.amazon.awssdk.core.async.AsyncRequestBody
   software.amazon.awssdk.core.async.AsyncResponseTransformer
   software.amazon.awssdk.core.client.config.ClientAsyncConfiguration
   software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption
   software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
   software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup
   software.amazon.awssdk.regions.Region
   software.amazon.awssdk.services.s3.S3AsyncClient
   software.amazon.awssdk.services.s3.model.Delete
   software.amazon.awssdk.services.s3.model.DeleteObjectRequest
   software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
   software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
   software.amazon.awssdk.services.s3.model.GetObjectRequest
   software.amazon.awssdk.services.s3.model.ObjectIdentifier
   software.amazon.awssdk.services.s3.model.PutObjectRequest
   software.amazon.awssdk.services.s3.model.S3Error
   software.amazon.awssdk.services.s3.presigner.S3Presigner
   software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
   software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest))

(declare put-object)
(declare get-object-bytes)
(declare get-object-data)
(declare get-object-url)
(declare del-object)
(declare del-object-in-bulk)
(declare build-s3-client)
(declare build-s3-presigner)

;; --- BACKEND INIT

(s/def ::region ::us/keyword)
(s/def ::bucket ::us/string)
(s/def ::prefix ::us/string)
(s/def ::endpoint ::us/string)

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt-un [::region ::bucket ::prefix ::endpoint ::wrk/executor]))

(defmethod ig/prep-key ::backend
  [_ {:keys [prefix region] :as cfg}]
  (cond-> (d/without-nils cfg)
    (some? prefix) (assoc :prefix prefix)
    (nil? region)  (assoc :region :eu-central-1)))

(defmethod ig/init-key ::backend
  [_ cfg]
  ;; Return a valid backend data structure only if all optional
  ;; parameters are provided.
  (when (and (contains? cfg :region)
             (string? (:bucket cfg)))
    (let [client    (build-s3-client cfg)
          presigner (build-s3-presigner cfg)]
      (assoc cfg
             :client @client
             :presigner presigner
             :type :s3
             ::close-fn #(.close ^java.lang.AutoCloseable client)))))

(defmethod ig/halt-key! ::backend
  [_ {:keys [::close-fn]}]
  (when (fn? close-fn)
    (px/run! close-fn)))

(s/def ::type ::us/keyword)
(s/def ::client #(instance? S3AsyncClient %))
(s/def ::presigner #(instance? S3Presigner %))
(s/def ::backend
  (s/keys :req-un [::region ::bucket ::client ::type ::presigner]
          :opt-un [::prefix]))

;; --- API IMPL

(defmethod impl/put-object :s3
  [backend object content]
  (put-object backend object content))

(defmethod impl/get-object-data :s3
  [backend object]
  (letfn [(no-such-key? [cause]
            (instance? software.amazon.awssdk.services.s3.model.NoSuchKeyException cause))
          (handle-not-found [cause]
            (ex/raise :type :not-found
                      :code :object-not-found
                      :hint "s3 object not found"
                      :cause cause))]

    (-> (get-object-data backend object)
        (p/catch no-such-key? handle-not-found))))

(defmethod impl/get-object-bytes :s3
  [backend object]
  (get-object-bytes backend object))

(defmethod impl/get-object-url :s3
  [backend object options]
  (get-object-url backend object options))

(defmethod impl/del-object :s3
  [backend object]
  (del-object backend object))

(defmethod impl/del-objects-in-bulk :s3
  [backend ids]
  (del-object-in-bulk backend ids))

;; --- HELPERS

(def default-eventloop-threads 4)
(def default-timeout
  (dt/duration {:seconds 30}))

(defn- lookup-region
  ^Region
  [region]
  (Region/of (name region)))

(defn build-s3-client
  [{:keys [region endpoint executor]}]
  (let [hclient (.. (NettyNioAsyncHttpClient/builder)
                    (eventLoopGroupBuilder (.. (SdkEventLoopGroup/builder)
                                               (numberOfThreads (int default-eventloop-threads))))
                    (connectionAcquisitionTimeout default-timeout)
                    (connectionTimeout default-timeout)
                    (readTimeout default-timeout)
                    (writeTimeout default-timeout)
                    (build))
        client  (.. (S3AsyncClient/builder)
                    (asyncConfiguration (.. (ClientAsyncConfiguration/builder)
                                            (advancedOption SdkAdvancedAsyncClientOption/FUTURE_COMPLETION_EXECUTOR
                                                            executor)
                                            (build)))
                    (httpClient hclient)
                    (region (lookup-region region)))]

    (when-let [uri (some-> endpoint (java.net.URI.))]
      (.endpointOverride client uri))

    (let [client (.build client)]
      (reify
        clojure.lang.IDeref
        (deref [_] client)

        java.lang.AutoCloseable
        (close [_]
          (.close hclient)
          (.close client))))))

(defn build-s3-presigner
  [{:keys [region endpoint]}]
  (if (string? endpoint)
    (let [uri (java.net.URI. endpoint)]
      (.. (S3Presigner/builder)
          (endpointOverride uri)
          (region (lookup-region region))
          (build)))
    (.. (S3Presigner/builder)
        (region (lookup-region region))
        (build))))

(defn- make-request-body
  [content]
  (let [is        (io/input-stream content)
        buff-size (* 1024 64)
        sem       (Semaphore. 0)

        writer-fn (fn [s]
                    (try
                      (loop []
                        (.acquire sem 1)
                        (let [buffer (byte-array buff-size)
                              readed (.read is buffer)]
                          (when (pos? readed)
                            (.onNext ^Subscriber s (ByteBuffer/wrap buffer 0 readed))
                            (when (= readed buff-size)
                              (recur)))))
                      (.onComplete s)
                      (catch Throwable cause
                        (.onError s cause))
                      (finally
                        (.close ^InputStream is))))]

    (reify
      AsyncRequestBody
      (contentLength [_]
        (Optional/of (long (impl/get-size content))))

      (^void subscribe [_ ^Subscriber s]
       (let [thread (Thread. #(writer-fn s))]
         (.setDaemon thread true)
         (.setName thread "penpot/storage:s3")
         (.start thread)

         (.onSubscribe s (reify Subscription
                           (cancel [_]
                             (.interrupt thread)
                             (.release sem 1))
                           (request [_ n]
                             (.release sem (int n))))))))))


(defn put-object
  [{:keys [client bucket prefix]} {:keys [id] :as object} content]
  (p/let [path    (str prefix (impl/id->path id))
          mdata   (meta object)
          mtype   (:content-type mdata "application/octet-stream")
          request (.. (PutObjectRequest/builder)
                      (bucket bucket)
                      (contentType mtype)
                      (key path)
                      (build))]

    (let [content (make-request-body content)]
      (.putObject ^S3AsyncClient client
                  ^PutObjectRequest request
                  ^AsyncRequestBody content))))

(defn get-object-data
  [{:keys [client bucket prefix]} {:keys [id size]}]
  (let [gor (.. (GetObjectRequest/builder)
                (bucket bucket)
                (key (str prefix (impl/id->path id)))
                (build))]

    ;; If the file size is greater than 2MiB then stream the content
    ;; to the filesystem and then read with buffered inputstream; if
    ;; not, read the contento into memory using bytearrays.
    (if (> size (* 1024 1024 2))
      (p/let [path (tmp/tempfile :prefix "penpot.storage.s3.")
              rxf  (AsyncResponseTransformer/toFile path)
              _    (.getObject ^S3AsyncClient client
                               ^GetObjectRequest gor
                               ^AsyncResponseTransformer rxf)]
        (proxy [FilterInputStream] [(io/input-stream path)]
          (close []
            (fs/delete path)
            (proxy-super close))))

      (p/let [rxf (AsyncResponseTransformer/toBytes)
              obj (.getObject ^S3AsyncClient client
                              ^GetObjectRequest gor
                              ^AsyncResponseTransformer rxf)]
        (.asInputStream ^ResponseBytes obj)))))

(defn get-object-bytes
  [{:keys [client bucket prefix]} {:keys [id]}]
  (p/let [gor (.. (GetObjectRequest/builder)
                  (bucket bucket)
                  (key (str prefix (impl/id->path id)))
                  (build))
          rxf (AsyncResponseTransformer/toBytes)
          obj (.getObjectAsBytes ^S3AsyncClient client
                                 ^GetObjectRequest gor
                                 ^AsyncResponseTransformer rxf)]
    (.asByteArray ^ResponseBytes obj)))

(def default-max-age
  (dt/duration {:minutes 10}))

(defn get-object-url
  [{:keys [presigner bucket prefix]} {:keys [id]} {:keys [max-age] :or {max-age default-max-age}}]
  (us/assert dt/duration? max-age)
  (p/do
    (let [gor  (.. (GetObjectRequest/builder)
                   (bucket bucket)
                   (key (str prefix (impl/id->path id)))
                   (build))
          gopr (.. (GetObjectPresignRequest/builder)
                   (signatureDuration ^Duration max-age)
                   (getObjectRequest ^GetObjectRequest gor)
                   (build))
          pgor (.presignGetObject ^S3Presigner presigner ^GetObjectPresignRequest gopr)]
      (u/uri (str (.url ^PresignedGetObjectRequest pgor))))))

(defn del-object
  [{:keys [bucket client prefix]} {:keys [id] :as obj}]
  (p/let [dor (.. (DeleteObjectRequest/builder)
                  (bucket bucket)
                  (key (str prefix (impl/id->path id)))
                  (build))]
    (.deleteObject ^S3AsyncClient client
                   ^DeleteObjectRequest dor)))

(defn del-object-in-bulk
  [{:keys [bucket client prefix]} ids]
  (p/let [oids (map (fn [id]
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
          dres (.deleteObjects ^S3AsyncClient client
                               ^DeleteObjectsRequest dor)]
    (when (.hasErrors ^DeleteObjectsResponse dres)
      (let [errors (seq (.errors ^DeleteObjectsResponse dres))]
        (ex/raise :type :internal
                  :code :error-on-s3-bulk-delete
                  :s3-errors (mapv (fn [^S3Error error]
                                     {:key (.key error)
                                      :msg (.message error)})
                                   errors))))))
