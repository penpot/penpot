;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.media
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
   [app.rpc.semaphore :as rsem]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.bytes :as bs]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [promesa.exec :as px]))

(def default-max-file-size (* 1024 1024 10)) ; 10 MiB

(def thumbnail-options
  {:width 100
   :height 100
   :quality 85
   :format :jpeg})

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)

;; --- Create File Media object (upload)

(declare create-file-media-object)
(declare select-file)

(s/def ::content ::media/upload)
(s/def ::is-local ::us/boolean)

(s/def ::upload-file-media-object
  (s/keys :req-un [::profile-id ::file-id ::is-local ::name ::content]
          :opt-un [::id]))

(sv/defmethod ::upload-file-media-object
  {::rsem/permits (cf/get :rpc-semaphore-permits-image)}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id content] :as params}]
  (let [file (select-file pool file-id)
        cfg  (update cfg :storage media/configure-assets-storage)]

    (teams/check-edition-permissions! pool profile-id (:team-id file))
    (media/validate-media-type! content)

    (when (> (:size content) (cf/get :media-max-file-size default-max-file-size))
      (ex/raise :type :restriction
                :code :media-max-file-size-reached
                :hint (str/ffmt "the uploaded file size % is greater than the maximum %"
                                (:size content)
                                default-max-file-size)))

    (create-file-media-object cfg params)))

(defn- big-enough-for-thumbnail?
  "Checks if the provided image info is big enough for
  create a separate thumbnail storage object."
  [info]
  (or (> (:width info) (:width thumbnail-options))
      (> (:height info) (:height thumbnail-options))))

(defn- svg-image?
  [info]
  (= (:mtype info) "image/svg+xml"))

;; NOTE: we use the `on conflict do update` instead of `do nothing`
;; because postgresql does not returns anything if no update is
;; performed, the `do update` does the trick.

