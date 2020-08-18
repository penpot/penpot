;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.services.queries.colors
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.services.queries :as sq]
   [app.services.queries.teams :as teams]
   [app.util.blob :as blob]
   [app.util.data :as data]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::file-id ::us/uuid)


;; --- Query: Colors (by file)

(declare retrieve-colors)
(declare retrieve-file)

(s/def ::colors
  (s/keys :req-un [::profile-id ::file-id]))

(sq/defquery ::colors
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (retrieve-file conn file-id)]
      (teams/check-read-permissions! conn profile-id (:team-id file))
      (retrieve-colors conn file-id))))

(def ^:private sql:colors
  "select *
     from color
    where color.deleted_at is null
      and color.file_id = ?
   order by created_at desc")

(defn- retrieve-colors
  [conn file-id]
  (db/exec! conn [sql:colors file-id]))

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


;; --- Query: Color (by ID)

(declare retrieve-color)

(s/def ::id ::us/uuid)
(s/def ::color
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::color
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [color (retrieve-color conn id)]
      (teams/check-read-permissions! conn profile-id (:team-id color))
      color)))

(def ^:private sql:single-color
  "select color.*,
          p.team_id as team_id
     from color as color
    inner join file as f on (color.file_id = f.id)
    inner join project as p on (p.id = f.project_id)
    where color.deleted_at is null
      and color.id = ?
   order by created_at desc")

(defn retrieve-color
  [conn id]
  (let [row (db/exec-one! conn [sql:single-color id])]
    (when-not row
      (ex/raise :type :not-found))
    row))

