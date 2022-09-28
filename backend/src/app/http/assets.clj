;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.assets
  "Assets related handlers."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.response :as yrs]))

(def ^:private cache-max-age
  (dt/duration {:hours 24}))

(def ^:private signature-max-age
  (dt/duration {:hours 24 :minutes 15}))

(defn coerce-id
  [id]
  (let [res (parse-uuid id)]
    (when-not (uuid? res)
      (ex/raise :type :not-found
                :hint "object not found"))
    res))

(defn- get-file-media-object
  [{:keys [pool executor] :as storage} id]
  (px/with-dispatch executor
    (let [id   (coerce-id id)
          mobj (db/exec-one! pool ["select * from file_media_object where id=?" id])]
      (when-not mobj
        (ex/raise :type :not-found
                  :hint "object does not found"))
      mobj)))

(defn- serve-object
  "Helper function that returns the appropriate response depending on
  the storage object backend type."
  [{:keys [storage] :as cfg} obj]
  (let [mdata   (meta obj)
        backend (sto/resolve-backend storage (:backend obj))]
    (case (:type backend)
      :s3
      (p/let [{:keys [host port] :as url} (sto/get-object-url storage obj {:max-age signature-max-age})]
        (yrs/response :status  307
                      :headers {"location" (str url)
                                "x-host"   (cond-> host port (str ":" port))
                                "x-mtype"  (:content-type mdata)
                                "cache-control" (str "max-age=" (inst-ms cache-max-age))}))

      :fs
      (p/let [purl (u/uri (:assets-path cfg))
              purl (u/join purl (sto/object->relative-path obj))]
        (yrs/response :status  204
                      :headers {"x-accel-redirect" (:path purl)
                                "content-type" (:content-type mdata)
                                "cache-control" (str "max-age=" (inst-ms cache-max-age))})))))

(defn objects-handler
  "Handler that servers storage objects by id."
  [{:keys [storage executor] :as cfg} request respond raise]
  (-> (px/with-dispatch executor
        (p/let [id  (get-in request [:path-params :id])
                id  (coerce-id id)
                obj (sto/get-object storage id)]
          (if obj
            (serve-object cfg obj)
            (yrs/response 404))))

      (p/bind p/wrap)
      (p/then' respond)
      (p/catch raise)))

(defn- generic-handler
  "A generic handler helper/common code for file-media based handlers."
  [{:keys [storage] :as cfg} request kf]
  (p/let [id   (get-in request [:path-params :id])
          mobj (get-file-media-object storage id)
          obj  (sto/get-object storage (kf mobj))]
    (if obj
      (serve-object cfg obj)
      (yrs/response 404))))

(defn file-objects-handler
  "Handler that serves storage objects by file media id."
  [cfg request respond raise]
  (-> (generic-handler cfg request :media-id)
      (p/then respond)
      (p/catch raise)))

(defn file-thumbnails-handler
  "Handler that serves storage objects by thumbnail-id and quick
  fallback to file-media-id if no thumbnail is available."
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

