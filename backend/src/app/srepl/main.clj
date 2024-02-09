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
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.repair :as cfr]
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
   [app.srepl.cli :as cli]
   [app.srepl.helpers :as h]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.pprint :refer [pprint print-table]]
   [clojure.tools.namespace.repl :as repl]
   [cuerdas.core :as str]))

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
  (let [sprops  (:app.setup/props main/system)
        pool    (:app.db/pool main/system)
        email   (profile/clean-email email)
        profile (profile/get-profile-by-email pool email)]

    (auth/send-email-verification! pool sprops profile)
    :email-sent))

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

(defn enable-objects-map-feature-on-file!
  [& {:keys [save? id]}]
  (h/process-file! main/system
                   :id id
                   :update-fn feat.fdata/enable-objects-map
                   :save? save?))

(defn enable-pointer-map-feature-on-file!
  [& {:keys [save? id]}]
  (h/process-file! main/system
                   :id id
                   :update-fn feat.fdata/enable-pointer-map
                   :save? save?))

(defn enable-storage-features-on-file!
  [& {:as params}]
  (enable-objects-map-feature-on-file! main/system params)
  (enable-pointer-map-feature-on-file! main/system params))

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
  [& {:keys [file-id id]}]
  (db/tx-run! main/system
              (fn [cfg]
                (let [file-id (h/parse-uuid file-id)
                      id      (h/parse-uuid id)]
                  (if (and (uuid? id) (uuid? file-id))
                    (fsnap/restore-file-snapshot! cfg {:id id :file-id file-id})
                    (println "=> invalid parameters"))))))


(defn list-file-snapshots!
  [& {:keys [file-id limit]}]
  (db/tx-run! main/system
              (fn [system]
                (let [params {:file-id (h/parse-uuid file-id)
                              :limit limit}]
                  (->> (fsnap/get-file-snapshots system (d/without-nils params))
                       (print-table [:id :revn :created-at :label]))))))

(defn take-team-snapshot!
  [& {:keys [team-id label rollback?]
      :or {rollback? true}}]
  (let [team-id (h/parse-uuid team-id)
        label   (or label (fsnap/generate-snapshot-label))

        take-snapshot
        (fn [{:keys [::db/conn] :as system}]
          (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
               (map (fn [file-id]
                      {:file-id file-id
                       :label label}))
               (run! (partial fsnap/take-file-snapshot! system))))]

    (-> (assoc main/system ::db/rollback rollback?)
        (db/tx-run! take-snapshot))))

(def ^:private sql:snapshots-with-file
  "WITH files AS (
     SELECT f.id AS file_id,
            (SELECT fc.id
               FROM file_change AS fc
              WHERE fc.label = ?
                AND fc.file_id = f.id
              ORDER BY fc.created_at DESC
              LIMIT 1) AS id
       FROM file AS f
   ) SELECT * FROM files
      WHERE file_id = ANY(?)
        AND id IS NOT NULL")

(defn restore-team-snapshot!
  "Restore a snapshot on all files of the team. The snapshot should
  exists for all files; if is not the case, an exception is raised."
  [& {:keys [team-id label rollback?] :or {rollback? true}}]
  (let [team-id (h/parse-uuid team-id)

        get-file-snapshots
        (fn [conn ids]
          (db/exec! conn [sql:snapshots-with-file label
                          (db/create-array conn "uuid" ids)]))

        restore-snapshot
        (fn [{:keys [::db/conn] :as system}]
          (let [ids  (->> (feat.comp-v2/get-and-lock-team-files conn team-id)
                          (into #{}))
                snap (get-file-snapshots conn ids)
                ids' (into #{} (map :file-id) snap)
                team (-> (feat.comp-v2/get-team conn team-id)
                         (update :features disj "components/v2"))]

            (when (not= ids ids')
              (throw (RuntimeException. "no uniform snapshot available")))

            (feat.comp-v2/update-team! conn team)
            (run! (partial fsnap/restore-file-snapshot! system) snap)))]

    (-> (assoc main/system ::db/rollback rollback?)
        (db/tx-run! restore-snapshot))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE VALIDATION & REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-file
  "Validate structure, referencial integrity and semantic coherence of
  all contents of a file. Returns a list of errors."
  [id]
  (db/tx-run! main/system
              (fn [{:keys [::db/conn] :as system}]
                (let [id   (if (string? id) (parse-uuid id) id)
                      file (h/get-file system id)
                      libs (->> (files/get-file-libraries conn id)
                                (into [file] (map (fn [{:keys [id]}]
                                                    (h/get-file system id))))
                                (d/index-by :id))]
                  (cfv/validate-file file libs)))))

(defn- repair-file*
  "Internal helper for validate and repair the file. The operation is
  applied multiple times untile file is fixed or max iteration counter
  is reached (default 10)"
  [system id & {:keys [max-iterations label] :or {max-iterations 10}}]
  (let [id (parse-uuid id)

        validate-and-repair
        (fn [file libs iteration]
          (when-let [errors (not-empty (cfv/validate-file file libs))]
            (l/trc :hint "repairing file"
                   :file-id (str id)
                   :iteration iteration
                   :errors (count errors))
            (let [changes (cfr/repair-file file libs errors)]
              (-> file
                  (update :revn inc)
                  (update :data cpc/process-changes changes)))))

        process-file
        (fn [file libs]
          (loop [file      file
                 iteration 0]
            (if (< iteration max-iterations)
              (if-let [file (validate-and-repair file libs iteration)]
                (recur file (inc iteration))
                file)
              (do
                (l/wrn :hint "max retry num reached on repairing file"
                       :file-id (str id)
                       :iteration iteration)
                file))))]

    (db/tx-run! system
                (fn [{:keys [::db/conn] :as system}]
                  (when (string? label)
                    (fsnap/take-file-snapshot! system {:file-id id :label label}))

                  (let [file (h/get-file system id)
                        libs (->> (files/get-file-libraries conn id)
                                  (into [file] (map (fn [{:keys [id]}]
                                                      (h/get-file system id))))
                                  (d/index-by :id))
                        file (process-file file libs)]
                    (h/update-file! system file))))))

(defn repair-file!
  "Repair the list of errors detected by validation."
  [file-id & {:keys [rollback?] :or {rollback? true} :as opts}]
  (let [system (assoc main/system ::db/rollback rollback?)]
    (repair-file* system file-id (dissoc opts :rollback?))))

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
