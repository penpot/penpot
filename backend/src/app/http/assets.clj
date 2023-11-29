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
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [ring.response :as-alias rres]))

(def ^:private cache-max-age
  (dt/duration {:hours 24}))

(def ^:private signature-max-age
  (dt/duration {:hours 24 :minutes 15}))

(defn get-id
  [{:keys [path-params]}]
  (or (some-> path-params :id d/parse-uuid)
      (ex/raise :type :not-found
                :hunt "object not found")))

(defn- get-file-media-object
  [pool id]
  (db/get pool :file-media-object {:id id}))

(defn- serve-object-from-s3
  [{:keys [::sto/storage] :as cfg} obj]
  (let [{:keys [host port] :as url} (sto/get-object-url storage obj {:max-age signature-max-age})]
    {::rres/status  307
     ::rres/headers {"location" (str url)
                    "x-host"   (cond-> host port (str ":" port))
                    "x-mtype"  (-> obj meta :content-type)
                    "cache-control" (str "max-age=" (inst-ms cache-max-age))}}))

(defn- serve-object-from-fs
  [{:keys [::path]} obj]
  (let [purl    (u/join (u/uri path)
                        (sto/object->relative-path obj))
        mdata   (meta obj)
        headers {"x-accel-redirect" (:path purl)
                 "content-type" (:content-type mdata)
                 "cache-control" (str "max-age=" (inst-ms cache-max-age))}]
    {::rres/status 204
     ::rres/headers headers}))

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
  [{:keys [::sto/storage] :as cfg} request]
  (let [id  (get-id request)
        obj (sto/get-object storage id)]
    (if obj
      (serve-object cfg obj)
      {::rres/status 404})))

(defn- generic-handler
  "A generic handler helper/common code for file-media based handlers."
  [{:keys [::sto/storage] :as cfg} request kf]
  (let [pool (::db/pool storage)
        id   (get-id request)
        mobj (get-file-media-object pool id)
        sobj (sto/get-object storage (kf mobj))]
    (if sobj
      (serve-object cfg sobj)
      {::rres/status 404})))

(defn file-objects-handler
  "Handler that serves storage objects by file media id."
  [cfg request]
  (generic-handler cfg request :media-id))

(defn file-thumbnails-handler
  "Handler that serves storage objects by thumbnail-id and quick
  fallback to file-media-id if no thumbnail is available."
  [cfg request]
  (generic-handler cfg request #(or (:thumbnail-id %) (:media-id %))))

;; --- Initialization

(s/def ::path ::us/string)
(s/def ::routes vector?)

(defmethod ig/pre-init-spec ::routes [_]
  (s/keys :req [::sto/storage  ::path]))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/assets"
   ["/by-id/:id" {:handler (partial objects-handler cfg)}]
   ["/by-file-media-id/:id" {:handler (partial file-objects-handler cfg)}]
   ["/by-file-media-id/:id/thumbnail" {:handler (partial file-thumbnails-handler cfg)}]])
