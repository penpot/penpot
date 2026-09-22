;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.assets
  "Assets related handlers."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http.access-token :as actoken]
   [app.http.session :as session]
   [app.metrics :as mtx]
   [app.rpc.permissions :as perms]
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
    "file-data-fragment"
    "organization"})

(defn- public-bucket?
  [bucket]
  (or (contains? public-buckets bucket)
      ;; Dashboard file thumbnails become public when link previews
      ;; are enabled, so link preview crawlers can fetch them.
      (and (= "file-thumbnail" bucket)
           (contains? cf/flags :link-preview))))

(defn get-id
  [{:keys [path-params]}]
  (or (some-> path-params :id d/parse-uuid)
      (ex/raise :type :not-found
                :hint "object not found")))

(defn- get-share-id
  "Extract and validate the optional `share-id` query param. Returns a UUID
  or `nil` for missing/malformed values."
  [{:keys [query-params]}]
  (some-> query-params :share-id d/parse-uuid))

(defn- get-file-media-object
  [pool id]
  (db/get* pool :file-media-object {:id id} {::db/remove-deleted false}))

(defn- result-label
  [status]
  (let [code (long (or status 500))]
    (cond
      (<= 200 code 399) "served"
      (= code 401) "unauthorized"
      (= code 403) "unauthorized"
      (= code 404) "not-found"
      :else "error")))

(defn- emit-asset!
  "Record an asset request. `route` is the handler route, `obj` the resolved
  storage object (or nil when it could not be resolved). Never fails."
  [cfg route obj status]
  (try
    (when-let [metrics (::mtx/metrics cfg)]
      (mtx/run! metrics :id :storage-asset-requests :inc 1
                :labels [route
                         (mtx/label (some-> obj :backend) "unknown")
                         (mtx/label (some-> obj meta :bucket) "unknown")
                         (result-label status)]))
    (catch Throwable cause
      (l/dbg :hint "unable to record asset metric" :cause cause))))

(defn- serve-object-from-s3
  [{:keys [::sto/storage ::signature-max-age ::cache-max-age] :as cfg} obj]
  (let [sig-max-age (or signature-max-age default-signature-max-age)
        cch-max-age (or cache-max-age default-cache-max-age)
        bucket  (-> obj meta :bucket)
        public? (public-bucket? bucket)
        ;; The disposition is also signed into the presigned url: this
        ;; response is a redirect, so the header below applies to the
        ;; redirect itself and not to the bytes the client then fetches
        ;; from the object store.
        {:keys [host port] :as url} (sto/get-object-url storage obj
                                                        (cond-> {:max-age sig-max-age}
                                                          (not public?)
                                                          (assoc :content-disposition "attachment")))
        headers (cond-> {"location" (str url)
                         "x-host"   (cond-> host port (str ":" port))
                         "x-mtype"  (-> obj meta :content-type)
                         "cache-control" (str "max-age=" (inst-ms cch-max-age))}
                  (not public?)
                  (assoc "content-disposition" "attachment"))]
    {::yres/status  307
     ::yres/headers headers}))

(defn- serve-object-from-fs
  [{:keys [::path ::cache-max-age]} obj]
  (let [cch-max-age (or cache-max-age default-cache-max-age)
        purl    (u/join (u/uri path)
                        (sto/object->relative-path obj))
        mdata   (meta obj)
        bucket  (:bucket mdata)
        headers (cond-> {"x-accel-redirect" (:path purl)
                         "content-type" (:content-type mdata)
                         "cache-control" (str "max-age=" (inst-ms cch-max-age))}
                  (not (public-bucket? bucket))
                  (assoc "content-disposition" "attachment"))]
    {::yres/status 204
     ::yres/headers headers}))

(defn- serve-object
  "Helper function that returns the appropriate response depending on
  the storage object backend type."
  [cfg {:keys [backend] :as obj}]
  (case backend
    (:s3 :assets-s3) (serve-object-from-s3 cfg obj)
    (:fs :assets-fs) (serve-object-from-fs cfg obj)
    (ex/raise :type :internal
              :hint "unknown storage backend"
              :backend backend)))

