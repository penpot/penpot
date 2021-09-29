;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.cli.migrate-media
  (:require
   [app.common.logging :as l]
   [app.common.media :as cm]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as main]
   [app.storage :as sto]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [integrant.core :as ig]))

(declare migrate-profiles)
(declare migrate-teams)
(declare migrate-file-media)

(defn run-in-system
  [system]
  (db/with-atomic [conn (:app.db/pool system)]
    (let [system (assoc system ::conn conn)]
      (migrate-profiles system)
      (migrate-teams system)
      (migrate-file-media system))
    system))

(defn run
  []
  (let [config (select-keys main/system-config
                            [:app.db/pool
                             :app.migrations/migrations
                             :app.metrics/metrics
                             :app.storage.s3/backend
                             :app.storage.db/backend
                             :app.storage.fs/backend
                             :app.storage/storage])]
    (ig/load-namespaces config)
    (try
      (-> (ig/prep config)
          (ig/init)
          (run-in-system)
          (ig/halt!))
      (catch Exception e
        (l/error :hint "unhandled exception" :cause e)))))


;; --- IMPL

(defn migrate-profiles
  [{:keys [::conn] :as system}]
  (letfn [(retrieve-profiles [conn]
            (->> (db/exec! conn ["select * from profile"])
                 (filter #(not (str/empty? (:photo %))))
                 (seq)))]
    (let [base    (fs/path (cf/get :storage-fs-old-directory))
          storage (-> (:app.storage/storage system)
                      (assoc :conn conn))]
      (doseq [profile (retrieve-profiles conn)]
        (let [path  (fs/path (:photo profile))
              full  (-> (fs/join base path)
                        (fs/normalize))
              ext   (fs/ext path)
              mtype (cm/format->mtype (keyword ext))
              obj   (sto/put-object storage {:content (sto/content full)
                                             :content-type mtype})]
          (db/update! conn :profile
                      {:photo-id (:id obj)}
                      {:id (:id profile)}))))))

(defn migrate-teams
  [{:keys [::conn] :as system}]
  (letfn [(retrieve-teams [conn]
            (->> (db/exec! conn ["select * from team"])
                 (filter #(not (str/empty? (:photo %))))
                 (seq)))]
    (let [base    (fs/path (cf/get :storage-fs-old-directory))
          storage (-> (:app.storage/storage system)
                      (assoc :conn conn))]
      (doseq [team (retrieve-teams conn)]
        (let [path  (fs/path (:photo team))
              full  (-> (fs/join base path)
                        (fs/normalize))
              ext   (fs/ext path)
              mtype (cm/format->mtype (keyword ext))
              obj   (sto/put-object storage {:content (sto/content full)
                                             :content-type mtype})]
          (db/update! conn :team
                      {:photo-id (:id obj)}
                      {:id (:id team)}))))))



(defn migrate-file-media
  [{:keys [::conn] :as system}]
  (letfn [(retrieve-media-objects [conn]
            (->> (db/exec! conn ["select fmo.id, fmo.path, fth.path as thumbnail_path
                                    from file_media_object as fmo
                                    join file_media_thumbnail as fth on (fth.media_object_id = fmo.id)"])
                 (seq)))]
    (let [base    (fs/path (cf/get :storage-fs-old-directory))
          storage (-> (:app.storage/storage system)
                      (assoc :conn conn))]
      (doseq [mobj (retrieve-media-objects conn)]
        (let [img-path  (fs/path (:path mobj))
              thm-path  (fs/path (:thumbnail-path mobj))
              img-path  (-> (fs/join base img-path)
                            (fs/normalize))
              thm-path  (-> (fs/join base thm-path)
                            (fs/normalize))
              img-ext   (fs/ext img-path)
              thm-ext   (fs/ext thm-path)

              img-mtype (cm/format->mtype (keyword img-ext))
              thm-mtype (cm/format->mtype (keyword thm-ext))

              img-obj   (sto/put-object storage {:content (sto/content img-path)
                                                 :content-type img-mtype})
              thm-obj   (sto/put-object storage {:content (sto/content thm-path)
                                                 :content-type thm-mtype})]

          (db/update! conn :file-media-object
                      {:media-id (:id img-obj)
                       :thumbnail-id (:id thm-obj)}
                      {:id (:id mobj)}))))))
