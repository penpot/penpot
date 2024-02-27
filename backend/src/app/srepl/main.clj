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
   [app.features.components-v2 :as feat.comp-v2]
   [app.features.fdata :as feat.fdata]
   [app.main :as main]
   [app.msgbus :as mbus]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-snapshot :as fsnap]
   [app.rpc.commands.management :as mgmt]
   [app.rpc.commands.profile :as profile]
   [app.srepl.fixes :as fixes]
   [app.srepl.helpers :as h]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.pprint :refer [print-table]]
   [clojure.stacktrace :as strace]
   [clojure.tools.namespace.repl :as repl]
   [cuerdas.core :as str]
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
   (let [tasks (:app.worker/registry main/system)
         tname (if (keyword? tname) (name tname) name)]
     (if-let [task-fn (get tasks tname)]
       (task-fn params)
       (println (format "no task '%s' found" tname))))))

(defn schedule-task!
  ([name]
   (schedule-task! name {}))
  ([name props]
   (let [pool (:app.db/pool main/system)]
     (wrk/submit!
      ::wrk/conn pool
      ::wrk/task name
      ::wrk/props props))))

(defn send-test-email!
  [destination]
  (us/verify!
   :expr (string? destination)
   :hint "destination should be provided")

  (let [handler (:app.email/sendmail main/system)]
    (handler {:body "test email"
              :subject "test email"
              :to [destination]})))

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
  (db/with-atomic [conn (:app.db/pool main/system)]
    (when-let [profile (db/get* conn :profile
                                {:email (str/lower email)}
                                {:columns [:id :email]})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-active true} {:id (:id profile)})
        :activated))))

(defn mark-profile-as-blocked!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [email]
  (db/with-atomic [conn (:app.db/pool main/system)]
    (when-let [profile (db/get* conn :profile
                                {:email (str/lower email)}
                                {:columns [:id :email]})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
        (db/delete! conn :http-session {:profile-id (:id profile)})
        :blocked))))

(defn reset-password!
  "Reset a password to a specific one for a concrete user or all users
  if email is `:all` keyword."
  [& {:keys [email password] :or {password "123123"} :as params}]
  (us/verify! (contains? params :email) "`email` parameter is mandatory")
  (db/with-atomic [conn (:app.db/pool main/system)]
    (let [password (derive-password password)]
      (if (= email :all)
        (db/exec! conn ["update profile set password=?" password])
        (let [email (str/lower email)]
          (db/exec! conn ["update profile set password=? where email=?" password email]))))))

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
  [team-id feature]
  (dm/verify!
   "feature should be supported"
   (contains? cfeat/supported-features feature))

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
  [team-id feature]
  (dm/verify!
   "feature should be supported"
   (contains? cfeat/supported-features feature))

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
  [{:keys [::mbus/msgbus ::db/pool]} & {:keys [dest code message level]
                                        :or {code :generic level :info}
                                        :as params}]
  (dm/verify!
   ["invalid level %" level]
   (contains? #{:success :error :info :warning} level))

  (dm/verify!
   ["invalid code: %" code]
   (contains? #{:generic :upgrade-version} code))

  (letfn [(send [dest]
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
                         :topic (str dest)
                         :message message)))

          (resolve-profile [email]
            (some-> (db/get* pool :profile {:email (str/lower email)} {:columns [:id]}) :id vector))

          (resolve-team [team-id]
            (->> (db/query pool :team-profile-rel
                           {:team-id team-id}
                           {:columns [:profile-id]})
                 (map :profile-id)))

          (resolve-dest [dest]
            (cond
              (uuid? dest)
              [dest]

              (string? dest)
              (some-> dest h/parse-uuid resolve-dest)

              (nil? dest)
              (resolve-dest uuid/zero)

              (map? dest)
              (sequence (comp
                         (map vec)
                         (mapcat resolve-dest))
                        dest)

              (and (coll? dest)
                   (every? coll? dest))
              (sequence (comp
                         (map vec)
                         (mapcat resolve-dest))
                        dest)

              (vector? dest)
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
    (db/tx-run! main/system fsnap/take-file-snapshot! {:file-id file-id :label label})))

(defn restore-file-snapshot!
  [file-id label]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! main/system
                (fn [{:keys [::db/conn] :as system}]
                  (when-let [snapshot (->> (h/get-file-snapshots conn label #{file-id})
                                           (map :id)
                                           (first))]
                    (fsnap/restore-file-snapshot! system
                                                  {:id (:id snapshot)
                                                   :file-id file-id}))))))

(defn list-file-snapshots!
  [file-id & {:keys [limit]}]
  (let [file-id (h/parse-uuid file-id)]
    (db/tx-run! main/system
                (fn [system]
                  (let [params {:file-id file-id :limit limit}]
                    (->> (fsnap/get-file-snapshots system (d/without-nils params))
                         (print-table [:label :id :revn :created-at])))))))

(defn take-team-snapshot!
  [team-id & {:keys [label rollback?] :or {rollback? true}}]
  (let [team-id (h/parse-uuid team-id)
        label   (or label (fsnap/generate-snapshot-label))]
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
          (try
            (l/trc :hint "process:file:start" :file-id (str file-id) :index idx)
            (let [system (assoc main/system ::db/rollback rollback?)]
              (db/tx-run! system (fn [system]
                                   (binding [h/*system* system]
                                     (h/process-file! system file-id update-fn opts)))))

            (catch Throwable cause
              (l/wrn :hint "unexpected error on processing file (skiping)"
                     :file-id (str file-id)
                     :index idx
                     :cause cause))
            (finally
              (ps/release! sjobs)
              (let [elapsed (dt/format-duration (tpoint))]
                (l/trc :hint "process:file:end"
                       :file-id (str file-id)
                       :index idx
                       :elapsed elapsed)))))

        process-files
        (fn [{:keys [::db/conn] :as system}]
          (db/exec! conn ["SET statement_timeout = 0"])
          (db/exec! conn ["SET idle_in_transaction_session_timeout = 0"])

          (try
            (reduce (fn [idx file-id]
                      (ps/acquire! sjobs)
                      (px/run! executor (partial process-file file-id idx (dt/tpoint)))
                      (inc idx))
                    0
                    (->> (db/cursor conn [query] {:chunk-size 1})
                         (take max-items)
                         (map :id)))
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
