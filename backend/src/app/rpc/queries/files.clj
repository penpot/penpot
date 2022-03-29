;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.files
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.pages.helpers :as cph]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.rpc.helpers :as rpch]
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

;; --- FILE THUMBNAIL

(defn- trim-objects
  "Given the page data and the object-id returns the page data with all
  other not needed objects removed from the `:objects` data
  structure."
  [{:keys [objects] :as page} object-id]
  (let [objects (cph/get-children-with-self objects object-id)]
     (assoc page :objects (d/index-by :id objects))))

(defn- prune-thumbnails
  "Given the page data, removes the `:thumbnail` prop from all
  shapes."
  [page]
  (update page :objects (fn [objects]
                          (d/mapm #(dissoc %2 :thumbnail) objects))))

(defn- prune-frames-with-thumbnails
  "Remove unnecesary shapes from frames that have thumbnail from page
  data."
  [page]
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

    (update page :objects update-objects)))

(defn- get-thumbnail-data
  [{:keys [data] :as file}]
  (if-let [[page frame] (first
                         (for [page  (-> data :pages-index vals)
                               frame (-> page :objects cph/get-frames)
                               :when (:file-thumbnail frame)]
                           [page frame]))]
    (let [objects (->> (cph/get-children-with-self (:objects page) (:id frame))
                       (d/index-by :id))]
      (-> (assoc page :objects objects)
          (assoc :thumbnail-frame frame)))

    (let [page-id (-> data :pages first)]
      (-> (get-in data [:pages-index page-id])
          (prune-frames-with-thumbnails)))))

(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::prune-frames-with-thumbnails ::us/boolean)
(s/def ::prune-thumbnails ::us/boolean)

(s/def ::page
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::page-id
                   ::object-id
                   ::prune-frames-with-thumbnails
                   ::prune-thumbnails]))

(sv/defmethod ::page
  "Retrieves the page data from file and returns it. If no page-id is
  specified, the first page will be returned. If object-id is
  specified, only that object and its children will be returned in the
  page objects data structure.

  Mainly used for rendering purposes."
  [{:keys [pool] :as cfg} {:keys [profile-id file-id page-id object-id] :as props}]
  (check-read-permissions! pool profile-id file-id)
  (let [file    (retrieve-file cfg file-id)
        page-id (or page-id (-> file :data :pages first))
        page    (get-in file [:data :pages-index page-id])]

    (cond-> page
      (:prune-frames-with-thumbnails props)
      (prune-frames-with-thumbnails)

      (:prune-thumbnails props)
      (prune-thumbnails)

      (uuid? object-id)
      (trim-objects object-id))))

(s/def ::file-data-for-thumbnail
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used mainly for render
  thumbnails on dashboard. Returns the page data."
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as props}]
  (check-read-permissions! pool profile-id file-id)
  (let [file (retrieve-file cfg file-id)]
    {:page (get-thumbnail-data file)
     :file-id file-id
     :revn (:revn file)}))

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

;; --- QUERY: get all file frame thumbnails

(s/def ::file-frame-thumbnails
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::frame-id]))

(sv/defmethod ::file-frame-thumbnails
  [{:keys [pool]} {:keys [profile-id file-id frame-id]}]
  (check-read-permissions! pool profile-id file-id)
  (let [params (cond-> {:file-id file-id}
                 frame-id (assoc :frame-id frame-id))
        rows   (db/query pool :file-frame-thumbnail params)]
    (d/index-by :frame-id :data rows)))

;; --- QUERY: get file thumbnail

(s/def ::revn ::us/integer)

(s/def ::file-thumbnail
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::revn]))

(sv/defmethod ::file-thumbnail
  [{:keys [pool]} {:keys [profile-id file-id revn]}]
  (check-read-permissions! pool profile-id file-id)
  (let [sql (sql/select :file-thumbnail
                        (cond-> {:file-id file-id}
                          revn (assoc :revn revn))
                        {:limit 1
                         :order-by [[:revn :desc]]})

        row (db/exec-one! pool sql)]

    (when-not row
      (ex/raise :type :not-found
                :code :file-thumbnail-not-found))

    (with-meta
      {:data (:data row)
       :props (some-> (:props row) db/decode-transit-pgobject)
       :revn (:revn row)
       :file-id (:file-id row)}
      {:transform-response (rpch/http-cache {:max-age (* 1000 60 60)})})))

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
