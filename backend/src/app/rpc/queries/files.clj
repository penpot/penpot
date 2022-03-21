;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.files
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.projects :as projects]
   [app.rpc.queries.share-link :refer [retrieve-share-link]]
   [app.rpc.queries.teams :as teams]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(declare decode-row)
(declare decode-row-xf)

;; --- Helpers & Specs

(s/def ::frame-id ::us/uuid)
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
          f.revn,
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

(defn retrieve-file
  [{:keys [pool] :as cfg} id]
  (let [item (db/get-by-id pool :file id)]
    (->> item
         (decode-row)
         (pmg/migrate-file))))

(s/def ::file
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::file
  "Retrieve a file by its ID. Only authenticated users."
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (let [perms (get-permissions pool profile-id id)]
    (check-read-permissions! perms)
    (-> (retrieve-file cfg id)
        (assoc :permissions perms))))

(declare trim-file-data)

(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)

(s/def ::trimmed-file
  (s/keys :req-un [::profile-id ::id ::object-id ::page-id]))

(sv/defmethod ::trimmed-file
  "Retrieve a file by its ID and trims all unnecesary content from
  it. It is mainly used for rendering a concrete object, so we don't
  need force download all shapes when only a small subset is
  necesseary."
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (let [perms (get-permissions pool profile-id id)]
    (check-read-permissions! perms)
    (-> (retrieve-file cfg id)
        (trim-file-data params)
        (assoc :permissions perms))))

(defn- trim-file-data
  [file {:keys [page-id object-id]}]
  (let [page    (get-in file [:data :pages-index page-id])
        objects (->> (cph/get-children-with-self (:objects page) object-id)
                     (map #(dissoc % :thumbnail))
                     (d/index-by :id))
        page    (assoc page :objects objects)]
    (-> file
        (update :data assoc :pages-index {page-id page})
        (update :data assoc :pages [page-id]))))

;; --- FILE THUMBNAIL

(declare strip-frames-with-thumbnails)
(declare extract-file-thumbnail)
(declare get-first-page-data)
(declare get-thumbnail-data)

(s/def ::strip-frames-with-thumbnails ::us/boolean)

(s/def ::page
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::strip-frames-with-thumbnails]))

(sv/defmethod ::page
  "Retrieves the first page of the file. Used mainly for render
  thumbnails on dashboard.

  DEPRECATED: still here for backward compatibility."
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as props}]
  (check-read-permissions! pool profile-id file-id)
  (let [file (retrieve-file cfg file-id)
        data (get-first-page-data file props)]
    data))

(s/def ::file-data-for-thumbnail
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::strip-frames-with-thumbnails]))

(sv/defmethod ::file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used mainly for render
  thumbnails on dashboard."
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as props}]
  (check-read-permissions! pool profile-id file-id)
  (let [file (retrieve-file cfg file-id)]
    (get-thumbnail-data file props)))

(defn get-thumbnail-data
  [{:keys [data] :as file} props]
  (if-let [[page frame] (first
                         (for [page (-> data :pages-index vals)
                               frame (-> page :objects cph/get-frames)
                               :when (:file-thumbnail frame)]
                           [page frame]))]
    (let [objects (->> (cph/get-children-with-self (:objects page) (:id frame))
                       (d/index-by :id))]
      (cond-> (assoc page :objects objects)
        (:strip-frames-with-thumbnails props)
        (strip-frames-with-thumbnails)

        :always
        (assoc :thumbnail-frame frame)))

    (let [page-id (-> data :pages first)]
      (cond-> (get-in data [:pages-index page-id])
        (:strip-frames-with-thumbnails props)
        (strip-frames-with-thumbnails)))))

(defn get-first-page-data
  [file props]
  (let [page-id (get-in file [:data :pages 0])
        data (cond-> (get-in file [:data :pages-index page-id])
               (true? (:strip-frames-with-thumbnails props))
               (strip-frames-with-thumbnails))]
    data))

(defn strip-frames-with-thumbnails
  "Remove unnecesary shapes from frames that have thumbnail."
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


;; --- Query: Shared Library Files

(def ^:private sql:team-shared-files
  "select f.id,
          f.revn,
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
  [{:keys [pool] :as cfg} is-indirect file-id]
  (let [xform (comp
               (map #(assoc % :is-indirect is-indirect))
               (map decode-row))]
    (into #{} xform (db/exec! pool [sql:file-libraries file-id]))))

(s/def ::file-libraries
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::file-libraries
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (check-read-permissions! pool profile-id file-id)
  (retrieve-file-libraries cfg false file-id))

;; --- QUERY: team-recent-files

(def sql:team-recent-files
  "with recent_files as (
     select f.id,
            f.revn,
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
  (teams/check-read-permissions! pool profile-id team-id)
  (db/exec! pool [sql:team-recent-files team-id]))


;; --- QUERY: get the thumbnail for an frame

(def ^:private sql:file-frame-thumbnail
  "select data
     from file_frame_thumbnail
    where file_id = ?
      and frame_id = ?")

(s/def ::file-frame-thumbnail
  (s/keys :req-un [::profile-id ::file-id ::frame-id]))

(sv/defmethod ::file-frame-thumbnail
  [{:keys [pool]} {:keys [profile-id file-id frame-id]}]
  (check-read-permissions! pool profile-id file-id)
  (db/exec-one! pool [sql:file-frame-thumbnail file-id frame-id]))

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
