;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage.s3
  "S3 Storage backend implementation."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.storage :as-alias sto]
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
   java.net.URI
   java.nio.ByteBuffer
   java.nio.file.Path
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
   software.amazon.awssdk.services.s3.S3AsyncClientBuilder
   software.amazon.awssdk.services.s3.S3Configuration
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

;; (set! *warn-on-reflection* true)
;; (set! *unchecked-math* :warn-on-boxed)

;; --- BACKEND INIT

(s/def ::region ::us/keyword)
(s/def ::bucket ::us/string)
(s/def ::prefix ::us/string)
(s/def ::endpoint ::us/string)

(defmethod ig/pre-init-spec ::backend [_]
  (s/keys :opt [::region ::bucket ::prefix ::endpoint ::wrk/executor]))

(defmethod ig/prep-key ::backend
  [_ {:keys [::prefix ::region] :as cfg}]
  (cond-> (d/without-nils cfg)
    (some? prefix) (assoc ::prefix prefix)
    (nil? region)  (assoc ::region :eu-central-1)))

(defmethod ig/init-key ::backend
  [_ cfg]
  ;; Return a valid backend data structure only if all optional
  ;; parameters are provided.
  (when (and (contains? cfg ::region)
             (string? (::bucket cfg)))
    (let [client    (build-s3-client cfg)
          presigner (build-s3-presigner cfg)]
      (assoc cfg
             ::sto/type :s3
             ::client @client
             ::presigner presigner
             ::close-fn #(.close ^java.lang.AutoCloseable client)))))

(defmethod ig/halt-key! ::backend
  [_ {:keys [::close-fn]}]
  (when (fn? close-fn)
    (px/run! close-fn)))

(s/def ::client #(instance? S3AsyncClient %))
(s/def ::presigner #(instance? S3Presigner %))
(s/def ::backend
  (s/keys :req [::region
                ::bucket
                ::client
                ::presigner]
          :opt [::prefix
                ::sto/id
                ::wrk/executor]))

;; --- API IMPL

(defmethod impl/put-object :s3
  [backend object content]
  (us/assert! ::backend backend)
  (p/await! (put-object backend object content)))

(defmethod impl/get-object-data :s3
  [backend object]
  (us/assert! ::backend backend)
  (letfn [(no-such-key? [cause]
            (instance? software.amazon.awssdk.services.s3.model.NoSuchKeyException cause))
          (handle-not-found [cause]
            (ex/raise :type :not-found
                      :code :object-not-found
                      :hint "s3 object not found"
                      :cause cause))]

    (-> (get-object-data backend object)
        (p/catch no-such-key? handle-not-found)
        (p/await!))))

(defmethod impl/get-object-bytes :s3
  [backend object]
  (us/assert! ::backend backend)
  (p/await! (get-object-bytes backend object)))

(defmethod impl/get-object-url :s3
  [backend object options]
  (us/assert! ::backend backend)
  (get-object-url backend object options))

(defmethod impl/del-object :s3
  [backend object]
  (us/assert! ::backend backend)
  (p/await! (del-object backend object)))

(defmethod impl/del-objects-in-bulk :s3
  [backend ids]
  (us/assert! ::backend backend)
  (p/await! (del-object-in-bulk backend ids)))

;; --- HELPERS

(def default-eventloop-threads 4)
(def default-timeout
  (dt/duration {:seconds 30}))

(defn- lookup-region
  ^Region
  [region]
  (Region/of (name region)))

(defn- build-s3-client
  [{:keys [::region ::endpoint ::wrk/executor]}]
  (let [aconfig (-> (ClientAsyncConfiguration/builder)
                    (.advancedOption SdkAdvancedAsyncClientOption/FUTURE_COMPLETION_EXECUTOR executor)
                    (.build))

        sconfig (-> (S3Configuration/builder)
                    (cond-> (some? endpoint) (.pathStyleAccessEnabled true))
                    (.build))

        hclient (-> (NettyNioAsyncHttpClient/builder)
                    (.eventLoopGroupBuilder (-> (SdkEventLoopGroup/builder)
                                                (.numberOfThreads (int default-eventloop-threads))))
                    (.connectionAcquisitionTimeout default-timeout)
                    (.connectionTimeout default-timeout)
                    (.readTimeout default-timeout)
                    (.writeTimeout default-timeout)
                    (.build))

        client  (let [builder (S3AsyncClient/builder)
                      builder (.serviceConfiguration ^S3AsyncClientBuilder builder ^S3Configuration sconfig)
                      builder (.asyncConfiguration ^S3AsyncClientBuilder builder ^ClientAsyncConfiguration aconfig)
                      builder (.httpClient ^S3AsyncClientBuilder builder ^NettyNioAsyncHttpClient hclient)
                      builder (.region ^S3AsyncClientBuilder builder (lookup-region region))
                      builder (cond-> ^S3AsyncClientBuilder builder
                                (some? endpoint)
                                (.endpointOverride (URI. endpoint)))]
                  (.build ^S3AsyncClientBuilder builder))

        ]

    (reify
      clojure.lang.IDeref
      (deref [_] client)

      java.lang.AutoCloseable
      (close [_]
        (.close ^NettyNioAsyncHttpClient hclient)
        (.close ^S3AsyncClient client)))))

(defn- build-s3-presigner
  [{:keys [::region ::endpoint]}]
  (let [config (-> (S3Configuration/builder)
                   (cond-> (some? endpoint) (.pathStyleAccessEnabled true))
                   (.build))]

    (-> (S3Presigner/builder)
        (cond-> (some? endpoint) (.endpointOverride (URI. endpoint)))
        (.region (lookup-region region))
        (.serviceConfiguration ^S3Configuration config)
        (.build))))

(defn- upload-thread
  [id subscriber sem content]
  (px/thread
    {:name "penpot/s3/uploader"
     :daemon true}
    (l/trace :hint "start upload thread"
             :object-id (str id)
             :size (impl/get-size content)
             ::l/sync? true)
    (let [stream (io/input-stream content)
          bsize  (* 1024 64)
          tpoint (dt/tpoint)]
      (try
        (loop []
          (.acquire ^Semaphore sem 1)
          (let [buffer (byte-array bsize)
                readed (.read ^InputStream stream buffer)]
            (when (pos? readed)
              (let [data (ByteBuffer/wrap ^bytes buffer 0 readed)]
                (.onNext ^Subscriber subscriber ^ByteBuffer data)
                (when (= readed bsize)
                  (recur))))))
        (.onComplete ^Subscriber subscriber)
        (catch InterruptedException _
          (l/trace :hint "interrupted upload thread"
                   :object-:id (str id)
                   ::l/sync? true)
          nil)
        (catch Throwable cause
          (.onError ^Subscriber subscriber cause))
        (finally
          (l/trace :hint "end upload thread"
                   :object-id (str id)
                   :elapsed (dt/format-duration (tpoint))
                   ::l/sync? true)
          (.close ^InputStream stream))))))

(defn- make-request-body
  [id content]
  (reify
    AsyncRequestBody
    (contentLength [_]
      (Optional/of (long (impl/get-size content))))

    (^void subscribe [_ ^Subscriber subscriber]
     (let [sem (Semaphore. 0)
           thr (upload-thread id subscriber sem content)]
       (.onSubscribe subscriber
                     (reify Subscription
                       (cancel [_]
                         (px/interrupt! thr)
                         (.release sem 1))
                       (request [_ n]
                         (.release sem (int n)))))))))


(defn- put-object
  [{:keys [::client ::bucket ::prefix]} {:keys [id] :as object} content]
  (let [path    (dm/str prefix (impl/id->path id))
        mdata   (meta object)
        mtype   (:content-type mdata "application/octet-stream")
        rbody   (make-request-body id content)
        request (.. (PutObjectRequest/builder)
                    (bucket bucket)
                    (contentType mtype)
                    (key path)
                    (build))]
    (->> (.putObject ^S3AsyncClient client
                     ^PutObjectRequest request
                     ^AsyncRequestBody rbody)
         (p/fmap (constantly object)))))

;; FIXME: research how to avoid reflection on close method
(defn- path->stream
  [path]
  (proxy [FilterInputStream] [(io/input-stream path)]
    (close []
      (fs/delete path)
      (proxy-super close))))

(defn- get-object-data
  [{:keys [::client ::bucket ::prefix]} {:keys [id size]}]
  (let [gor (.. (GetObjectRequest/builder)
                (bucket bucket)
                (key (str prefix (impl/id->path id)))
                (build))]

    ;; If the file size is greater than 2MiB then stream the content
    ;; to the filesystem and then read with buffered inputstream; if
    ;; not, read the contento into memory using bytearrays.
    (if (> ^long size (* 1024 1024 2))
      (let [path (tmp/tempfile :prefix "penpot.storage.s3.")
            rxf  (AsyncResponseTransformer/toFile ^Path path)]
        (->> (.getObject ^S3AsyncClient client
                         ^GetObjectRequest gor
                         ^AsyncResponseTransformer rxf)
             (p/fmap (constantly path))
             (p/fmap path->stream)))

      (let [rxf (AsyncResponseTransformer/toBytes)]
        (->> (.getObject ^S3AsyncClient client
                         ^GetObjectRequest gor
                         ^AsyncResponseTransformer rxf)
             (p/fmap #(.asInputStream ^ResponseBytes %)))))))

(defn- get-object-bytes
  [{:keys [::client ::bucket ::prefix]} {:keys [id]}]
  (let [gor (.. (GetObjectRequest/builder)
                (bucket bucket)
                (key (str prefix (impl/id->path id)))
                (build))
        rxf (AsyncResponseTransformer/toBytes)]
    (->> (.getObject ^S3AsyncClient client
                     ^GetObjectRequest gor
                     ^AsyncResponseTransformer rxf)
         (p/fmap #(.asByteArray ^ResponseBytes %)))))

(def default-max-age
  (dt/duration {:minutes 10}))

(defn- get-object-url
  [{:keys [::presigner ::bucket ::prefix]} {:keys [id]} {:keys [max-age] :or {max-age default-max-age}}]
  (us/assert dt/duration? max-age)
  (let [gor  (.. (GetObjectRequest/builder)
                 (bucket bucket)
                 (key (dm/str prefix (impl/id->path id)))
                 (build))
        gopr (.. (GetObjectPresignRequest/builder)
                 (signatureDuration ^Duration max-age)
                 (getObjectRequest ^GetObjectRequest gor)
                 (build))
        pgor (.presignGetObject ^S3Presigner presigner ^GetObjectPresignRequest gopr)]
    (u/uri (str (.url ^PresignedGetObjectRequest pgor)))))

(defn- del-object
  [{:keys [::bucket ::client ::prefix]} {:keys [id] :as obj}]
  (let [dor (.. (DeleteObjectRequest/builder)
                (bucket bucket)
                (key (dm/str prefix (impl/id->path id)))
                (build))]
    (->> (.deleteObject ^S3AsyncClient client ^DeleteObjectRequest dor)
         (p/fmap (constantly nil)))))

(defn- del-object-in-bulk
  [{:keys [::bucket ::client ::prefix]} ids]

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
                 (build))]

    (->> (.deleteObjects ^S3AsyncClient client ^DeleteObjectsRequest dor)
         (p/fmap (fn [dres]
                   (when (.hasErrors ^DeleteObjectsResponse dres)
                     (let [errors (seq (.errors ^DeleteObjectsResponse dres))]
                       (ex/raise :type :internal
                                 :code :error-on-s3-bulk-delete
                                 :s3-errors (mapv (fn [^S3Error error]
                                                    {:key (.key error)
                                                     :msg (.message error)})
                                                  errors)))))))))
