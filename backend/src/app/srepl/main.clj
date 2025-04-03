;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.main
  "A collection of adhoc fixes scripts."
  #_:clj-kondo/ignore
  (:require
   [app.auth :refer [derive-password]]
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.pprint :as p]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.components-v2 :as feat.comp-v2]
   [app.features.fdata :as feat.fdata]
   [app.loggers.audit :as audit]
   [app.main :as main]
   [app.msgbus :as mbus]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-snapshot :as fsnap]
   [app.rpc.commands.management :as mgmt]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.srepl.fixes :as fixes]
   [app.srepl.helpers :as h]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.java.io :as io]
   [clojure.pprint :refer [print-table]]
   [clojure.stacktrace :as strace]
   [clojure.tools.namespace.repl :as repl]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [promesa.exec :as px]
   [promesa.exec.semaphore :as ps]
   [promesa.util :as pu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASKS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn print-tasks
  []
  (let [tasks (:app.worker/registry main/system)]
    (p/pprint (keys tasks) :level 200)))

(defn run-task!
  ([tname]
   (run-task! tname {}))
  ([tname params]
   (wrk/invoke! (-> main/system
                    (assoc ::wrk/task tname)
                    (assoc ::wrk/params params)))))

(defn schedule-task!
  ([name]
   (schedule-task! name {}))
  ([name params]
   (wrk/submit! (-> main/system
                    (assoc ::wrk/task name)
                    (assoc ::wrk/params params)))))

(defn send-test-email!
  [destination]
  (assert (string? destination) "destination should be provided")
  (-> main/system
      (assoc ::wrk/task :sendmail)
      (assoc ::wrk/params {:body "test email"
                           :subject "test email"
                           :to [destination]})
      (wrk/invoke!)))

