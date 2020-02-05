;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.project-files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.pages :as cp]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.projects :as proj]
   [uxbox.services.mutations.images :as imgs]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::user ::us/uuid)
(s/def ::project-id ::us/uuid)

;; --- Permissions Checks

;; A query that returns all (not-equal) user assignations for a
;; requested file (project level and file level).

;; Is important having the condition of user_id in the join and not in
;; where clause because we need all results independently if value is
;; true, false or null; with that, the empty result means there are no
;; file found.

(def ^:private sql:file-permissions
  "select pf.id,
          pfu.can_edit as can_edit
     from project_files as pf
     left join project_file_users as pfu
       on (pfu.file_id = pf.id and pfu.user_id = $1)
    where pf.id = $2
   union all
   select pf.id,
          pu.can_edit as can_edit
     from project_files as pf
     left join project_users as pu
       on (pf.project_id = pu.project_id and pu.user_id = $1)
    where pf.id = $2")

(defn check-edition-permissions!
  [conn user file-id]
  (-> (db/query conn [sql:file-permissions user file-id])
      (p/then' seq)
      (p/then' su/raise-not-found-if-nil)
      (p/then' (fn [rows]
                 (when-not (some :can-edit rows)
                   (ex/raise :type :validation
                             :code :not-authorized))))))

;; --- Mutation: Create Project File

(declare create-file)
(declare create-page)

(s/def ::create-project-file
  (s/keys :req-un [::user ::name ::project-id]
          :opt-un [::id]))

(sm/defmutation ::create-project-file
  [{:keys [user project-id] :as params}]
  (db/with-atomic [conn db/pool]
    (proj/check-edition-permissions! conn user project-id)
    (p/let [file (create-file conn params)
            page (create-page conn (assoc params :file-id (:id file)))]
      (assoc file :pages [(:id page)]))))

(defn create-file
  [conn {:keys [id user name project-id] :as params}]
  (let [id (or id (uuid/next))
        sql "insert into project_files (id, user_id, project_id, name)
             values ($1, $2, $3, $4) returning *"]
    (db/query-one conn [sql id user project-id name])))

(defn- create-page
  "Creates an initial page for the file."
  [conn {:keys [user file-id] :as params}]
  (let [id  (uuid/next)
        name "Page 1"
        data (blob/encode cp/default-page-data)
        sql "insert into project_pages (id, user_id, file_id, name, version,
                                        ordering, data)
             values ($1, $2, $3, $4, 0, 1, $5) returning id"]
    (db/query-one conn [sql id user file-id name data])))

;; --- Mutation: Rename File

(declare rename-file)

(s/def ::rename-project-file
  (s/keys :req-un [::user ::name ::id]))

(sm/defmutation ::rename-project-file
  [{:keys [id user] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn user id)
    (rename-file conn params)))

(def sql:rename-file
  "update project_files
      set name = $2
    where id = $1
      and deleted_at is null")

(defn- rename-file
  [conn {:keys [id name] :as params}]
  (let [sql sql:rename-file]
    (-> (db/query-one conn [sql id name])
        (p/then' su/constantly-nil))))


;; --- Mutation: Delete Project File

(declare delete-file)

(s/def ::delete-project-file
  (s/keys :req-un [::id ::user]))

(sm/defmutation ::delete-project-file
  [{:keys [id user] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn user id)
    (delete-file conn params)))

(def ^:private sql:delete-file
  "update project_files
      set deleted_at = clock_timestamp()
    where id = $1
      and deleted_at is null")

(defn delete-file
  [conn {:keys [id] :as params}]
  (let [sql sql:delete-file]
    (-> (db/query-one conn [sql id])
        (p/then' su/constantly-nil))))

;; --- Mutation: Upload File Image

(s/def ::file-id ::us/uuid)
(s/def ::content ::imgs/upload)

(s/def ::upload-project-file-image
  (s/keys :req-un [::user ::file-id ::name ::content]
          :opt-un [::id]))

(declare create-file-image)

(sm/defmutation ::upload-project-file-image
  [{:keys [user file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn user file-id)
    (create-file-image conn params)))

(def ^:private
  sql:insert-file-image
  "insert into project_file_images
     (file_id, user_id, name, path, width, height, mtype,
      thumb_path, thumb_width, thumb_height, thumb_quality, thumb_mtype)
   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
   returning *")

(defn- create-file-image
  [conn {:keys [content file-id user name] :as params}]
  (when-not (imgs/valid-image-types? (:mtype content))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))

  (p/let [image-opts (vu/blocking (images/info (:path content)))
          image-path (imgs/persist-image-on-fs content)
          thumb-opts imgs/thumbnail-options
          thumb-path (imgs/persist-image-thumbnail-on-fs thumb-opts image-path)

          sqlv [sql:insert-file-image
                file-id
                user
                name
                (str image-path)
                (:width image-opts)
                (:height image-opts)
                (:mtype content)
                (str thumb-path)
                (:width thumb-opts)
                (:height thumb-opts)
                (:quality thumb-opts)
                (images/format->mtype (:format thumb-opts))]]
    (-> (db/query-one db/pool sqlv)
        (p/then' #(images/resolve-urls % :path :uri))
        (p/then' #(images/resolve-urls % :thumb-path :thumb-uri)))))

;; --- Mutation: Import from collection

(declare copy-image!)

(s/def ::import-image-to-file
  (s/keys :req-un [::image-id ::file-id ::user]))

(def ^:private sql:select-image-by-id
  "select img.* from images as img where id=$1")

(sm/defmutation ::import-image-to-file
  [{:keys [image-id file-id user]}]
  (db/with-atomic [conn db/pool]
    (p/let [image (-> (db/query-one conn [sql:select-image-by-id image-id])
                      (p/then' su/raise-not-found-if-nil))
            image-path (copy-image! (:path image))
            thumb-path (copy-image! (:thumb-path image))
            sqlv [sql:insert-file-image
                  file-id
                  user
                  (:name image)
                  (str image-path)
                  (:width image)
                  (:height image)
                  (:mtype image)
                  (str thumb-path)
                  (:thumb-width image)
                  (:thumb-height image)
                  (:thumb-quality image)
                  (:thumb-mtype image)]]
      (-> (db/query-one db/pool sqlv)
          (p/then' #(images/resolve-urls % :path :uri))
          (p/then' #(images/resolve-urls % :thumb-path :thumb-uri))))))

(defn- copy-image!
  [path]
  (vu/blocking
   (let [image-path (ust/lookup media/media-storage path)]
     (ust/save! media/media-storage (fs/name image-path) image-path))))
