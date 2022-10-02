;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.files
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.rpc.helpers :as rpch]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.projects :as projects]
   [app.rpc.queries.share-link :refer [retrieve-share-link]]
   [app.rpc.queries.teams :as teams]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(declare decode-row)

;; --- Helpers & Specs

(s/def ::frame-id ::us/uuid)
(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::search-term ::us/string)
(s/def ::components-v2 ::us/boolean)

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
        :can-read true
        :is-logged (some? profile-id)})))
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
                      :is-logged (some? profile-id)
                      :who-comment (:who-comment ldata)
                      :who-inspect (:who-inspect ldata)}))))

(def has-edit-permissions?
  (perms/make-edition-predicate-fn get-permissions))

(def has-read-permissions?
  (perms/make-read-predicate-fn get-permissions))

(def has-comment-permissions?
  (perms/make-comment-predicate-fn get-permissions))

(def check-edition-permissions!
  (perms/make-check-fn has-edit-permissions?))

(def check-read-permissions!
  (perms/make-check-fn has-read-permissions?))

;; A user has comment permissions if she has read permissions, or comment permissions
(defn check-comment-permissions!
  [conn profile-id file-id share-id]
   (let [can-read (has-read-permissions? conn profile-id file-id)
         can-comment  (has-comment-permissions? conn profile-id file-id share-id)]
     (when-not (or can-read can-comment)
       (ex/raise :type :not-found
                 :code :object-not-found
                 :hint "not found"))))

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

(defn retrieve-object-thumbnails
  ([{:keys [pool]} file-id]
   (let [sql (str/concat
              "select object_id, data "
              "  from file_object_thumbnail"
              " where file_id=?")]
     (->> (db/exec! pool [sql file-id])
          (d/index-by :object-id :data))))

  ([{:keys [pool]} file-id object-ids]
   (with-open [conn (db/open pool)]
     (let [sql (str/concat
                "select object_id, data "
                "  from file_object_thumbnail"
                " where file_id=? and object_id = ANY(?)")
           ids (db/create-array conn "text" (seq object-ids))]
       (->> (db/exec! conn [sql file-id ids])
            (d/index-by :object-id :data))))))

(defn retrieve-file
  [{:keys [pool] :as cfg} id components-v2]
  (let [file (->> (db/get-by-id pool :file id)
                  (decode-row)
                  (pmg/migrate-file))]

    (if components-v2
      (update file :data ctf/migrate-to-components-v2)
      (if (get-in file [:data :options :components-v2])
        (ex/raise :type :restriction
                  :code :feature-disabled
                  :hint "tried to open a components-v2 file with feature disabled")
        file))))

(s/def ::file
  (s/keys :req-un [::profile-id ::id]
          :opt-un [::components-v2]))

(sv/defmethod ::file
  "Retrieve a file by its ID. Only authenticated users."
  [{:keys [pool] :as cfg} {:keys [profile-id id components-v2] :as params}]
  (let [perms (get-permissions pool profile-id id)]
    (check-read-permissions! perms)
    (let [file   (retrieve-file cfg id components-v2)
          thumbs (retrieve-object-thumbnails cfg id)]
      (-> file
          (assoc :thumbnails thumbs)
          (assoc :permissions perms)))))


;; --- QUERY: page

