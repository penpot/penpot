;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.management
  "A collection of RPC methods for manage the files, projects and team organization."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as proj]
   [app.rpc.commands.teams :as teams :refer [create-project-role create-project]]
   [app.rpc.doc :as-alias doc]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]))

;; --- COMMAND: Duplicate File

(declare duplicate-file)

(s/def ::id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::name ::us/string)

(s/def ::duplicate-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::name]))

(sv/defmethod ::duplicate-file
  "Duplicate a single file in the same team."
  {::doc/added "1.16"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (duplicate-file conn (assoc params :profile-id profile-id))))

(defn- remap-id
  [item index key]
  (cond-> item
    (contains? item key)
    (assoc key (get index (get item key) (get item key)))))

(defn- process-file
  [conn {:keys [id] :as file} index]
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
    (-> file
        (update :id #(get index %))
        (update :data
                (fn [data]
                  (binding [pmap/*load-fn* (partial files/load-pointer conn id)
                            pmap/*tracked* (atom {})]
                    (let [file-id (get index id)
                          data    (-> data
                                      (blob/decode)
                                      (assoc :id file-id)
                                      (pmg/migrate-data)
                                      (update :pages-index relink-shapes)
                                      (update :components relink-shapes)
                                      (update :media relink-media)
                                      (d/without-nils)
                                      (files/process-pointers pmap/clone)
                                      (blob/encode))]
                      (files/persist-pointers! conn file-id)
                      data)))))))

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

(defn duplicate-file*
  [conn {:keys [profile-id file index project-id name flibs fmeds]} {:keys [reset-shared-flag]}]
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
                    (assoc :ignore-sync-until ignore))

        file    (process-file conn file index)]

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

(defn duplicate-file
  [conn {:keys [profile-id file-id] :as params}]
  (let [file   (db/get-by-id conn :file file-id)
        index  {file-id (uuid/next)}
        params (assoc params :index index :file file)]
    (proj/check-edition-permissions! conn profile-id (:project-id file))
    (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
    (-> (duplicate-file* conn params {:reset-shared-flag true})
        (update :data blob/decode)
        (update :features db/decode-pgarray #{}))))

;; --- COMMAND: Duplicate Project

(declare duplicate-project)

(s/def ::duplicate-project
  (s/keys :req [::rpc/profile-id]
          :req-un [::project-id]
          :opt-un [::name]))

(sv/defmethod ::duplicate-project
  "Duplicate an entire project with all the files"
  {::doc/added "1.16"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (duplicate-project conn (assoc params :profile-id (::rpc/profile-id params)))))

(defn duplicate-project
  [conn {:keys [profile-id project-id name] :as params}]

  ;; Defer all constraints
  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

  (let [project (-> (db/get-by-id conn :project project-id)
                    (assoc :is-pinned false))

        files   (db/query conn :file
                  {:project-id (:id project)
                   :deleted-at nil}
                  {:columns [:id]})

        project (cond-> project
                  (string? name)
                  (assoc :name name)

                  :always
                  (assoc :id (uuid/next)))]

    ;; Check if the source team-id allow creating new project for current user
    (teams/check-edition-permissions! conn profile-id (:team-id project))

    ;; create the duplicated project and assign the current profile as
    ;; a project owner
    (create-project conn project)
    (create-project-role conn profile-id (:id project) :owner)

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
          (duplicate-file* conn params opts))))

    ;; return the created project
    project))

;; --- COMMAND: Move file

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

(defn move-files
  [conn {:keys [profile-id ids project-id] :as params}]

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

    nil))

(s/def ::ids (s/every ::us/uuid :kind set?))
(s/def ::move-files
  (s/keys :req [::rpc/profile-id]
          :req-un [::ids ::project-id]))

(sv/defmethod ::move-files
  "Move a set of files from one project to other."
  {::doc/added "1.16"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (move-files conn (assoc params :profile-id profile-id))))

;; --- COMMAND: Move project

(defn move-project
  [conn {:keys [profile-id team-id project-id] :as params}]
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

    nil))


(s/def ::move-project
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::project-id]))

(sv/defmethod ::move-project
  "Move projects between teams."
  {::doc/added "1.16"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (move-project conn (assoc params :profile-id profile-id))))

;; --- COMMAND: Clone Template

(declare clone-template)

(s/def ::template-id ::us/not-empty-string)
(s/def ::clone-template
  (s/keys :req [::rpc/profile-id]
          :req-un [::project-id ::template-id]))

(sv/defmethod ::clone-template
  "Clone into the specified project the template by its id."
  {::doc/added "1.16"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (-> (assoc cfg :conn conn)
        (clone-template (assoc params :profile-id profile-id)))))

(defn- clone-template
  [{:keys [conn templates] :as cfg} {:keys [profile-id template-id project-id]}]
  (let [template (d/seek #(= (:id %) template-id) templates)
        project  (db/get-by-id conn :project project-id {:columns [:id :team-id]})]

    (teams/check-edition-permissions! conn profile-id (:team-id project))

    (when-not template
      (ex/raise :type :not-found
                :code :template-not-found
                :hint "template not found"))

    (-> cfg
        (assoc ::binfile/input (:path template))
        (assoc ::binfile/project-id (:id project))
        (assoc ::binfile/ignore-index-errors? true)
        (assoc ::binfile/migrate? true)
        (binfile/import!))))


;; --- COMMAND: Retrieve list of builtin templates

(s/def ::retrieve-list-of-builtin-templates any?)

(sv/defmethod ::retrieve-list-of-builtin-templates
  [cfg _params]
  (mapv #(select-keys % [:id :name :thumbnail-uri]) (:templates cfg)))
