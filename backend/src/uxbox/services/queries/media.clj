;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.media
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.services.queries :as sq]
   [uxbox.services.queries.teams :as teams]))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)


;; --- Query: Media objects (by file)

(declare retrieve-media-objects)
(declare retrieve-file)

(s/def ::is-local ::us/boolean)
(s/def ::media-objects
  (s/keys :req-un [::profile-id ::file-id ::is-local]))

;; TODO: check if we can resolve url with transducer for reduce
;; garbage generation for each request

(sq/defquery ::media-objects
  [{:keys [profile-id file-id is-local] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (retrieve-file conn file-id)]
      (teams/check-read-permissions! conn profile-id (:team-id file))
      (->> (retrieve-media-objects conn file-id is-local)
           (mapv #(media/resolve-urls % :path :uri))
           (mapv #(media/resolve-urls % :thumb-path :thumb-uri))))))

(def ^:private sql:media-objects
  "select obj.*,
          thumb.path as thumb_path
     from media_object as obj
    inner join media_thumbnail as thumb on obj.id = thumb.media_object_id
    where obj.deleted_at is null
      and obj.file_id = ?
      and obj.is_local = ?
   order by obj.created_at desc")

(defn retrieve-media-objects
  [conn file-id is-local]
  (db/exec! conn [sql:media-objects file-id is-local]))

(def ^:private sql:retrieve-file
  "select file.*,
          project.team_id as team_id
     from file
    inner join project on (project.id = file.project_id)
    where file.id = ?")

(defn- retrieve-file
  [conn id]
  (let [row (db/exec-one! conn [sql:retrieve-file id])]
    (when-not row
      (ex/raise :type :not-found))
    row))


;; --- Query: Media object (by ID)

(declare retrieve-media-object)

(s/def ::id ::us/uuid)
(s/def ::media-object
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::media-object
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [media-object (retrieve-media-object conn id)]
      (teams/check-read-permissions! conn profile-id (:team-id media-object))
      (-> media-object
          (media/resolve-urls :path :uri)))))

(def ^:private sql:media-object
  "select obj.*,
          p.team_id as team_id
     from media_object as obj
    inner join file as f on (f.id = obj.file_id)
    inner join project as p on (p.id = f.project_id)
    where obj.deleted_at is null
      and obj.id = ?
   order by created_at desc")

(defn retrieve-media-object
  [conn id]
  (let [row (db/exec-one! conn [sql:media-object id])]
    (when-not row
      (ex/raise :type :not-found))
    row))