(defn- prune-objects
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
  (update page :objects d/update-vals #(dissoc % :thumbnail)))

(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)

(s/def ::page
  (s/and
   (s/keys :req-un [::profile-id ::file-id]
           :opt-un [::page-id ::object-id ::components-v2])
   (fn [obj]
     (if (contains? obj :object-id)
       (contains? obj :page-id)
       true))))

(sv/defmethod ::page
  "Retrieves the page data from file and returns it. If no page-id is
  specified, the first page will be returned. If object-id is
  specified, only that object and its children will be returned in the
  page objects data structure.

  If you specify the object-id, the page-id parameter becomes
  mandatory.

  Mainly used for rendering purposes."
  [{:keys [pool] :as cfg} {:keys [profile-id file-id page-id object-id components-v2] :as props}]
  (check-read-permissions! pool profile-id file-id)
  (let [file    (retrieve-file cfg file-id components-v2)
        page-id (or page-id (-> file :data :pages first))
        page    (get-in file [:data :pages-index page-id])]

    (cond-> (prune-thumbnails page)
      (uuid? object-id)
      (prune-objects object-id))))

;; --- QUERY: file-data-for-thumbnail

(defn- get-file-thumbnail-data
  [cfg {:keys [data id] :as file}]
  (letfn [;; function responsible on finding the frame marked to be
          ;; used as thumbnail; the returned frame always have
          ;; the :page-id set to the page that it belongs.
          (get-thumbnail-frame [data]
            (d/seek :use-for-thumbnail?
                    (for [page  (-> data :pages-index vals)
                          frame (-> page :objects ctt/get-frames)]
                      (assoc frame :page-id (:id page)))))

          ;; function responsible to filter objects data structure of
          ;; all unneeded shapes if a concrete frame is provided. If no
          ;; frame, the objects is returned untouched.
          (filter-objects [objects frame-id]
            (d/index-by :id (cph/get-children-with-self objects frame-id)))

          ;; function responsible of assoc available thumbnails
          ;; to frames and remove all children shapes from objects if
          ;; thumbnails is available
          (assoc-thumbnails [objects page-id thumbnails]
            (loop [objects objects
                   frames  (filter cph/frame-shape? (vals objects))]

              (if-let [frame  (-> frames first)]
                (let [frame-id (:id frame)
                      object-id (str page-id frame-id)
                      frame (if-let [thumb (get thumbnails object-id)]
                              (assoc frame :thumbnail thumb :shapes [])
                              (dissoc frame :thumbnail))

                      children-ids
                      (cph/get-children-ids objects frame-id)

                      bounds
                      (when (:show-content frame)
                        (gsh/selection-rect (concat [frame] (->> children-ids (map (d/getf objects))))))

                      frame
                      (cond-> frame
                        (some? bounds)
                        (assoc :children-bounds bounds))]

                  (if (:thumbnail frame)
                    (recur (-> objects
                               (assoc frame-id frame)
                               (d/without-keys children-ids))
                           (rest frames))
                    (recur (assoc objects frame-id frame)
                           (rest frames))))

                objects)))]

    (let [frame     (get-thumbnail-frame data)
          frame-id  (:id frame)
          page-id   (or (:page-id frame)
                        (-> data :pages first))

          page      (dm/get-in data [:pages-index page-id])
          frame-ids (if (some? frame) (list frame-id) (map :id (ctt/get-frames (:objects page))))

          obj-ids   (map #(str page-id %) frame-ids)
          thumbs    (retrieve-object-thumbnails cfg id obj-ids)]

      (cond-> page
        ;; If we have frame, we need to specify it on the page level
        ;; and remove the all other unrelated objects.
        (some? frame-id)
        (-> (assoc :thumbnail-frame-id frame-id)
            (update :objects filter-objects frame-id))

        ;; Assoc the available thumbnails and prune not visible shapes
        ;; for avoid transfer unnecessary data.
        :always
        (update :objects assoc-thumbnails page-id thumbs)))))

(s/def ::file-data-for-thumbnail
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::components-v2]))

(sv/defmethod ::file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used
  mainly for render thumbnails on dashboard."
  [{:keys [pool] :as cfg} {:keys [profile-id file-id components-v2] :as props}]
  (check-read-permissions! pool profile-id file-id)
  (let [file (retrieve-file cfg file-id components-v2)]
    {:file-id file-id
     :revn (:revn file)
     :page (get-file-thumbnail-data cfg file)}))


;; --- Query: Shared Library Files

(def ^:private sql:team-shared-files
  "select f.id,
          f.revn,
          f.data,
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
  (let [assets-sample
        (fn [assets limit]
          (let [sorted-assets (->> (vals assets)
                                   (sort-by #(str/lower (:name %))))]

          {:count (count sorted-assets)
           :sample (into [] (take limit sorted-assets))}))

        library-summary
        (fn [data]
          {:components (assets-sample (:components data) 4)
           :colors (assets-sample (:colors data) 3)
           :typographies (assets-sample (:typographies data) 3)})

        xform (comp
                (map decode-row)
                (map #(assoc % :library-summary (library-summary (:data %))))
                (map #(dissoc % :data)))]

    (into #{} xform (db/exec! pool [sql:team-shared-files team-id]))))


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


;; --- Query: Files that use this File library

(def ^:private sql:library-using-files
  "SELECT f.id,
          f.name
     FROM file_library_rel AS flr
     JOIN file AS f ON (f.id = flr.file_id)
    WHERE flr.library_file_id = ?
      AND (f.deleted_at IS NULL OR f.deleted_at > now())")

(s/def ::library-using-files
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::library-using-files
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (check-read-permissions! pool profile-id file-id)
  (db/exec! pool [sql:library-using-files file-id]))

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
