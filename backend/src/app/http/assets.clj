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
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.db :as db]
   [app.http.session :as session]
   [app.storage :as sto]
   [integrant.core :as ig]
   [yetti.response :as-alias yres]))

(def ^:private default-cache-max-age
  (ct/duration {:hours 24}))

(def ^:private default-signature-max-age
  (ct/duration {:hours 24 :minutes 15}))

;; Buckets that are legitimately public and do not require authentication.
;; These are used by public shared board viewing, profile photos in UI,
;; and embedded export/binfile flows.
(def ^:private public-buckets
  #{"file-media-object"
    "file-object-thumbnail"
    "team-font-variant"
    "file-data-fragment"})

(defn get-id
  [{:keys [path-params]}]
  (or (some-> path-params :id d/parse-uuid)
      (ex/raise :type :not-found
                :hint "object not found")))

(defn- get-file-media-object
  [pool id]
  (db/get pool :file-media-object {:id id} {::db/remove-deleted false}))

(defn- serve-object-from-s3
  [{:keys [::sto/storage ::signature-max-age ::cache-max-age] :as cfg} obj]
  (let [sig-max-age (or signature-max-age default-signature-max-age)
        cch-max-age (or cache-max-age default-cache-max-age)
        {:keys [host port] :as url} (sto/get-object-url storage obj {:max-age sig-max-age})]
    {::yres/status  307
     ::yres/headers {"location" (str url)
                     "x-host"   (cond-> host port (str ":" port))
                     "x-mtype"  (-> obj meta :content-type)
                     "cache-control" (str "max-age=" (inst-ms cch-max-age))}}))

(defn- serve-object-from-fs
  [{:keys [::path ::cache-max-age]} obj]
  (let [cch-max-age (or cache-max-age default-cache-max-age)
        purl    (u/join (u/uri path)
                        (sto/object->relative-path obj))
        mdata   (meta obj)
        headers {"x-accel-redirect" (:path purl)
                 "content-type" (:content-type mdata)
                 "cache-control" (str "max-age=" (inst-ms cch-max-age))}]
    {::yres/status 204
     ::yres/headers headers}))

(defn- serve-object
  "Helper function that returns the appropriate response depending on
  the storage object backend type."
  [cfg {:keys [backend] :as obj}]
  (case backend
    (:s3 :assets-s3) (serve-object-from-s3 cfg obj)
    (:fs :assets-fs) (serve-object-from-fs cfg obj)))

(defn- requires-auth?
  "Check if the storage object requires authentication based on its bucket."
  [obj]
  (let [bucket (-> obj meta :bucket)]
    (not (contains? public-buckets bucket))))

(defn objects-handler
  "Handler that serves storage objects by id.
   For non-public buckets (e.g. profile), requires an authenticated session."
  [{:keys [::sto/storage] :as cfg} request]
  (let [id  (get-id request)
        obj (sto/get-object storage id)]
    (cond
      (nil? obj)
      {::yres/status 404}

      (and (requires-auth? obj)
           (nil? (::session/profile-id request)))
      {::yres/status 401}

      :else
      (serve-object cfg obj))))

(defn- generic-handler
  "A generic handler helper/common code for file-media based handlers."
  [{:keys [::sto/storage] :as cfg} request kf]
  (let [pool (::db/pool storage)
        id   (get-id request)
        mobj (get-file-media-object pool id)
        sobj (sto/get-object storage (kf mobj))]
    (if sobj
      (serve-object cfg sobj)
      {::yres/status 404})))

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

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage instance")
  (assert (session/manager? (::session/manager params)) "expected valid session manager")
  (assert (string? (::path params))))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/assets" {:middleware [[session/authz cfg]]}
   ["/by-id/:id" {:handler (partial objects-handler cfg)}]
   ["/by-file-media-id/:id" {:handler (partial file-objects-handler cfg)}]
   ["/by-file-media-id/:id/thumbnail" {:handler (partial file-thumbnails-handler cfg)}]])
