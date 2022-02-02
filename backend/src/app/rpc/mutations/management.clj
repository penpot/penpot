;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.management
  "Move & Duplicate RPC methods for files and projects."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.mutations.projects :refer [create-project-role create-project]]
   [app.rpc.queries.projects :as proj]
   [app.rpc.queries.teams :as teams]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]))

(s/def ::id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::name ::us/string)

(defn- remap-id
  [item index key]
  (cond-> item
    (contains? item key)
    (assoc key (get index (get item key) (get item key)))))

(defn- process-file
  [file index]
  (letfn [(process-form [form]
            (cond-> form
              ;; Relink library items
              (and (map? form)
                   (uuid? (:component-file form)))
              (update :component-file #(get index % %))

              (and (map? form)
                   (uuid? (:fill-color-ref-file form)))
              (update :fill-color-ref-file #(get index % %))

              (and (map? form)
                   (uuid? (:stroke-color-ref-file form)))
              (update :stroke-color-ref-file #(get index % %))

              (and (map? form)
                   (uuid? (:typography-ref-file form)))
              (update :typography-ref-file #(get index % %))

              ;; Relink Image Shapes
              (and (map? form)
                   (map? (:metadata form))
                   (= :image (:type form)))
              (update-in [:metadata :id] #(get index % %))))

          ;; A function responsible to analyze all file data and
          ;; replace the old :component-file reference with the new
          ;; ones, using the provided file-index
          (relink-shapes [data]
            (walk/postwalk process-form data))

          ;; A function responsible of process the :media attr of file
          ;; data and remap the old ids with the new ones.
          (relink-media [media]
            (reduce-kv (fn [res k v]
                         (let [id (get index k)]
                           (if (uuid? id)
                             (-> res
                                 (assoc id (assoc v :id id))
                                 (dissoc k))
                             res)))
                       media
                       media))]

    (update file :data
            (fn [data]
              (-> data
                  (blob/decode)
                  (assoc :id (:id file))
                  (pmg/migrate-data)
                  (update :pages-index relink-shapes)
                  (update :components relink-shapes)
                  (update :media relink-media)
                  (d/without-nils)
                  (blob/encode))))))

(def sql:retrieve-used-libraries
  "select flr.*
     from file_library_rel as flr
    inner join file as l on (flr.library_file_id = l.id)
    where flr.file_id = ?
      and l.deleted_at is null")

(def sql:retrieve-used-media-objects
  "select fmo.*
     from file_media_object as fmo
    inner join storage_object as so on (fmo.media_id = so.id)
    where fmo.file_id = ?
      and so.deleted_at is null")

(defn duplicate-file
  [conn {:keys [profile-id file index project-id name flibs fmeds]} {:keys [reset-shared-flag] :as opts}]
  (let [flibs    (or flibs (db/exec! conn [sql:retrieve-used-libraries (:id file)]))
        fmeds    (or fmeds (db/exec! conn [sql:retrieve-used-media-objects (:id file)]))

        ;; memo uniform creation/modification date
        now      (dt/now)
        ignore   (dt/plus now (dt/duration {:seconds 5}))

        ;; add to the index all file media objects.
        index    (reduce #(assoc %1 (:id %2) (uuid/next)) index fmeds)

        flibs-xf (comp
                  (map #(remap-id % index :file-id))
                  (map #(remap-id % index :library-file-id))
                  (map #(assoc % :synced-at now))
                  (map #(assoc % :created-at now)))

        ;; remap all file-library-rel row
        flibs    (sequence flibs-xf flibs)

        fmeds-xf (comp
                  (map #(assoc % :id (get index (:id %))))
                  (map #(assoc % :created-at now))
                  (map #(remap-id % index :file-id)))

        ;; remap all file-media-object rows
        fmeds   (sequence fmeds-xf fmeds)

        file    (cond-> file
                  (some? project-id)
                  (assoc :project-id project-id)

                  (some? name)
                  (assoc :name name)

                  (true? reset-shared-flag)
                  (assoc :is-shared false))

        file    (-> file
                    (assoc :created-at now)
                    (assoc :modified-at now)
                    (assoc :ignore-sync-until ignore)
                    (update :id #(get index %))
                    (process-file index))]

    (db/insert! conn :file file)
    (db/insert! conn :file-profile-rel
                {:file-id (:id file)
                 :profile-id profile-id
                 :is-owner true
                 :is-admin true
                 :can-edit true})

    (doseq [params flibs]
      (db/insert! conn :file-library-rel params))

    (doseq [params fmeds]
      (db/insert! conn :file-media-object params))

    file))


;; --- MUTATION: Duplicate File

(declare duplicate-file)

(s/def ::duplicate-file
  (s/keys :req-un [::profile-id ::file-id]
          :opt-un [::name]))

(sv/defmethod ::duplicate-file
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (let [file   (db/get-by-id conn :file file-id)
          index  {file-id (uuid/next)}
          params (assoc params :index index :file file)]
      (proj/check-edition-permissions! conn profile-id (:project-id file))
      (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
      (-> (duplicate-file conn params {:reset-shared-flag true})
          (update :data blob/decode)))))


;; --- MUTATION: Duplicate Project

(declare duplicate-project)

(s/def ::duplicate-project
  (s/keys :req-un [::profile-id ::project-id]
          :opt-un [::name]))

(sv/defmethod ::duplicate-project
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (let [project (db/get-by-id conn :project project-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id project))
      (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
      (duplicate-project conn (assoc params :project project)))))

(defn duplicate-project
  [conn {:keys [profile-id project name] :as params}]
  (let [files   (db/query conn :file
                          {:project-id (:id project)
                           :deleted-at nil}
                          {:columns [:id]})

        project (cond-> project
                  (string? name)
                  (assoc :name name)

                  :always
                  (assoc :id (uuid/next)))]

    ;; create the duplicated project and assign the current profile as
    ;; a project owner
    (create-project conn project)
    (create-project-role conn {:project-id (:id project)
                               :profile-id profile-id
                               :role :owner})

    ;; duplicate all files
    (let [index  (reduce #(assoc %1 (:id %2) (uuid/next)) {} files)
          params (-> params
                     (dissoc :name)
                     (assoc :project-id (:id project))
                     (assoc :index index))]
      (doseq [{:keys [id]} files]
        (let [file   (db/get-by-id conn :file id)
              params (assoc params :file file)
              opts   {:reset-shared-flag false}]
          (duplicate-file conn params opts))))

    ;; return the created project
    project))


;; --- MUTATION: Move file

(def sql:retrieve-files
  "select id, project_id from file where id = ANY(?)")

(def sql:move-files
  "update file set project_id = ? where id = ANY(?)")

(def sql:delete-broken-relations
  "with broken as (
     (select * from file_library_rel as flr
       inner join file as f on (flr.file_id = f.id)
       inner join project as p on (f.project_id = p.id)
       inner join file as lf on (flr.library_file_id = lf.id)
       inner join project as lp on (lf.project_id = lp.id)
       where p.id = ANY(?)
         and lp.team_id != p.team_id)
   )
   delete from file_library_rel as rel
    using broken as br
    where rel.file_id = br.file_id
      and rel.library_file_id = br.library_file_id")

(s/def ::ids (s/every ::us/uuid :kind set?))
(s/def ::move-files
  (s/keys :req-un [::profile-id ::ids ::project-id]))

(sv/defmethod ::move-files
  [{:keys [pool] :as cfg} {:keys [profile-id ids project-id] :as params}]
  (db/with-atomic [conn pool]
    (let [fids    (db/create-array conn "uuid" ids)
          files   (db/exec! conn [sql:retrieve-files fids])
          source  (into #{} (map :project-id) files)
          pids    (->> (conj source project-id)
                       (db/create-array conn "uuid"))]

      ;; Check if we have permissions on the destination project
      (proj/check-edition-permissions! conn profile-id project-id)

      ;; Check if we have permissions on all source projects
      (doseq [project-id source]
        (proj/check-edition-permissions! conn profile-id project-id))

      (when (contains? source project-id)
        (ex/raise :type :validation
                  :code :cant-move-to-same-project
                  :hint "Unable to move a file to the same project"))

      ;; move all files to the project
      (db/exec-one! conn [sql:move-files project-id fids])

      ;; delete possible broken relations on moved files
      (db/exec-one! conn [sql:delete-broken-relations pids])

      nil)))


;; --- MUTATION: Move project

(declare move-project)

(s/def ::move-project
  (s/keys :req-un [::profile-id ::team-id ::project-id]))

(sv/defmethod ::move-project
  [{:keys [pool] :as cfg} {:keys [profile-id team-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})

          pids    (->> (db/query conn :project {:team-id (:team-id project)} {:columns [:id]})
                       (map :id)
                       (db/create-array conn "uuid"))]

      (teams/check-edition-permissions! conn profile-id (:team-id project))
      (teams/check-edition-permissions! conn profile-id team-id)

      (when (= team-id (:team-id project))
        (ex/raise :type :validation
                  :code :cant-move-to-same-team
                  :hint "Unable to move a project to same team"))

      ;; move project to the destination team
      (db/update! conn :project
                  {:team-id team-id}
                  {:id project-id})

      ;; delete possible broken relations on moved files
      (db/exec-one! conn [sql:delete-broken-relations pids])

      nil)))