(def sql:create-file-media-object
  "insert into file_media_object (id, file_id, is_local, name, media_id, thumbnail_id, width, height, mtype)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?)
       on conflict (id) do update set created_at=file_media_object.created_at
       returning *")

;; NOTE: the following function executes without a transaction, this
;; means that if something fails in the middle of this function, it
;; will probably leave leaked/unreferenced objects in the database and
;; probably in the storage layer. For handle possible object leakage,
;; we create all media objects marked as touched, this ensures that if
;; something fails, all leaked (already created storage objects) will
;; be eventually marked as deleted by the touched-gc task.
;;
;; The touched-gc task, performs periodic analisis of all touched
;; storage objects and check references of it. This is the reason why
;; `reference` metadata exists: it indicates the name of the table
;; witch holds the reference to storage object (it some kind of
;; inverse, soft referential integrity).

(defn create-file-media-object
  [{:keys [storage pool executors] :as cfg} {:keys [id file-id is-local name content] :as params}]
  (letfn [;; Function responsible to retrieve the file information, as
          ;; it is synchronous operation it should be wrapped into
          ;; with-dispatch macro.
          (get-info [content]
            (px/with-dispatch (:blocking executors)
              (media/run {:cmd :info :input content})))

          ;; Function responsible of calculating cryptographyc hash of
          ;; the provided data. Even though it uses the hight
          ;; performance BLAKE2b algorithm, we prefer to schedule it
          ;; to be executed on the blocking executor.
          (calculate-hash [data]
            (px/with-dispatch (:blocking executors)
              (sto/calculate-hash data)))

          ;; Function responsible of generating thumnail. As it is synchronous
          ;; opetation, it should be wrapped into with-dispatch macro
          (generate-thumbnail [info]
            (px/with-dispatch (:blocking executors)
              (media/run (assoc thumbnail-options
                                :cmd :generic-thumbnail
                                :input info))))

          (create-thumbnail [info]
            (when (and (not (svg-image? info))
                       (big-enough-for-thumbnail? info))
              (p/let [thumb   (generate-thumbnail info)
                      hash    (calculate-hash (:data thumb))
                      content (-> (sto/content (:data thumb) (:size thumb))
                                  (sto/wrap-with-hash hash))]
                (sto/put-object! storage
                                 {::sto/content content
                                  ::sto/deduplicate? true
                                  ::sto/touched-at (dt/now)
                                  :content-type (:mtype thumb)
                                  :bucket "file-media-object"}))))

          (create-image [info]
            (p/let [data    (:path info)
                    hash    (calculate-hash data)
                    content (-> (sto/content data)
                                (sto/wrap-with-hash hash))]
              (sto/put-object! storage
                               {::sto/content content
                                ::sto/deduplicate? true
                                ::sto/touched-at (dt/now)
                                :content-type (:mtype info)
                                :bucket "file-media-object"})))

          (insert-into-database [info image thumb]
            (px/with-dispatch (:default executors)
              (db/exec-one! pool [sql:create-file-media-object
                                  (or id (uuid/next))
                                  file-id is-local name
                                  (:id image)
                                  (:id thumb)
                                  (:width info)
                                  (:height info)
                                  (:mtype info)])))]

    (p/let [info  (get-info content)
            thumb (create-thumbnail info)
            image (create-image info)]
      (insert-into-database info image thumb))))

;; --- Create File Media Object (from URL)

(declare ^:private create-file-media-object-from-url)

(s/def ::create-file-media-object-from-url
  (s/keys :req-un [::profile-id ::file-id ::is-local ::url]
          :opt-un [::id ::name]))

(sv/defmethod ::create-file-media-object-from-url
  {::rsem/permits (cf/get :rpc-semaphore-permits-image)}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (let [file (select-file pool file-id)
        cfg  (update cfg :storage media/configure-assets-storage)]
    (teams/check-edition-permissions! pool profile-id (:team-id file))
    (create-file-media-object-from-url cfg params)))

(defn- create-file-media-object-from-url
  [{:keys [http-client] :as cfg} {:keys [url name] :as params}]
  (letfn [(parse-and-validate-size [headers]
            (let [size     (some-> (get headers "content-length") d/parse-integer)
                  mtype    (get headers "content-type")
                  format   (cm/mtype->format mtype)
                  max-size (cf/get :media-max-file-size default-max-file-size)]

              (when-not size
                (ex/raise :type :validation
                          :code :unknown-size
                          :hint "seems like the url points to resource with unknown size"))

              (when (> size max-size)
                (ex/raise :type :validation
                          :code :file-too-large
                          :hint (str/ffmt "the file size % is greater than the maximum %"
                                          size
                                          default-max-file-size)))

              (when (nil? format)
                (ex/raise :type :validation
                          :code :media-type-not-allowed
                          :hint "seems like the url points to an invalid media object"))

              {:size size
               :mtype mtype
               :format format}))

          (download-media [uri]
            (-> (http-client {:method :get :uri uri} {:response-type :input-stream})
                (p/then process-response)))

          (process-response [{:keys [body headers] :as response}]
            (let [{:keys [size mtype]} (parse-and-validate-size headers)
                  path                 (tmp/tempfile :prefix "penpot.media.download.")
                  written              (bs/write-to-file! body path :size size)]

              (when (not= written size)
                (ex/raise :type :internal
                          :code :mismatch-write-size
                          :hint "unexpected state: unable to write to file"))

              {:filename "tempfile"
               :size size
               :path path
               :mtype mtype}))]

    (p/let [content (download-media url)]
      (->> (merge params {:content content :name (or name (:filename content))})
           (create-file-media-object cfg)))))

;; --- Clone File Media object (Upload and create from url)

(declare clone-file-media-object)

(s/def ::clone-file-media-object
  (s/keys :req-un [::profile-id ::file-id ::is-local ::id]))

(sv/defmethod ::clone-file-media-object
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (-> (assoc cfg :conn conn)
          (clone-file-media-object params)))))

(defn clone-file-media-object
  [{:keys [conn] :as cfg} {:keys [id file-id is-local]}]
  (let [mobj (db/get-by-id conn :file-media-object id)]
    (db/insert! conn :file-media-object
                {:id (uuid/next)
                 :file-id file-id
                 :is-local is-local
                 :name (:name mobj)
                 :media-id (:media-id mobj)
                 :thumbnail-id (:thumbnail-id mobj)
                 :width  (:width mobj)
                 :height (:height mobj)
                 :mtype  (:mtype mobj)})))

;; --- HELPERS

(def ^:private
  sql:select-file
  "select file.*,
          project.team_id as team_id
     from file
    inner join project on (project.id = file.project_id)
    where file.id = ?")

(defn- select-file
  [conn id]
  (let [row (db/exec-one! conn [sql:select-file id])]
    (when-not row
      (ex/raise :type :not-found))
    row))
