;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.services.queries.files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [app.common.pages-migrations :as pmg]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.media :as media]
   [app.services.queries :as sq]
   [app.services.queries.projects :as projects]
   [app.util.blob :as blob]))

(declare decode-row)
(declare decode-row-xf)

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::search-term ::us/string)


;; --- Query: File Permissions

(def ^:private sql:file-permissions
  "select fpr.is_owner,
          fpr.is_admin,
          fpr.can_edit
     from file_profile_rel as fpr
    where fpr.file_id = ?
      and fpr.profile_id = ?
   union all
   select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = ?
      and tpr.profile_id = ?
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    inner join file as f on (f.project_id = ppr.project_id)
    where f.id = ?
      and ppr.profile_id = ?")

(defn check-edition-permissions!
  [conn profile-id file-id]
  (let [rows (db/exec! conn [sql:file-permissions
                             file-id profile-id
                             file-id profile-id
                             file-id profile-id])]
    (when (empty? rows)
      (ex/raise :type :not-found))

    (when-not (or (some :can-edit rows)
                  (some :is-admin rows)
                  (some :is-owner rows))
      (ex/raise :type :validation
                :code :not-authorized))))


(defn check-read-permissions!
  [conn profile-id file-id]
  (let [rows (db/exec! conn [sql:file-permissions
                             file-id profile-id
                             file-id profile-id
                             file-id profile-id])]
    (when-not (seq rows)
      (ex/raise :type :validation
                :code :not-authorized))))


;; --- Query: Files search

;; TODO: this query need to a good refactor

(def ^:private sql:search-files
  "with projects as (
     select p.*
       from project as p
      inner join team_profile_rel as tpr on (tpr.team_id = p.team_id)
      where tpr.profile_id = ?
        and p.team_id = ?
        and p.deleted_at is null
        and (tpr.is_admin = true or
             tpr.is_owner = true or
             tpr.can_edit = true)
      union
     select p.*
       from project as p
      inner join project_profile_rel as ppr on (ppr.project_id = p.id)
      where ppr.profile_id = ?
        and p.team_id = ?
        and p.deleted_at is null
        and (ppr.is_admin = true or
             ppr.is_owner = true or
             ppr.can_edit = true)
      union
     select p.*
       from project as p
      where p.team_id = uuid_nil()
        and p.deleted_at is null
   )
   select distinct f.*
     from file as f
    inner join projects as pr on (f.project_id = pr.id)
    where f.name ilike ('%' || ? || '%')
      and f.deleted_at is null
    order by f.created_at asc")

(s/def ::search-files
  (s/keys :req-un [::profile-id ::team-id ::search-term]))

(sq/defquery ::search-files
  [{:keys [profile-id team-id search-term] :as params}]
  (let [rows (db/exec! db/pool [sql:search-files
                                profile-id team-id
                                profile-id team-id
                                search-term])]
    (into [] decode-row-xf rows)))


;; --- Query: Project Files

(def ^:private sql:files
  "select f.*
     from file as f
    where f.project_id = ?
      and f.deleted_at is null
    order by f.modified_at desc")

(s/def ::project-id ::us/uuid)
(s/def ::files
  (s/keys :req-un [::profile-id ::project-id]))

(sq/defquery ::files
  [{:keys [profile-id project-id] :as params}]
  (with-open [conn (db/open)]
    (projects/check-read-permissions! conn profile-id project-id)
    (into [] decode-row-xf (db/exec! conn [sql:files project-id]))))


;; --- Query: File (By ID)

(defn retrieve-file
  [conn id]
  (let [file (db/get-by-id conn :file id)]
    (-> (decode-row file)
        (pmg/migrate-file))))

(s/def ::file
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::file
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)
    (retrieve-file conn id)))

(s/def ::page
  (s/keys :req-un [::profile-id ::id ::file-id]))

(sq/defquery ::page
  [{:keys [profile-id file-id id]}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id file-id)
    (let [file (retrieve-file conn file-id)]
      (get-in file [:data :pages-index id]))))

;; --- Query: File users

(def ^:private sql:file-users
  "select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join file_profile_rel as fpr on (fpr.profile_id = pf.id)
    where fpr.file_id = ?
    union
   select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join team_profile_rel as tpr on (tpr.profile_id = pf.id)
    inner join project as p on (tpr.team_id = p.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = ?")

(defn retrieve-file-users
  [conn id]
  (->> (db/exec! conn [sql:file-users id id])
       (mapv #(media/resolve-media-uris % [:photo :photo-uri]))))

(s/def ::file-users
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::file-users
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id id)
    (retrieve-file-users conn id)))

;; --- Query: Shared Library Files

;; TODO: remove the counts, because they are no longer needed.

(def ^:private sql:shared-files
  "select f.*,
          (select count(*) from color as c
            where c.file_id = f.id
              and c.deleted_at is null) as colors_count,
          (select count(*) from media_object as m
            where m.file_id = f.id
              and m.is_local = false
              and m.deleted_at is null) as graphics_count
     from file as f
    inner join project as p on (p.id = f.project_id)
    where f.is_shared = true
      and f.deleted_at is null
      and p.deleted_at is null
      and p.team_id = ?
    order by f.modified_at desc")

(s/def ::shared-files
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::shared-files
  [{:keys [profile-id team-id] :as params}]
  (into [] decode-row-xf (db/exec! db/pool [sql:shared-files team-id])))


;; --- Query: File Libraries used by a File

(def ^:private sql:file-libraries
  "select fl.*,
          flr.synced_at as synced_at
     from file as fl
    inner join file_library_rel as flr on (flr.library_file_id = fl.id)
    where flr.file_id = ?
      and fl.deleted_at is null")

(defn retrieve-file-libraries
  [conn file-id]
  (into [] decode-row-xf (db/exec! conn [sql:file-libraries file-id])))

(s/def ::file-libraries
  (s/keys :req-un [::profile-id ::file-id]))

(sq/defquery ::file-libraries
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id file-id)
    (retrieve-file-libraries conn file-id)))


;; --- Query: Single File Library

;; TODO: this looks like is duplicate of `::file`

(def ^:private sql:file-library
  "select fl.*
     from file as fl
    where fl.id = ?")

(defn retrieve-file-library
  [conn file-id]
  (let [rows (db/exec! conn [sql:file-library file-id])]
    (when-not (seq rows)
      (ex/raise :type :not-found))
    (first (sequence decode-row-xf rows))))

(s/def ::file-library
  (s/keys :req-un [::profile-id ::file-id]))

(sq/defquery ::file-library
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn profile-id file-id) ;; TODO: this should check read permissions
    (retrieve-file-library conn file-id)))


;; --- Helpers

(defn decode-row
  [{:keys [pages data changes] :as row}]
  (when row
    (cond-> row
      changes (assoc :changes (blob/decode changes))
      data (assoc :data (blob/decode data))
      pages (assoc :pages (vec (.getArray pages))))))

(def decode-row-xf
  (comp (map decode-row)
        (map pmg/migrate-file)))
