;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.mutations.media
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.media :as media]
   [app.rpc.queries.teams :as teams]
   [app.util.storage :as ust]
   [app.util.services :as sv]
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
(s/def ::url ::us/url)

;; --- Create Media object (Upload and create from url)

(declare create-media-object)
(declare select-file-for-update)
(declare persist-media-object-on-fs)
(declare persist-media-thumbnail-on-fs)

(s/def ::content ::media/upload)
(s/def ::is-local ::us/boolean)

(s/def ::add-media-object-from-url
  (s/keys :req-un [::profile-id ::file-id ::is-local ::url]
          :opt-un [::id ::name]))

(s/def ::upload-media-object
  (s/keys :req-un [::profile-id ::file-id ::is-local ::name ::content]
          :opt-un [::id]))

(sv/defmethod ::add-media-object-from-url
  [{:keys [pool] :as cfg} {:keys [profile-id file-id url name] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (let [content (media/download-media-object url)
            cfg     (assoc cfg :conn conn)
            params' (merge params {:content content
                                   :name (or name (:filename content))})]
        (create-media-object cfg params')))))

(sv/defmethod ::upload-media-object
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [file (select-file-for-update conn file-id)
          cfg  (assoc cfg :conn conn)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (create-media-object cfg params))))

(defn create-media-object
  [{:keys [conn] :as cfg} {:keys [id file-id is-local name content]}]
  (media/validate-media-type (:content-type content))
  (let [info  (media/run {:cmd :info :input {:path (:tempfile content)
                                             :mtype (:content-type content)}})
        path  (persist-media-object-on-fs cfg content)
        opts  (assoc thumbnail-options
                     :input {:mtype (:mtype info)
                             :path path})
        thumb (if-not (= (:mtype info) "image/svg+xml")
                (persist-media-thumbnail-on-fs cfg opts)
                (assoc info
                       :path path
                       :quality 0))

        id (or id (uuid/next))

        media-object (db/insert! conn :media-object
                                 {:id id
                                  :file-id file-id
                                  :is-local is-local
                                  :name name
                                  :path (str path)
                                  :width (:width info)
                                  :height (:height info)
                                  :mtype  (:mtype info)})

        media-thumbnail (db/insert! conn :media-thumbnail
                                    {:id (uuid/next)
                                     :media-object-id id
                                     :path (str (:path thumb))
                                     :width (:width thumb)
                                     :height (:height thumb)
                                     :quality (:quality thumb)
                                     :mtype (:mtype thumb)})]
    (assoc media-object :thumb-path (:path media-thumbnail))))

(def ^:private sql:select-file-for-update
  "select file.*,
          project.team_id as team_id
     from file
    inner join project on (project.id = file.project_id)
    where file.id = ?
      for update of file")

(defn- select-file-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-file-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))

(defn persist-media-object-on-fs
  [{:keys [storage]} {:keys [filename tempfile]}]
  (let [filename (fs/name filename)]
    (ust/save! storage filename tempfile)))

(defn persist-media-thumbnail-on-fs
  [{:keys [storage]} {:keys [input] :as params}]
  (let [path  (ust/lookup storage (:path input))
        thumb (media/run
                (-> params
                    (assoc :cmd :generic-thumbnail)
                    (update :input assoc :path path)))

        name  (str "thumbnail-"
                   (first (fs/split-ext (fs/name (:path input))))
                   (cm/format->extension (:format thumb)))
        path  (ust/save! storage name (:data thumb))]

    (-> thumb
        (dissoc :data :input)
        (assoc :path path))))
