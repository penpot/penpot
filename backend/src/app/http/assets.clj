;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.assets
  "Assets related handlers."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.db :as db]
   [app.storage :as sto]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [yetti.response :as yrs]))

(def ^:private cache-max-age
  (dt/duration {:hours 24}))

(def ^:private signature-max-age
  (dt/duration {:hours 24 :minutes 15}))

(defn get-id
  [{:keys [path-params]}]
  (if-let [id (some-> path-params :id d/parse-uuid)]
    (p/resolved id)
    (p/rejected (ex/error :type :not-found
                          :hunt "object not found"))))

(defn- get-file-media-object
  [pool id]
  (db/get pool :file-media-object {:id id}))

(defn- serve-object-from-s3
  [{:keys [::sto/storage] :as cfg} obj]
  (let [mdata (meta obj)]
    (->> (sto/get-object-url storage obj {:max-age signature-max-age})
         (p/fmap (fn [{:keys [host port] :as url}]
                   (let [headers {"location" (str url)
                                  "x-host"   (cond-> host port (str ":" port))
                                  "x-mtype"  (:content-type mdata)
                                  "cache-control" (str "max-age=" (inst-ms cache-max-age))}]
                     (yrs/response
                      :status  307
                      :headers headers)))))))

(defn- serve-object-from-fs
  [{:keys [::path]} obj]
  (let [purl    (u/join (u/uri path)
                        (sto/object->relative-path obj))
        mdata   (meta obj)
        headers {"x-accel-redirect" (:path purl)
                 "content-type" (:content-type mdata)
                 "cache-control" (str "max-age=" (inst-ms cache-max-age))}]
    (p/resolved
     (yrs/response :status 204 :headers headers))))

(defn- serve-object
  "Helper function that returns the appropriate response depending on
  the storage object backend type."
  [{:keys [::sto/storage] :as cfg} {:keys [backend] :as obj}]
  (let [backend (sto/resolve-backend storage backend)]
    (case (::sto/type backend)
      :s3 (serve-object-from-s3 cfg obj)
      :fs (serve-object-from-fs cfg obj))))

(defn objects-handler
  "Handler that servers storage objects by id."
  [{:keys [::sto/storage ::wrk/executor] :as cfg} request respond raise]
  (->> (get-id request)
       (p/mcat executor (fn [id] (sto/get-object storage id)))
       (p/mcat executor (fn [obj]
                          (if (some? obj)
                            (serve-object cfg obj)
                            (p/resolved (yrs/response 404)))))
       (p/fnly executor (fn [result cause]
                          (if cause (raise cause) (respond result))))))

(defn- generic-handler
  "A generic handler helper/common code for file-media based handlers."
  [{:keys [::sto/storage ::wrk/executor] :as cfg} request kf]
  (let [pool (::db/pool storage)]
    (->> (get-id request)
         (p/fmap executor (fn [id] (get-file-media-object pool id)))
         (p/mcat executor (fn [mobj] (sto/get-object storage (kf mobj))))
         (p/mcat executor (fn [sobj]
                            (if sobj
                              (serve-object cfg sobj)
                              (p/resolved (yrs/response 404))))))))

(defn file-objects-handler
  "Handler that serves storage objects by file media id."
  [cfg request respond raise]
  (->> (generic-handler cfg request :media-id)
       (p/fnly (fn [result cause]
                 (if cause (raise cause) (respond result))))))

(defn file-thumbnails-handler
  "Handler that serves storage objects by thumbnail-id and quick
  fallback to file-media-id if no thumbnail is available."
  [cfg request respond raise]
  (->> (generic-handler cfg request #(or (:thumbnail-id %) (:media-id %)))
       (p/fnly (fn [result cause]
                 (if cause (raise cause) (respond result))))))

;; --- Initialization

(s/def ::path ::us/string)
(s/def ::routes vector?)

(defmethod ig/pre-init-spec ::routes [_]
  (s/keys :req [::sto/storage ::wrk/executor  ::path]))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/assets"
   ["/by-id/:id" {:handler (partial objects-handler cfg)}]
   ["/by-file-media-id/:id" {:handler (partial file-objects-handler cfg)}]
   ["/by-file-media-id/:id/thumbnail" {:handler (partial file-thumbnails-handler cfg)}]])