(defn resend-email-verification-email!
  [email]
  (db/tx-run! main/system
              (fn [{:keys [::db/conn] :as cfg}]
                (let [email   (profile/clean-email email)
                      profile (profile/get-profile-by-email conn email)]
                  (#'auth/send-email-verification! cfg profile)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROFILES MANAGEMENT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mark-profile-as-active!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [email]
  (some-> main/system
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (when-let [profile (db/get* conn :profile
                                         {:email (str/lower email)}
                                         {:columns [:id :email]})]
               (when-not (:is-blocked profile)
                 (db/update! conn :profile {:is-active true} {:id (:id profile)})
                 :activated))))))

(defn mark-profile-as-blocked!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [email]
  (some-> main/system
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (when-let [profile (db/get* conn :profile
                                         {:email (str/lower email)}
                                         {:columns [:id :email]})]
               (when-not (:is-blocked profile)
                 (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
                 (db/delete! conn :http-session {:profile-id (:id profile)})
                 :blocked))))))

(defn reset-password!
  "Reset a password to a specific one for a concrete user or all users
  if email is `:all` keyword."
  [& {:keys [email password] :or {password "123123"} :as params}]
  (when-not email
    (throw (IllegalArgumentException. "email is mandatory")))

  (some-> main/system
          (db/tx-run!
           (fn [{:keys [::db/conn] :as system}]
             (let [password (derive-password password)]
               (if (= email :all)
                 (db/exec! conn ["update profile set password=?" password])
                 (let [email (str/lower email)]
                   (db/exec! conn ["update profile set password=? where email=?" password email]))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FEATURES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare process-file!)

(defn enable-objects-map-feature-on-file!
  [file-id & {:as opts}]
  (process-file! file-id feat.fdata/enable-objects-map opts))

(defn enable-pointer-map-feature-on-file!
  [file-id & {:as opts}]
  (process-file! file-id feat.fdata/enable-pointer-map opts))

(defn enable-storage-features-on-file!
  [file-id & {:as opts}]
  (enable-objects-map-feature-on-file! file-id opts)
  (enable-pointer-map-feature-on-file! file-id opts))

(defn enable-team-feature!
  [team-id feature & {:keys [skip-check] :or {skip-check false}}]
  (when (and (not skip-check) (not (contains? cfeat/supported-features feature)))
    (ex/raise :type :assertion
              :code :feature-not-supported
              :hint (str "feature '" feature "' not supported")))

  (let [team-id (h/parse-uuid team-id)]
    (db/tx-run! main/system
                (fn [{:keys [::db/conn]}]
                  (let [team     (-> (db/get conn :team {:id team-id})
                                     (update :features db/decode-pgarray #{}))
                        features (conj (:features team) feature)]
                    (when (not= features (:features team))
                      (db/update! conn :team
                                  {:features (db/create-array conn "text" features)}
                                  {:id team-id})
                      :enabled))))))

(defn disable-team-feature!
  [team-id feature & {:keys [skip-check] :or {skip-check false}}]
  (when (and (not skip-check) (not (contains? cfeat/supported-features feature)))
    (ex/raise :type :assertion
              :code :feature-not-supported
              :hint (str "feature '" feature "' not supported")))

  (let [team-id (h/parse-uuid team-id)]
    (db/tx-run! main/system
                (fn [{:keys [::db/conn]}]
                  (let [team     (-> (db/get conn :team {:id team-id})
                                     (update :features db/decode-pgarray #{}))
                        features (disj (:features team) feature)]
                    (when (not= features (:features team))
                      (db/update! conn :team
                                  {:features (db/create-array conn "text" features)}
                                  {:id team-id})
                      :disabled))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NOTIFICATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn notify!
  "Send flash notifications.

  This method allows send flash notifications to specified target destinations.
  The message can be a free text or a preconfigured one.

  The destination can be: all, profile-id, team-id, or a coll of them.
  It also can be:

  {:email \"some@example.com\"}
  [[:email \"some@example.com\"], ...]

  Command examples:

  (notify! :dest :all :code :maintenance)
  (notify! :dest :all :code :upgrade-version)
  "
  [& {:keys [dest code message level]
      :or {code :generic level :info}
      :as params}]

  (when-not (contains? #{:success :error :info :warning} level)
    (ex/raise :type :assertion
              :code :incorrect-level
              :hint (str "level '" level "' not supported")))

  (let [{:keys [::mbus/msgbus ::db/pool]} main/system

        send
        (fn [dest]
          (l/inf :hint "sending notification" :dest (str dest))
          (let [message {:type :notification
                         :code code
                         :level level
                         :version (:full cf/version)
                         :subs-id dest
                         :message message}
                message (->> (dissoc params :dest :code :message :level)
                             (merge message))]
            (mbus/pub! msgbus
                       :topic dest
                       :message message)))

        resolve-profile
        (fn [email]
          (some-> (db/get* pool :profile {:email (str/lower email)} {:columns [:id]}) :id vector))

        resolve-team
        (fn [team-id]
          (->> (db/query pool :team-profile-rel
                         {:team-id team-id}
                         {:columns [:profile-id]})
               (map :profile-id)))

        resolve-dest
        (fn resolve-dest [dest]
          (cond
            (= :all dest)
            [uuid/zero]

            (uuid? dest)
            [dest]

            (string? dest)
            (some-> dest h/parse-uuid resolve-dest)

            (nil? dest)
            [uuid/zero]

            (map? dest)
            (sequence (comp
                       (map vec)
                       (mapcat resolve-dest))
                      dest)

            (and (vector? dest)
                 (every? vector? dest))
            (sequence (comp
                       (map vec)
                       (mapcat resolve-dest))
                      dest)

            (and (vector? dest)
                 (keyword? (first dest)))
            (let [[op param] dest]
              (cond
                (= op :email)
                (cond
                  (and (coll? param)
                       (every? string? param))
                  (sequence (comp
                             (keep resolve-profile)
                             (mapcat identity))
                            param)

                  (string? param)
                  (resolve-profile param))

                (= op :team-id)
                (cond
                  (coll? param)
                  (sequence (comp
                             (mapcat resolve-team)
                             (keep h/parse-uuid))
                            param)

                  (uuid? param)
                  (resolve-team param)

                  (string? param)
                  (some-> param h/parse-uuid resolve-team))

                (= op :profile-id)
                (if (coll? param)
                  (sequence (keep h/parse-uuid) param)
                  (resolve-dest param))))))]

    (->> (resolve-dest dest)
         (filter some?)
         (into #{})
         (run! send))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SNAPSHOTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn take-file-snapshot!
  "An internal helper that persist the file snapshot using non-gc
  collectable file-changes entry."
  [& {:keys [file-id label]}]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! main/system fsnap/create-file-snapshot! {:file-id file-id :label label})))

(defn restore-file-snapshot!
  [file-id label]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! main/system
                (fn [{:keys [::db/conn] :as system}]
                  (when-let [snapshot (->> (h/search-file-snapshots conn #{file-id} label)
                                           (map :id)
                                           (first))]
                    (fsnap/restore-file-snapshot! system file-id (:id snapshot)))))))

(defn list-file-snapshots!
  [file-id & {:as _}]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! main/system
                (fn [{:keys [::db/conn]}]
                  (->> (fsnap/get-file-snapshots conn file-id)
                       (print-table [:label :id :revn :created-at]))))))

(defn take-team-snapshot!
  [team-id & {:keys [label rollback?] :or {rollback? true}}]
  (let [team-id (h/parse-uuid team-id)]
    (-> (assoc main/system ::db/rollback rollback?)
        (db/tx-run! h/take-team-snapshot! team-id label))))

(defn restore-team-snapshot!
  "Restore a snapshot on all files of the team. The snapshot should
  exists for all files; if is not the case, an exception is raised."
  [team-id label & {:keys [rollback?] :or {rollback? true}}]
  (let [team-id (h/parse-uuid team-id)]
    (-> (assoc main/system ::db/rollback rollback?)
        (db/tx-run! h/restore-team-snapshot! team-id label))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE VALIDATION & REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-file
  "Validate structure, referencial integrity and semantic coherence of
  all contents of a file. Returns a list of errors."
  [file-id]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! (assoc main/system ::db/rollback true)
                (fn [{:keys [::db/conn] :as system}]
                  (let [file (h/get-file system file-id)
                        libs (->> (files/get-file-libraries conn file-id)
                                  (into [file] (map (fn [{:keys [id]}]
                                                      (h/get-file system id))))
                                  (d/index-by :id))]
                    (cfv/validate-file file libs))))))

(defn repair-file!
  "Repair the list of errors detected by validation."
  [file-id & {:keys [rollback?] :or {rollback? true} :as opts}]
  (let [system  (assoc main/system ::db/rollback rollback?)
        file-id (h/parse-uuid file-id)
        opts    (assoc opts :with-libraries? true)]
    (db/tx-run! system h/process-file! file-id fixes/repair-file opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROCESSING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:get-files
  "SELECT id FROM file
    WHERE deleted_at is NULL
    ORDER BY created_at DESC")

(defn process-file!
  "Apply a function to the file. Optionally save the changes or not.
  The function receives the decoded and migrated file data."
  [file-id update-fn & {:keys [rollback?] :or {rollback? true} :as opts}]
  (db/tx-run! (assoc main/system ::db/rollback rollback?)
              (fn [system]
                (binding [h/*system* system]
                  (h/process-file! system file-id update-fn opts)))))

(defn process-team-files!
  "Apply a function to each file of the specified team."
  [team-id update-fn & {:keys [rollback? label] :or {rollback? true} :as opts}]
  (let [team-id (h/parse-uuid team-id)
        opts    (dissoc opts :label)]
    (db/tx-run! (assoc main/system ::db/rollback rollback?)
                (fn [{:keys [::db/conn] :as system}]
                  (when (string? label)
                    (h/take-team-snapshot! system team-id label))

                  (binding [h/*system* system]
                    (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
                         (reduce (fn [result file-id]
                                   (if (h/process-file! system file-id update-fn opts)
                                     (inc result)
                                     result))
                                 0)))))))

(defn process-files!
  "Apply a function to all files in the database"
  [update-fn & {:keys [max-items
                       max-jobs
                       rollback?
                       query]
                :or {max-jobs 1
                     max-items Long/MAX_VALUE
                     rollback? true
                     query sql:get-files}
                :as opts}]

  (l/dbg :hint "process:start"
         :rollback rollback?
         :max-jobs max-jobs
         :max-items max-items)

  (let [tpoint    (dt/tpoint)
        factory   (px/thread-factory :virtual false :prefix "penpot/file-process/")
        executor  (px/cached-executor :factory factory)
        sjobs     (ps/create :permits max-jobs)

        process-file
        (fn [file-id idx tpoint]
          (let [thread-id (px/get-thread-id)]
            (try
              (l/trc :hint "process:file:start"
                     :tid thread-id
                     :file-id (str file-id)
                     :index idx)
              (let [system (assoc main/system ::db/rollback rollback?)]
                (db/tx-run! system (fn [system]
                                     (binding [h/*system* system]
                                       (h/process-file! system file-id update-fn opts)))))

              (catch Throwable cause
                (l/wrn :hint "unexpected error on processing file (skiping)"
                       :tid thread-id
                       :file-id (str file-id)
                       :index idx
                       :cause cause))
              (finally
                (when-let [pause (:pause opts)]
                  (Thread/sleep (int pause)))

                (ps/release! sjobs)
                (let [elapsed (dt/format-duration (tpoint))]
                  (l/trc :hint "process:file:end"
                         :tid thread-id
                         :file-id (str file-id)
                         :index idx
                         :elapsed elapsed))))))

        process-file*
        (fn [idx file-id]
          (ps/acquire! sjobs)
          (px/run! executor (partial process-file file-id idx (dt/tpoint)))
          (inc idx))

        process-files
        (fn [{:keys [::db/conn] :as system}]
          (db/exec! conn ["SET statement_timeout = 0"])
          (db/exec! conn ["SET idle_in_transaction_session_timeout = 0"])

          (try
            (->> (db/plan conn [query])
                 (transduce (comp
                             (take max-items)
                             (map :id))
                            (completing process-file*)
                            0))
            (finally
              ;; Close and await tasks
              (pu/close! executor))))]

    (try
      (db/tx-run! main/system process-files)

      (catch Throwable cause
        (l/dbg :hint "process:error" :cause cause))

      (finally
        (let [elapsed (dt/format-duration (tpoint))]
          (l/dbg :hint "process:end"
                 :rollback rollback?
                 :elapsed elapsed))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DELETE/RESTORE OBJECTS (WITH CASCADE, SOFT)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn delete-file!
  "Mark a project for deletion"
  [file-id]
  (let [file-id (h/parse-uuid file-id)
        tnow    (dt/now)]

    (audit/insert! main/system
                   {::audit/name "delete-file"
                    ::audit/type "action"
                    ::audit/profile-id uuid/zero
                    ::audit/props {:id file-id}
                    ::audit/context {:triggered-by "srepl"
                                     :cause "explicit call to delete-file!"}
                    ::audit/tracked-at tnow})
    (wrk/invoke! (-> main/system
                     (assoc ::wrk/task :delete-object)
                     (assoc ::wrk/params {:object :file
                                          :deleted-at tnow
                                          :id file-id})))
    :deleted))

(defn- restore-file*
  [{:keys [::db/conn]} file-id]
  (db/update! conn :file
              {:deleted-at nil
               :has-media-trimmed false}
              {:id file-id})

  ;; Fragments are not handled here because they
  ;; use the database cascade operation and they
  ;; are not marked for deletion with objects-gc
  ;; task

  (db/update! conn :file-media-object
              {:deleted-at nil}
              {:file-id file-id})

  ;; Mark thumbnails to be deleted
  (db/update! conn :file-thumbnail
              {:deleted-at nil}
              {:file-id file-id})

  (db/update! conn :file-tagged-object-thumbnail
              {:deleted-at nil}
              {:file-id file-id})

  :restored)

(defn restore-file!
  "Mark a file and all related objects as not deleted"
  [file-id]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! main/system
                (fn [system]
                  (when-let [file (some-> (db/get* system :file
                                                   {:id file-id}
                                                   {::db/remove-deleted false
                                                    ::sql/columns [:id :name]})
                                          (files/decode-row))]
                    (audit/insert! system
                                   {::audit/name "restore-file"
                                    ::audit/type "action"
                                    ::audit/profile-id uuid/zero
                                    ::audit/props file
                                    ::audit/context {:triggered-by "srepl"
                                                     :cause "explicit call to restore-file!"}
                                    ::audit/tracked-at (dt/now)})

                    (restore-file* system file-id))))))

(defn delete-project!
  "Mark a project for deletion"
  [project-id]
  (let [project-id (h/parse-uuid project-id)
        tnow       (dt/now)]

    (audit/insert! main/system
                   {::audit/name "delete-project"
                    ::audit/type "action"
                    ::audit/profile-id uuid/zero
                    ::audit/props {:id project-id}
                    ::audit/context {:triggered-by "srepl"
                                     :cause "explicit call to delete-project!"}
                    ::audit/tracked-at tnow})

    (wrk/invoke! (-> main/system
                     (assoc ::wrk/task :delete-object)
                     (assoc ::wrk/params {:object :project
                                          :deleted-at tnow
                                          :id project-id})))
    :deleted))

(defn- restore-project*
  [{:keys [::db/conn] :as cfg} project-id]
  (db/update! conn :project
              {:deleted-at nil}
              {:id project-id})

  (doseq [{:keys [id]} (db/query conn :file
                                 {:project-id project-id}
                                 {::sql/columns [:id]})]
    (restore-file* cfg id))

  :restored)

(defn restore-project!
  "Mark a project and all related objects as not deleted"
  [project-id]
  (let [project-id (h/parse-uuid project-id)]
    (db/tx-run! main/system
                (fn [system]
                  (when-let [project (db/get* system :project
                                              {:id project-id}
                                              {::db/remove-deleted false})]
                    (audit/insert! system
                                   {::audit/name "restore-project"
                                    ::audit/type "action"
                                    ::audit/profile-id uuid/zero
                                    ::audit/props project
                                    ::audit/context {:triggered-by "srepl"
                                                     :cause "explicit call to restore-team!"}
                                    ::audit/tracked-at (dt/now)})

                    (restore-project* system project-id))))))

(defn delete-team!
  "Mark a team for deletion"
  [team-id]
  (let [team-id (h/parse-uuid team-id)
        tnow    (dt/now)]

    (audit/insert! main/system
                   {::audit/name "delete-team"
                    ::audit/type "action"
                    ::audit/profile-id uuid/zero
                    ::audit/props {:id team-id}
                    ::audit/context {:triggered-by "srepl"
                                     :cause "explicit call to delete-profile!"}
                    ::audit/tracked-at tnow})

    (wrk/invoke! (-> main/system
                     (assoc ::wrk/task :delete-object)
                     (assoc ::wrk/params {:object :team
                                          :deleted-at tnow
                                          :id team-id})))
    :deleted))

(defn- restore-team*
  [{:keys [::db/conn] :as cfg} team-id]
  (db/update! conn :team
              {:deleted-at nil}
              {:id team-id})

  (db/update! conn :team-font-variant
              {:deleted-at nil}
              {:team-id team-id})

  (doseq [{:keys [id]} (db/query conn :project
                                 {:team-id team-id}
                                 {::sql/columns [:id]})]
    (restore-project* cfg id))

  :restored)

(defn restore-team!
  "Mark a team and all related objects as not deleted"
  [team-id]
  (let [team-id (h/parse-uuid team-id)]
    (db/tx-run! main/system
                (fn [system]
                  (when-let [team (some-> (db/get* system :team
                                                   {:id team-id}
                                                   {::db/remove-deleted false})
                                          (teams/decode-row))]
                    (audit/insert! system
                                   {::audit/name "restore-team"
                                    ::audit/type "action"
                                    ::audit/profile-id uuid/zero
                                    ::audit/props team
                                    ::audit/context {:triggered-by "srepl"
                                                     :cause "explicit call to restore-team!"}
                                    ::audit/tracked-at (dt/now)})

                    (restore-team* system team-id))))))

(defn delete-profile!
  "Mark a profile for deletion."
  [profile-id]
  (let [profile-id (h/parse-uuid profile-id)
        tnow       (dt/now)]

    (audit/insert! main/system
                   {::audit/name "delete-profile"
                    ::audit/type "action"
                    ::audit/profile-id uuid/zero
                    ::audit/context {:triggered-by "srepl"
                                     :cause "explicit call to delete-profile!"}
                    ::audit/tracked-at tnow})

    (wrk/invoke! (-> main/system
                     (assoc ::wrk/task :delete-object)
                     (assoc ::wrk/params {:object :profile
                                          :deleted-at tnow
                                          :id profile-id})))
    :deleted))

(defn restore-profile!
  "Mark a team and all related objects as not deleted"
  [profile-id]
  (let [profile-id (h/parse-uuid profile-id)]
    (db/tx-run! main/system
                (fn [system]
                  (when-let [profile (some-> (db/get* system :profile
                                                      {:id profile-id}
                                                      {::db/remove-deleted false})
                                             (profile/decode-row))]
                    (audit/insert! system
                                   {::audit/name "restore-profile"
                                    ::audit/type "action"
                                    ::audit/profile-id uuid/zero
                                    ::audit/props (audit/profile->props profile)
                                    ::audit/context {:triggered-by "srepl"
                                                     :cause "explicit call to restore-profile!"}
                                    ::audit/tracked-at (dt/now)})

                    (db/update! system :profile
                                {:deleted-at nil}
                                {:id profile-id}
                                {::db/return-keys false})

                    (doseq [{:keys [id]} (profile/get-owned-teams system profile-id)]
                      (restore-team* system id))

                    :restored)))))

(defn delete-profiles-in-bulk!
  [system path]
  (letfn [(process-data! [system deleted-at emails]
            (loop [emails  emails
                   deleted 0
                   total   0]
              (if-let [email (first emails)]
                (if-let [profile (some-> (db/get* system :profile
                                                  {:email (str/lower email)}
                                                  {::db/remove-deleted false})
                                         (profile/decode-row))]
                  (do
                    (audit/insert! system
                                   {::audit/name "delete-profile"
                                    ::audit/type "action"
                                    ::audit/profile-id (:id profile)
                                    ::audit/tracked-at deleted-at
                                    ::audit/props (audit/profile->props profile)
                                    ::audit/context {:triggered-by "srepl"
                                                     :cause "explicit call to delete-profiles-in-bulk!"}})
                    (wrk/invoke! (-> system
                                     (assoc ::wrk/task :delete-object)
                                     (assoc ::wrk/params {:object :profile
                                                          :deleted-at deleted-at
                                                          :id (:id profile)})))
                    (recur (rest emails)
                           (inc deleted)
                           (inc total)))
                  (recur (rest emails)
                         deleted
                         (inc total)))
                {:deleted deleted :total total})))]

    (let [path       (fs/path path)
          deleted-at (dt/minus (dt/now) (cf/get-deletion-delay))]

      (when-not (fs/exists? path)
        (throw (ex-info "path does not exists" {:path path})))

      (db/tx-run! system
                  (fn [system]
                    (with-open [reader (io/reader path)]
                      (process-data! system deleted-at (line-seq reader))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CASCADE FIXING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-deleted-profiles-cascade
  []
  (->> (db/exec! main/system ["select id, deleted_at from profile where deleted_at is not null"])
       (run! (fn [{:keys [id deleted-at]}]
               (wrk/invoke! (-> main/system
                                (assoc ::wrk/task :delete-object)
                                (assoc ::wrk/params {:object :profile
                                                     :deleted-at deleted-at
                                                     :id id})))))))

(defn process-deleted-teams-cascade
  []
  (->> (db/exec! main/system ["select id, deleted_at from team where deleted_at is not null"])
       (run! (fn [{:keys [id deleted-at]}]
               (wrk/invoke! (-> main/system
                                (assoc ::wrk/task :delete-object)
                                (assoc ::wrk/params {:object :team
                                                     :deleted-at deleted-at
                                                     :id id})))))))

(defn process-deleted-projects-cascade
  []
  (->> (db/exec! main/system ["select id, deleted_at from project where deleted_at is not null"])
       (run! (fn [{:keys [id deleted-at]}]
               (wrk/invoke! (-> main/system
                                (assoc ::wrk/task :delete-object)
                                (assoc ::wrk/params {:object :project
                                                     :deleted-at deleted-at
                                                     :id id})))))))

(defn process-deleted-files-cascade
  []
  (->> (db/exec! main/system ["select id, deleted_at from file where deleted_at is not null"])
       (run! (fn [{:keys [id deleted-at]}]
               (wrk/invoke! (-> main/system
                                (assoc ::wrk/task :delete-object)
                                (assoc ::wrk/params {:object :file
                                                     :deleted-at deleted-at
                                                     :id id})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MISC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn instrument-var
  [var]
  (alter-var-root var (fn [f]
                        (let [mf (meta f)]
                          (if (::original mf)
                            f
                            (with-meta
                              (fn [& params]
                                (tap> params)
                                (let [result (apply f params)]
                                  (tap> result)
                                  result))
                              {::original f}))))))

(defn uninstrument-var
  [var]
  (alter-var-root var (fn [f]
                        (or (::original (meta f)) f))))


(defn duplicate-team
  [team-id & {:keys [name]}]
  (let [team-id (h/parse-uuid team-id)]
    (db/tx-run! main/system
                (fn [{:keys [::db/conn] :as cfg}]
                  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
                  (let [team (-> (assoc cfg ::bfc/timestamp (dt/now))
                                 (mgmt/duplicate-team :team-id team-id :name name))
                        rels (db/query conn :team-profile-rel {:team-id team-id})]

                    (doseq [rel rels]
                      (let [params (-> rel
                                       (assoc :id (uuid/next))
                                       (assoc :team-id (:id team)))]
                        (db/insert! conn :team-profile-rel params
                                    {::db/return-keys false}))))))))
