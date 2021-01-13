;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http.assets
  "Assets related handlers."
  (:require
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.storage :as sto]
   [app.db :as db]
   [app.util.time :as dt]))

(def ^:private cache-max-age
  (dt/duration {:hours 24}))

(def ^:private signature-max-age
  (dt/duration {:hours 24 :minutes 15}))

(defn- serve-object
  [storage obj]
  (let [mdata   (meta obj)
        backend (sto/resolve-backend storage (:backend obj))]
    (case (:type backend)
      :db
      {:status 200
       :headers {"content-type" (:content-type mdata)
                 "cache-control" (str "max-age=" (inst-ms cache-max-age))}
       :body (sto/get-object-data storage obj)}

      :s3
      (let [url (sto/get-object-url storage obj {:max-age signature-max-age})]
        {:status 307
         :headers {"location" (str url)
                   "x-host"   (:host url)
                   "cache-control" (str "max-age=" (inst-ms cache-max-age))}
         :body ""})

      :fs
      (let [url (sto/get-object-url storage obj)]
        {:status 204
         :headers {"x-accel-redirect" (:path url)
                   "content-type" (:content-type mdata)
                   "cache-control" (str "max-age=" (inst-ms cache-max-age))
                   }
         :body ""}))))

(defn- generic-handler
  [{:keys [pool] :as storage} request id]
  (with-open [conn (db/open pool)]
    (let [storage (assoc storage :conn conn)
          obj     (sto/get-object storage id)]
      (if obj
        (serve-object storage obj)
        {:status 404 :body ""}))))

(defn coerce-id
  [id]
  (let [res (us/uuid-conformer id)]
    (when-not (uuid? res)
      (ex/raise :type :not-found
                :hint "object not found"))
    res))

(defn- get-file-media-object
  [conn id]
  (let [id   (coerce-id id)
        mobj (db/exec-one! conn ["select * from file_media_object where id=?" id])]
    (when-not mobj
      (ex/raise :type :not-found
                :hint "object does not found"))
      mobj))

(defn objects-handler
  [storage request]
  (let [id (get-in request [:path-params :id])]
    (generic-handler storage request (coerce-id id))))

(defn file-objects-handler
  [{:keys [pool] :as storage} request]
  (let [id   (get-in request [:path-params :id])
        mobj (get-file-media-object pool id)]
    (generic-handler storage request (:media-id mobj))))

(defn file-thumbnails-handler
  [{:keys [pool] :as storage} request]
  (let [id   (get-in request [:path-params :id])
        mobj (get-file-media-object pool id)]
    (generic-handler storage request (or (:thumbnail-id mobj) (:media-id mobj)))))
