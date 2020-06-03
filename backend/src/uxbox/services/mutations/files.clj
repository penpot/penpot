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
   [datoteka.core :as fs]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.mutations.images :as imgs]
   [uxbox.services.mutations.projects :as proj]
   [uxbox.services.queries.files :as files]
   [uxbox.tasks :as tasks]
   [uxbox.util.blob :as blob]
   [uxbox.util.storage :as ust]
   [uxbox.util.time :as dt]))

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
    (let [file (create-file conn params)
          page (create-page conn (assoc params :file-id (:id file)))]
      (assoc file :pages [(:id page)]))))

(defn- create-file-profile
  [conn {:keys [profile-id file-id] :as params}]
  (db/insert! conn :file-profile-rel
              {:profile-id profile-id
               :file-id file-id
               :is-owner true
               :is-admin true
               :can-edit true}))

(defn- create-file
  [conn {:keys [id profile-id name project-id] :as params}]
  (let [id   (or id (uuid/next))
        file (db/insert! conn :file {:id id :project-id project-id :name name})]
    (->> (assoc params :file-id id)
         (create-file-profile conn))
    file))

(defn- create-page
  [conn {:keys [file-id] :as params}]
  (let [id  (uuid/next)]
    (db/insert! conn :page
                {:id id
                 :file-id file-id
                 :name "Page 1"
                 :ordering 1
                 :data (blob/encode cp/default-page-data)})))


;; --- Mutation: Rename File

(declare rename-file)

(s/def ::rename-file
  (s/keys :req-un [::profile-id ::name ::id]))

(sm/defmutation ::rename-file
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)
    (rename-file conn params)))

(defn- rename-file
  [conn {:keys [id name] :as params}]
  (db/update! conn :file
              {:name name}
              {:id id}))


;; --- Mutation: Delete Project File

(declare mark-file-deleted)

(s/def ::delete-file
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-file
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (files/check-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (tasks/submit! conn {:name "delete-object"
                         :delay cfg/default-deletion-delay
                         :props {:id id :type :file}})

    (mark-file-deleted conn params)))

(def ^:private sql:mark-file-deleted
  "update file
      set deleted_at = clock_timestamp()
    where id = ?
      and deleted_at is null")

(defn mark-file-deleted
  [conn {:keys [id] :as params}]
  (db/update! conn :file
              {:deleted-at (dt/now)}
              {:id id})
  nil)


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
  (when-not (imgs/valid-image-types? (:content-type content))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))

  (let [image-opts (images/info (:content-type content) (:tempfile content))
        image-path (imgs/persist-image-on-fs content)
        thumb-opts imgs/thumbnail-options
        thumb-path (imgs/persist-image-thumbnail-on-fs thumb-opts image-path)]
    (-> (db/insert! conn :file-image
                    {:file-id file-id
                     :name name
                     :path (str image-path)
                     :width (:width image-opts)
                     :height (:height image-opts)
                     :mtype  (:content-type content)
                     :thumb-path (str thumb-path)
                     :thumb-width (:width thumb-opts)
                     :thumb-height (:height thumb-opts)
                     :thumb-quality (:quality thumb-opts)
                     :thumb-mtype (images/format->mtype (:format thumb-opts))})
        (images/resolve-urls :path :uri)
        (images/resolve-urls :thumb-path :thumb-uri))))


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
  (let [image      (db/get-by-id conn :image image-id)
        image-path (copy-image (:path image))
        thumb-path (copy-image (:thumb-path image))]

    (-> (db/insert! conn :file-image
                    {:file-id file-id
                     :name (:name image)
                     :path (str image-path)
                     :width (:width image)
                     :height (:height image)
                     :mtype  (:mtype image)
                     :thumb-path (str thumb-path)
                     :thumb-width (:thumb-width image)
                     :thumb-height (:thumb-height image)
                     :thumb-quality (:thumb-quality image)
                     :thumb-mtype (:thumb-mtype image)})
        (images/resolve-urls :path :uri)
        (images/resolve-urls :thumb-path :thumb-uri))))

(defn- copy-image
  [path]
  (let [image-path (ust/lookup media/media-storage path)]
    (ust/save! media/media-storage (fs/name image-path) image-path)))
