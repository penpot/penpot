;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.management
  "A collection of RPC methods for manage the files, projects and team organization."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.sse :as sse]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as proj]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.setup :as-alias setup]
   [app.setup.templates :as tmpl]
   [app.storage.tmp :as tmp]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [promesa.exec :as px]))

;; --- COMMAND: Duplicate File

(defn duplicate-file
  [{:keys [::db/conn ::bfc/timestamp] :as cfg} {:keys [profile-id file-id name reset-shared-flag] :as params}]
  (let [;; We don't touch the original file on duplication
        file       (bfc/get-file cfg file-id)
        project-id (:project-id file)
        file       (-> file
                       (update :id bfc/lookup-index)
                       (update :project-id bfc/lookup-index)
                       (cond-> (string? name)
                         (assoc :name name))
                       (cond-> (true? reset-shared-flag)
                         (assoc :is-shared false)))

        flibs  (bfc/get-files-rels cfg #{file-id})
        fmeds  (bfc/get-file-media cfg file)]

    (when (uuid? profile-id)
      (proj/check-edition-permissions! conn profile-id project-id))

    (vswap! bfc/*state* update :index bfc/update-index fmeds :id)

    ;; Process and persist file
    (let [file (bfc/process-file file)]
      (bfc/insert-file! cfg file ::db/return-keys false)

      ;; The file profile creation is optional, so when no profile is
      ;; present (when this function is called from profile less
      ;; environment: SREPL) we just omit the creation of the relation
      (when (uuid? profile-id)
        (db/insert! conn :file-profile-rel
                    {:file-id (:id file)
                     :profile-id profile-id
                     :is-owner true
                     :is-admin true
                     :can-edit true}
                    {::db/return-keys false}))

      (doseq [params (sequence (comp
                                (map #(bfc/remap-id % :file-id))
                                (map #(bfc/remap-id % :library-file-id))
                                (map #(assoc % :synced-at timestamp))
                                (map #(assoc % :created-at timestamp)))
                               flibs)]
        (db/insert! conn :file-library-rel params ::db/return-keys false))

      (doseq [params (sequence (comp
                                (map #(bfc/remap-id % :id))
                                (map #(assoc % :created-at timestamp))
                                (map #(bfc/remap-id % :file-id)))
                               fmeds)]
        (db/insert! conn :file-media-object params ::db/return-keys false))

      file)))

(def ^:private
  schema:duplicate-file
  [:map {:title "duplicate-file"}
   [:file-id ::sm/uuid]
   [:name {:optional true} [:string {:max 250}]]])

(sv/defmethod ::duplicate-file
  "Duplicate a single file in the same team."
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:duplicate-file}
  [cfg {:keys [::rpc/profile-id file-id] :as params}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

                    (binding [bfc/*state* (volatile! {:index {file-id (uuid/next)}})]
                      (duplicate-file (assoc cfg ::bfc/timestamp (dt/now))
                                      (-> params
                                          (assoc :profile-id profile-id)
                                          (assoc :reset-shared-flag true)))))))

;; --- COMMAND: Duplicate Project

(defn duplicate-project
  [{:keys [::db/conn ::bfc/timestamp] :as cfg} {:keys [profile-id project-id name] :as params}]
  (binding [bfc/*state* (volatile! {:index {project-id (uuid/next)}})]
    (let [project (-> (db/get-by-id conn :project project-id)
                      (assoc :created-at timestamp)
                      (assoc :modified-at timestamp)
                      (assoc :is-pinned false)
                      (update :id bfc/lookup-index)
                      (cond-> (string? name)
                        (assoc :name name)))

          files   (bfc/get-project-files cfg project-id)]

      ;; Update index with the project files and the project-id
      (vswap! bfc/*state* update :index bfc/update-index files)


      ;; Check if the source team-id allow creating new project for current user
      (teams/check-edition-permissions! conn profile-id (:team-id project))

      ;; create the duplicated project and assign the current profile as
      ;; a project owner
      (let [project (teams/create-project conn project)]
        ;; The project profile creation is optional, so when no profile is
        ;; present (when this function is called from profile less
        ;; environment: SREPL) we just omit the creation of the relation
        (when (uuid? profile-id)
          (teams/create-project-role conn profile-id (:id project) :owner))

        (doseq [file-id files]
          (let [params (-> params
                           (dissoc :name)
                           (assoc :file-id file-id)
                           (assoc :reset-shared-flag false))]
            (duplicate-file cfg params)))

        project))))

(def ^:private
  schema:duplicate-project
  [:map {:title "duplicate-project"}
   [:project-id ::sm/uuid]
   [:name {:optional true} [:string {:max 250}]]])

(sv/defmethod ::duplicate-project
  "Duplicate an entire project with all the files"
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:duplicate-project}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg (fn [cfg]
                    ;; Defer all constraints
                    (db/exec-one! cfg ["SET CONSTRAINTS ALL DEFERRED"])
                    (-> (assoc cfg ::bfc/timestamp (dt/now))
                        (duplicate-project (assoc params :profile-id profile-id))))))

(defn duplicate-team
  [{:keys [::db/conn ::bfc/timestamp] :as cfg} & {:keys [profile-id team-id name] :as params}]

  ;; Check if the source team-id allowed to be read by the user if
  ;; profile-id is present; it can be ommited if this function is
  ;; called from SREPL helpers where no profile is available
  (when (uuid? profile-id)
    (teams/check-read-permissions! conn profile-id team-id))

  (binding [bfc/*state* (volatile! {:index {team-id (uuid/next)}})]
    (let [projs (bfc/get-team-projects cfg team-id)
          files (bfc/get-team-files-ids cfg team-id)
          frels (bfc/get-files-rels cfg files)

          team  (-> (db/get-by-id conn :team team-id)
                    (assoc :created-at timestamp)
                    (assoc :modified-at timestamp)
                    (update :id bfc/lookup-index)
                    (cond-> (string? name)
                      (assoc :name name)))

          fonts (db/query conn :team-font-variant
                          {:team-id team-id})]

      (vswap! bfc/*state* update :index
              (fn [index]
                (-> index
                    (bfc/update-index projs)
                    (bfc/update-index files)
                    (bfc/update-index fonts :id))))

      ;; FIXME: disallow clone default team
      ;; Create the new team in the database
      (db/insert! conn :team team)

      ;; Duplicate team <-> profile relations
      (doseq [params frels]
        (let [params (-> params
                         (assoc :id (uuid/next))
                         (update :team-id bfc/lookup-index)
                         (assoc :created-at timestamp)
                         (assoc :modified-at timestamp))]
          (db/insert! conn :team-profile-rel params
                      {::db/return-keys false})))

      ;; Duplicate team fonts
      (doseq [font fonts]
        (let [params (-> font
                         (update :id bfc/lookup-index)
                         (update :team-id bfc/lookup-index)
                         (assoc :created-at timestamp)
                         (assoc :modified-at timestamp))]
          (db/insert! conn :team-font-variant params
                      {::db/return-keys false})))

      ;; Duplicate projects; We don't reuse the `duplicate-project`
      ;; here because we handle files duplication by whole team
      ;; instead of by project and we want to preserve some project
      ;; props which are reset on the `duplicate-project` impl
      (doseq [project-id projs]
        (let [project (db/get conn :project {:id project-id})
              project (-> project
                          (assoc :created-at timestamp)
                          (assoc :modified-at timestamp)
                          (update :id bfc/lookup-index)
                          (update :team-id bfc/lookup-index))]
          (teams/create-project conn project)

          ;; The project profile creation is optional, so when no profile is
          ;; present (when this function is called from profile less
          ;; environment: SREPL) we just omit the creation of the relation
          (when (uuid? profile-id)
            (teams/create-project-role conn profile-id (:id project) :owner))))

      (doseq [file-id files]
        (let [params (-> params
                         (dissoc :name)
                         (assoc :file-id file-id)
                         (assoc :reset-shared-flag false))]
          (duplicate-file cfg params)))

      team)))

;; --- COMMAND: Move file

(def sql:get-files
  "select id, features, project_id from file where id = ANY(?)")

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
  [{:keys [::db/conn] :as cfg} {:keys [profile-id ids project-id] :as params}]

  (let [fids    (db/create-array conn "uuid" ids)
        files   (->> (db/exec! conn [sql:get-files fids])
                     (map files/decode-row))
        source  (into #{} (map :project-id) files)
        pids    (->> (conj source project-id)
                     (db/create-array conn "uuid"))]

    (when (contains? source project-id)
      (ex/raise :type :validation
                :code :cant-move-to-same-project
                :hint "Unable to move a file to the same project"))

    ;; Check if we have permissions on the destination project
    (proj/check-edition-permissions! conn profile-id project-id)

    ;; Check if we have permissions on all source projects
    (doseq [project-id source]
      (proj/check-edition-permissions! conn profile-id project-id))

    ;; Check the team compatibility
    (let [orig-team (teams/get-team conn :profile-id profile-id :project-id (first source))
          dest-team (teams/get-team conn :profile-id profile-id :project-id project-id)]
      (cfeat/check-teams-compatibility! orig-team dest-team)

      ;; Check if all pending to move files are compaib
      (let [features (cfeat/get-team-enabled-features cf/flags dest-team)]
        (doseq [file files]
          (cfeat/check-file-features! features (:features file)))))

    ;; move all files to the project
    (db/exec-one! conn [sql:move-files project-id fids])

    ;; delete possible broken relations on moved files
    (db/exec-one! conn [sql:delete-broken-relations pids])

    ;; Update the modification date of the all affected projects
    ;; ensuring that the destination project is the most recent one.
    (doseq [project-id (into (list project-id) source)]

      ;; NOTE: as this is executed on virtual thread, sleeping does
      ;; not causes major issues, and allows an easy way to set a
      ;; trully different modification date to each file.
      (px/sleep 10)
      (db/update! conn :project
                  {:modified-at (dt/now)}
                  {:id project-id}))

    nil))

(def ^:private
  schema:move-files
  [:map {:title "move-files"}
   [:ids [::sm/set {:min 1} ::sm/uuid]]
   [:project-id ::sm/uuid]])

(sv/defmethod ::move-files
  "Move a set of files from one project to other."
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:move-files}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg move-files (assoc params :profile-id profile-id)))

;; --- COMMAND: Move project

(defn move-project
  [{:keys [::db/conn] :as cfg} {:keys [profile-id team-id project-id] :as params}]
  (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})
        pids    (->> (db/query conn :project {:team-id (:team-id project)} {:columns [:id]})
                     (map :id)
                     (db/create-array conn "uuid"))]

    (when (= team-id (:team-id project))
      (ex/raise :type :validation
                :code :cant-move-to-same-team
                :hint "Unable to move a project to same team"))

    (teams/check-edition-permissions! conn profile-id (:team-id project))
    (teams/check-edition-permissions! conn profile-id team-id)

    ;; Check the teams compatibility
    (let [orig-team (teams/get-team conn :profile-id profile-id :team-id (:team-id project))
          dest-team (teams/get-team conn :profile-id profile-id :team-id team-id)]
      (cfeat/check-teams-compatibility! orig-team dest-team)

      ;; Check if all pending to move files are compaib
      (let [features (cfeat/get-team-enabled-features cf/flags dest-team)]
        (doseq [file (->> (db/query conn :file
                                    {:project-id project-id}
                                    {:columns [:features]})
                          (map files/decode-row))]
          (cfeat/check-file-features! features (:features file)))))

    ;; move project to the destination team
    (db/update! conn :project
                {:team-id team-id}
                {:id project-id})

    ;; delete possible broken relations on moved files
    (db/exec-one! conn [sql:delete-broken-relations pids])

    nil))

(def ^:private
  schema:move-project
  [:map {:title "move-project"}
   [:team-id ::sm/uuid]
   [:project-id ::sm/uuid]])

(sv/defmethod ::move-project
  "Move projects between teams"
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:move-project}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg #(move-project % (assoc params :profile-id profile-id))))

;; --- COMMAND: Clone Template

(defn clone-template
  [{:keys [::db/pool ::wrk/executor] :as cfg} {:keys [project-id profile-id] :as params} template]

  ;; NOTE: the importation process performs some operations
  ;; that are not very friendly with virtual threads, and for
  ;; avoid unexpected blocking of other concurrent operations
  ;; we dispatch that operation to a dedicated executor.
  (let [template (tmp/tempfile-from template
                                    :prefix "penpot.template."
                                    :suffix ""
                                    :min-age "30m")

        format   (bfc/parse-file-format template)
        team     (teams/get-team pool
                                 :profile-id profile-id
                                 :project-id project-id)

        cfg      (-> cfg
                     (assoc ::bfc/project-id project-id)
                     (assoc ::bfc/profile-id profile-id)
                     (assoc ::bfc/input template)
                     (assoc ::bfc/features (cfeat/get-team-enabled-features cf/flags team)))

        result   (if (= format :binfile-v3)
                   (px/invoke! executor (partial bf.v3/import-files! cfg))
                   (px/invoke! executor (partial bf.v1/import-files! cfg)))]

    (db/tx-run! cfg
                (fn [{:keys [::db/conn] :as cfg}]
                  (db/update! conn :project
                              {:modified-at (dt/now)}
                              {:id project-id}
                              {::db/return-keys false})

                  (let [props (audit/clean-props params)]
                    (doseq [file-id result]
                      (let [props (assoc props :id file-id)
                            event (-> (audit/event-from-rpc-params params)
                                      (assoc ::audit/profile-id profile-id)
                                      (assoc ::audit/name "create-file")
                                      (assoc ::audit/props props))]
                        (audit/submit! cfg event))))))

    result))

(def ^:private
  schema:clone-template
  [:map {:title "clone-template"}
   [:project-id ::sm/uuid]
   [:template-id ::sm/word-string]])

(sv/defmethod ::clone-template
  "Clone into the specified project the template by its id."
  {::doc/added "1.16"
   ::sse/stream? true
   ::webhooks/event? true
   ::sm/params schema:clone-template}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id template-id] :as params}]
  (let [project   (db/get-by-id pool :project project-id {:columns [:id :team-id]})
        _         (teams/check-edition-permissions! pool profile-id (:team-id project))
        template  (tmpl/get-template-stream cfg template-id)
        params    (assoc params :profile-id profile-id)]

    (when-not template
      (ex/raise :type :not-found
                :code :template-not-found
                :hint "template not found"))

    (sse/response #(clone-template cfg params template))))

;; --- COMMAND: Get list of builtin templates

(sv/defmethod ::get-builtin-templates
  {::doc/added "1.19"}
  [cfg _params]
  (mapv #(select-keys % [:id :name]) (::setup/templates cfg)))
