;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [datoteka.core :as fs]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.images :as images]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.pages :as cp]
   [uxbox.tasks :as tasks]
   [uxbox.services.queries.files :as files]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.projects :as proj]
   [uxbox.services.mutations.images :as imgs]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)

;; --- Mutation: Create Project File

(declare create-file)
(declare create-page)

(s/def ::create-file
  (s/keys :req-un [::profile-id ::name ::project-id]
          :opt-un [::id]))

(sm/defmutation ::create-file
  [{:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn db/pool]
    (p/let [file (create-file conn params)
            page (create-page conn (assoc params :file-id (:id file)))]
      (assoc file :pages [(:id page)]))))

(def ^:private sql:create-file
  "insert into file (id, project_id, name)
   values ($1, $2, $3) returning *")

(def ^:private sql:create-file-profile
  "insert into file_profile_rel (profile_id, file_id, is_owner, is_admin, can_edit)
   values ($1, $2, true, true, true) returning *")

(def ^:private sql:create-page
  "insert into page (id, file_id, name, ordering, data)
   values ($1, $2, $3, $4, $5) returning id")

(defn- create-file-profile
  [conn {:keys [profile-id file-id] :as params}]
  (db/query-one conn [sql:create-file-profile profile-id file-id]))

(defn- create-file
  [conn {:keys [id profile-id name project-id] :as params}]
  (p/let [id   (or id (uuid/next))
          file (db/query-one conn [sql:create-file id project-id name])]
    (->> (assoc params :file-id id)
         (create-file-profile conn))
    file))

(defn- create-page
  [conn {:keys [file-id] :as params}]
  (let [id  (uuid/next)
        name "Page 1"
        data (blob/encode cp/default-page-data)]
    (db/query-one conn [sql:create-page id file-id name 1 data])))



;; --- Mutation: Rename File

(declare rename-file)

(s/def ::rename-file
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-file
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)
    (rename-file conn params)))

(def ^:private sql:rename-file
  "update file
      set name = $2
    where id = $1
      and deleted_at is null
     returning *")

(defn- rename-file
  [conn {:keys [id name] :as params}]
  (db/query-one conn [sql:rename-file id name]))


;; --- Mutation: Delete Project File

(declare mark-file-deleted)

(s/def ::delete-file
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-file
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (tasks/schedule! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :file}})

    (mark-file-deleted conn params)))

(def ^:private sql:mark-file-deleted
  "update file
      set deleted_at = clock_timestamp()
    where id = $1
      and deleted_at is null")

(defn mark-file-deleted
  [conn {:keys [id] :as params}]
  (-> (db/query-one conn [sql:mark-file-deleted id])
      (p/then' su/constantly-nil)))


;; --- Mutation: Upload File Image

(declare create-file-image)

(s/def ::file-id ::us/uuid)
(s/def ::content ::imgs/upload)

(s/def ::upload-file-image
  (s/keys :req-un [::profile-id ::file-id ::name ::content]
          :opt-un [::id]))

(sm/defmutation ::upload-file-image
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (create-file-image conn params)))

(def ^:private sql:insert-file-image
  "insert into file_image
     (file_id, name, path, width, height, mtype,
      thumb_path, thumb_width, thumb_height,
      thumb_quality, thumb_mtype)
   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
   returning *")

(defn- create-file-image
  [conn {:keys [content file-id name] :as params}]
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

(declare copy-image)
(declare import-image-to-file)

(s/def ::import-image-to-file
  (s/keys :req-un [::image-id ::file-id ::profile-id]))

(def ^:private sql:select-image-by-id
  "select img.* from image as img where id=$1")

(sm/defmutation ::import-image-to-file
  [{:keys [image-id file-id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (import-image-to-file conn params)))

(defn- import-image-to-file
  [conn {:keys [image-id file-id] :as params}]
  (p/let [image (-> (db/query-one conn [sql:select-image-by-id image-id])
                    (p/then' su/raise-not-found-if-nil))
          image-path (copy-image (:path image))
          thumb-path (copy-image (:thumb-path image))
          sqlv [sql:insert-file-image
                file-id
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
        (p/then' #(images/resolve-urls % :thumb-path :thumb-uri)))))

(defn- copy-image
  [path]
  (vu/blocking
   (let [image-path (ust/lookup media/media-storage path)]
     (ust/save! media/media-storage (fs/name image-path) image-path))))
