;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.files
  (:require
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.projects :as projects]
   [app.rpc.queries.share-link :refer [retrieve-share-link]]
   [app.rpc.queries.teams :as teams]
   [app.storage.impl :as simpl]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

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

(defn retrieve-file-permissions
  [conn profile-id file-id]
  (when (and profile-id file-id)
    (db/exec! conn [sql:file-permissions
                    file-id profile-id
                    file-id profile-id
                    file-id profile-id])))

(defn get-permissions
  ([conn profile-id file-id]
   (let [rows     (retrieve-file-permissions conn profile-id file-id)
         is-owner (boolean (some :is-owner rows))
         is-admin (boolean (some :is-admin rows))
         can-edit (boolean (some :can-edit rows))]
     (when (seq rows)
       {:type :membership
        :is-owner is-owner
        :is-admin (or is-owner is-admin)
        :can-edit (or is-owner is-admin can-edit)
        :can-read true})))
  ([conn profile-id file-id share-id]
   (let [perms  (get-permissions conn profile-id file-id)
         ldata  (retrieve-share-link conn file-id share-id)]

     ;; NOTE: in a future when share-link becomes more powerful and
     ;; will allow us specify which parts of the app is available, we
     ;; will probably need to tweak this function in order to expose
     ;; this flags to the frontend.
     (cond
       (some? perms) perms
       (some? ldata) {:type :share-link
                      :can-read true
                      :flags (:flags ldata)}))))

(def has-edit-permissions?
  (perms/make-edition-predicate-fn get-permissions))

(def has-read-permissions?
  (perms/make-read-predicate-fn get-permissions))

(def check-edition-permissions!
  (perms/make-check-fn has-edit-permissions?))

(def check-read-permissions!
  (perms/make-check-fn has-read-permissions?))

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
   )
   select distinct
          f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    inner join projects as pr on (f.project_id = pr.id)
    where f.name ilike ('%' || ? || '%')
      and f.deleted_at is null
    order by f.created_at asc")

(s/def ::search-files
  (s/keys :req-un [::profile-id ::team-id]
          :opt-un [::search-term]))

(sv/defmethod ::search-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id search-term] :as params}]
  (when search-term
    (db/exec! pool [sql:search-files
                    profile-id team-id
                    profile-id team-id
                    search-term])))

;; --- Query: Project Files

(def ^:private sql:project-files
  "select f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    where f.project_id = ?
      and f.deleted_at is null
    order by f.modified_at desc")

(s/def ::project-files
  (s/keys :req-un [::profile-id ::project-id]))

(sv/defmethod ::project-files
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (db/exec! conn [sql:project-files project-id])))

;; --- Query: File (By ID)

(defn- retrieve-data*
  [{:keys [storage] :as cfg} file]
  (when-let [backend (simpl/resolve-backend storage (:data-backend file))]
    (simpl/get-object-bytes backend file)))

(defn retrieve-data
  [cfg file]
  (if (bytes? (:data file))
    file
    (assoc file :data (retrieve-data* cfg file))))

(defn retrieve-file
  [{:keys [conn] :as cfg} id]
  (->> (db/get-by-id conn :file id)
       (retrieve-data cfg)
       (decode-row)
       (pmg/migrate-file)))

(s/def ::file
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::file
  "Retrieve a file by its ID. Only authenticated users."
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg   (assoc cfg :conn conn)
          perms (get-permissions conn profile-id id)]

      (check-read-permissions! perms)
      (some-> (retrieve-file cfg id)
              (assoc :permissions perms)))))

(s/def ::page
  (s/keys :req-un [::profile-id ::file-id]))

(defn remove-thumbnails-frames
  "Removes from data the children for frames that have a thumbnail set up"
  [data]
  (let [filter-shape?
        (fn [objects [id shape]]
          (let [frame-id (:frame-id shape)]
            (or (= id uuid/zero)
                (= frame-id uuid/zero)
                (not (some? (get-in objects [frame-id :thumbnail]))))))

        ;; We need to remove from the attribute :shapes its children because
        ;; they will not be sent in the data
        remove-frame-children
        (fn [[id shape]]
          [id (cond-> shape
                (some? (:thumbnail shape))
                (assoc :shapes []))])

        update-objects
        (fn [objects]
          (into {}
                (comp (map remove-frame-children)
                      (filter (partial filter-shape? objects)))
                objects))]

    (update data :objects update-objects)))

(sv/defmethod ::page
  [{:keys [pool] :as cfg} {:keys [profile-id file-id strip-thumbnails]}]
  (db/with-atomic [conn pool]
    (check-read-permissions! conn profile-id file-id)

    (let [cfg     (assoc cfg :conn conn)
          file    (retrieve-file cfg file-id)
          page-id (get-in file [:data :pages 0])]
      (cond-> (get-in file [:data :pages-index page-id])
        strip-thumbnails
        (remove-thumbnails-frames)))))

;; --- Query: Shared Library Files

(def ^:private sql:team-shared-files
  "select f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    inner join project as p on (p.id = f.project_id)
    where f.is_shared = true
      and f.deleted_at is null
      and p.deleted_at is null
      and p.team_id = ?
    order by f.modified_at desc")

(s/def ::team-shared-files
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-shared-files
  [{:keys [pool] :as cfg} {:keys [team-id] :as params}]
  (db/exec! pool [sql:team-shared-files team-id]))


;; --- Query: File Libraries used by a File

(def ^:private sql:file-libraries
  "WITH RECURSIVE libs AS (
     SELECT fl.*, flr.synced_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
      WHERE flr.file_id = ?::uuid
    UNION
     SELECT fl.*, flr.synced_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
       JOIN libs AS l ON (flr.file_id = l.id)
   )
   SELECT l.id,
          l.data,
          l.project_id,
          l.created_at,
          l.modified_at,
          l.deleted_at,
          l.name,
          l.revn,
          l.synced_at
     FROM libs AS l
    WHERE l.deleted_at IS NULL OR l.deleted_at > now();")

(defn retrieve-file-libraries
  [{:keys [conn] :as cfg} is-indirect file-id]
  (let [xform (comp
               (map #(assoc % :is-indirect is-indirect))
               (map #(retrieve-data cfg %))
               (map decode-row))]
    (into #{} xform (db/exec! conn [sql:file-libraries file-id]))))

(s/def ::file-libraries
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::file-libraries
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [cfg (assoc cfg :conn conn)]
      (check-read-permissions! conn profile-id file-id)
      (retrieve-file-libraries cfg false file-id))))

;; --- QUERY: team-recent-files

(def sql:team-recent-files
  "with recent_files as (
     select f.id,
            f.project_id,
            f.created_at,
            f.modified_at,
            f.name,
            f.is_shared,
            row_number() over w as row_num
       from file as f
       join project as p on (p.id = f.project_id)
      where p.team_id = ?
        and p.deleted_at is null
        and f.deleted_at is null
     window w as (partition by f.project_id order by f.modified_at desc)
      order by f.modified_at desc
   )
   select * from recent_files where row_num <= 10;")

(s/def ::team-recent-files
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-recent-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (db/exec! conn [sql:team-recent-files team-id])))

;; --- Helpers

(defn decode-row
  [{:keys [data changes] :as row}]
  (when row
    (cond-> row
      changes (assoc :changes (blob/decode changes))
      data    (assoc :data (blob/decode data)))))

(def decode-row-xf
  (comp (map decode-row)
        (map pmg/migrate-file)))
