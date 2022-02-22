;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.assets
  "Assets related handlers."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.util.async :as async]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]))

(def ^:private cache-max-age
  (dt/duration {:hours 24}))

(def ^:private signature-max-age
  (dt/duration {:hours 24 :minutes 15}))

(defn coerce-id
  [id]
  (let [res (us/uuid-conformer id)]
    (when-not (uuid? res)
      (ex/raise :type :not-found
                :hint "object not found"))
    res))

(defn- get-file-media-object
  [{:keys [pool] :as storage} id]
  (let [id   (coerce-id id)
        mobj (db/exec-one! pool ["select * from file_media_object where id=?" id])]
    (when-not mobj
      (ex/raise :type :not-found
                :hint "object does not found"))
      mobj))

(defn- serve-object
  [{:keys [storage] :as cfg} obj]
  (let [mdata   (meta obj)
        backend (sto/resolve-backend storage (:backend obj))]
    (case (:type backend)
      :db
      {:status 200
       :headers {"content-type" (:content-type mdata)
                 "cache-control" (str "max-age=" (inst-ms cache-max-age))}
       :body (sto/get-object-bytes storage obj)}

      :s3
      (let [{:keys [host port] :as url} (sto/get-object-url storage obj {:max-age signature-max-age})]
        {:status 307
         :headers {"location" (str url)
                   "x-host"   (cond-> host port (str ":" port))
                   "cache-control" (str "max-age=" (inst-ms cache-max-age))}
         :body ""})

      :fs
      (let [purl (u/uri (:assets-path cfg))
            purl (u/join purl (sto/object->relative-path obj))]
        {:status 204
         :headers {"x-accel-redirect" (:path purl)
                   "content-type" (:content-type mdata)
                   "cache-control" (str "max-age=" (inst-ms cache-max-age))}
         :body ""}))))

(defn- generic-handler
  [{:keys [storage executor] :as cfg} request kf]
  (async/with-dispatch executor
    (let [id   (get-in request [:path-params :id])
          mobj (get-file-media-object storage id)
          obj  (sto/get-object storage (kf mobj))]
      (if obj
        (serve-object cfg obj)
        {:status 404 :body ""}))))

(defn objects-handler
  [{:keys [storage executor] :as cfg} request respond raise]
  (-> (async/with-dispatch executor
        (let [id (get-in request [:path-params :id])
              id (coerce-id id)
              obj  (sto/get-object storage id)]
          (if obj
            (serve-object cfg obj)
            {:status 404 :body ""})))
      (p/then respond)
      (p/catch raise)))

(defn file-objects-handler
  [cfg request respond raise]
  (-> (generic-handler cfg request :media-id)
      (p/then respond)
      (p/catch raise)))

(defn file-thumbnails-handler
  [cfg request respond raise]
  (-> (generic-handler cfg request #(or (:thumbnail-id %) (:media-id %)))
      (p/then respond)
      (p/catch raise)))

;; --- Initialization

(s/def ::storage some?)
(s/def ::assets-path ::us/string)
(s/def ::cache-max-age ::dt/duration)
(s/def ::signature-max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handlers [_]
  (s/keys :req-un [::storage
                   ::wrk/executor
                   ::mtx/metrics
                   ::assets-path
                   ::cache-max-age
                   ::signature-max-age]))

(defmethod ig/init-key ::handlers
  [_ cfg]
  {:objects-handler (partial objects-handler cfg)
   :file-objects-handler (partial file-objects-handler cfg)
   :file-thumbnails-handler (partial file-thumbnails-handler cfg)})

