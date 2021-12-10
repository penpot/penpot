;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.media
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
   [app.storage :as sto]
   [app.util.http :as http]
   [app.util.rlimit :as rlimit]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]))

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

(s/def ::content-type ::media/image-content-type)
(s/def ::content (s/and ::media/upload (s/keys :req-un [::content-type])))

(s/def ::is-local ::us/boolean)

(s/def ::upload-file-media-object
  (s/keys :req-un [::profile-id ::file-id ::is-local ::name ::content]
          :opt-un [::id]))

(sv/defmethod ::upload-file-media-object
  {::rlimit/permits (cf/get :rlimit-image)}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (-> (assoc cfg :conn conn)
          (create-file-media-object params)))))

(defn- big-enough-for-thumbnail?
  "Checks if the provided image info is big enough for
  create a separate thumbnail storage object."
  [info]
  (or (> (:width info) (:width thumbnail-options))
      (> (:height info) (:height thumbnail-options))))

(defn- svg-image?
  [info]
  (= (:mtype info) "image/svg+xml"))

(defn- fetch-url
  [url]
  (try
    (http/get! url {:as :byte-array})
    (catch Exception e
      (ex/raise :type :validation
                :code :unable-to-access-to-url
                :cause e))))

(defn- download-media
  [{:keys [storage] :as cfg} url]
  (let [result (fetch-url url)
        data   (:body result)
        mtype  (get (:headers result) "content-type")
        format (cm/mtype->format mtype)]
    (when (nil? format)
      (ex/raise :type :validation
                :code :media-type-not-allowed
                :hint "Seems like the url points to an invalid media object."))
    (-> (assoc storage :backend :tmp)
        (sto/put-object {:content (sto/content data)
                         :content-type mtype
                         :expired-at (dt/in-future {:minutes 30})}))))

;; NOTE: we use the `on conflict do update` instead of `do nothing`
;; because postgresql does not returns anything if no update is
;; performed, the `do update` does the trick.

(def sql:create-file-media-object
  "insert into file_media_object (id, file_id, is_local, name, media_id, thumbnail_id, width, height, mtype)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?)
       on conflict (id) do update set created_at=file_media_object.created_at
       returning *")

(defn create-file-media-object
  [{:keys [conn storage] :as cfg} {:keys [id file-id is-local name content] :as params}]
  (media/validate-media-type (:content-type content))
  (let [storage      (media/configure-assets-storage storage conn)
        source-path  (fs/path (:tempfile content))
        source-mtype (:content-type content)
        source-info  (media/run {:cmd :info :input {:path source-path :mtype source-mtype}})

        thumb        (when (and (not (svg-image? source-info))
                                (big-enough-for-thumbnail? source-info))
                       (media/run (assoc thumbnail-options
                                         :cmd :generic-thumbnail
                                         :input {:mtype (:mtype source-info)
                                                 :path source-path})))

        image        (if (= (:mtype source-info) "image/svg+xml")
                       (let [data (slurp source-path)]
                         (sto/put-object storage {:content (sto/content data)
                                                  :content-type (:mtype source-info)}))
                       (sto/put-object storage {:content (sto/content source-path)
                                                :content-type (:mtype source-info)}))

        thumb        (when thumb
                       (sto/put-object storage {:content (sto/content (:data thumb) (:size thumb))
                                                :content-type (:mtype thumb)}))]

    (db/exec-one! conn [sql:create-file-media-object
                        (or id (uuid/next))
                        file-id is-local name
                        (:id image)
                        (:id thumb)
                        (:width source-info)
                        (:height source-info)
                        source-mtype])))

;; --- Create File Media Object (from URL)

(s/def ::create-file-media-object-from-url
  (s/keys :req-un [::profile-id ::file-id ::is-local ::url]
          :opt-un [::id ::name]))

(sv/defmethod ::create-file-media-object-from-url
  [{:keys [pool storage] :as cfg} {:keys [profile-id file-id url name] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (let [mobj    (download-media cfg url)
            content {:filename "tempfile"
                     :size (:size mobj)
                     :tempfile (sto/get-object-path storage mobj)
                     :content-type (:content-type (meta mobj))}
            params' (merge params {:content content
                                   :name (or name (:filename content))})]
        (-> (assoc cfg :conn conn)
            (create-file-media-object params'))))))


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