(defn- requires-auth?
  "Check if the storage object requires authentication based on its bucket."
  [obj]
  (let [bucket (-> obj meta :bucket)]
    (not (public-bucket? bucket))))

(defn- request-profile-id
  "Extract the authenticated profile-id from the request."
  [request]
  (or (::session/profile-id request)
      (::actoken/profile-id request)))

(defn- authenticated?
  "Check if the request has an authenticated profile, either via session
   or access token."
  [request]
  (some? (request-profile-id request)))

(defn- tempfile-owner-match?
  "Check if the request's profile-id matches the tempfile's stored owner.
   Returns true if no profile-id was stored (legacy objects)."
  [obj request]
  (let [stored-profile-id (:profile-id (meta obj))
        request-profile-id (request-profile-id request)]
    (or (nil? stored-profile-id)
        (= stored-profile-id request-profile-id))))

(defn- serve-object-measured
  "Serve `obj`, recording one asset metric per outcome. A failure is
  counted and then rethrown: never swallowed, never counted twice."
  [cfg route obj]
  (try
    (let [response (serve-object cfg obj)]
      (emit-asset! cfg route obj (::yres/status response))
      response)
    (catch Throwable cause
      (emit-asset! cfg route obj 500)
      (throw cause))))

(defn objects-handler
  "Handler that serves storage objects by id.
   For non-public buckets (e.g. profile), requires authentication
   via session cookie or access token.
   For tempfile bucket, also requires ownership (profile-id match)."
  [{:keys [::sto/storage] :as cfg} request]
  (let [id  (get-id request)
        obj (sto/get-object storage id)]
    (cond
      (nil? obj)
      (do
        (emit-asset! cfg "by-id" nil 404)
        {::yres/status 404})

      (and (requires-auth? obj)
           (not (authenticated? request)))
      (do
        (emit-asset! cfg "by-id" obj 401)
        {::yres/status 401})

      ;; The response stays 404 to avoid leaking existence, but the
      ;; metric records the internal 401 outcome.
      (and (= (-> obj meta :bucket) sto/tempfile-bucket)
           (not (tempfile-owner-match? obj request)))
      (do
        (emit-asset! cfg "by-id" obj 401)
        {::yres/status 404})

      :else
      (serve-object-measured cfg "by-id" obj))))

(defn- generic-handler
  "A generic handler helper/common code for file-media based handlers."
  [{:keys [::sto/storage] :as cfg} request route kf]
  (let [pool       (::db/pool storage)
        id         (get-id request)
        mobj       (get-file-media-object pool id)]
    (if (nil? mobj)
      (do
        (emit-asset! cfg route nil 404)
        {::yres/status 404})
      (let [file-id    (:file-id mobj)
            profile-id (or (::session/profile-id request)
                           (::actoken/profile-id request))
            share-id   (get-share-id request)
            perms      (perms/get-file-read-permissions pool profile-id file-id share-id)]
        (if-not (:can-read perms)
          ;; The response stays 404 to avoid leaking existence, but the
          ;; metric records the internal 403 outcome.
          (do
            (emit-asset! cfg route nil 403)
            {::yres/status 404})
          (let [sobj (sto/get-object storage (kf mobj))]
            (if sobj
              (serve-object-measured cfg route sobj)
              (do
                (emit-asset! cfg route nil 404)
                {::yres/status 404}))))))))

(defn file-objects-handler
  "Handler that serves storage objects by file media id."
  [cfg request]
  (generic-handler cfg request "by-file-media-id" :media-id))

(defn file-thumbnails-handler
  "Handler that serves storage objects by thumbnail-id and quick
  fallback to file-media-id if no thumbnail is available."
  [cfg request]
  (generic-handler cfg request "thumbnail" #(or (:thumbnail-id %) (:media-id %))))

;; --- Initialization

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage instance")
  (assert (session/manager? (::session/manager params)) "expected valid session manager")
  (assert (string? (::path params))))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/assets" {:middleware [[session/authz cfg]
                           [actoken/authz cfg]]}
   ["/by-id/:id" {:handler (partial objects-handler cfg)}]
   ["/by-file-media-id/:id" {:handler (partial file-objects-handler cfg)}]
   ["/by-file-media-id/:id/thumbnail" {:handler (partial file-thumbnails-handler cfg)}]])
