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
   [app.common.logging :as l]
   [app.common.pprint :as p]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as features.fdata]
   [app.main :as main]
   [app.msgbus :as mbus]
   [app.rpc.commands.auth :as auth]
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

(defn print-available-tasks
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
        profile (profile/get-profile-by-email pool email)]

    (auth/send-email-verification! pool sprops profile)
    :email-sent))

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

(defn enable-objects-map-feature-on-file!
  [& {:keys [save? id]}]
  (h/update-file! main/system
                  :id id
                  :update-fn features.fdata/enable-objects-map
                  :save? save?))

(defn enable-pointer-map-feature-on-file!
  [& {:keys [save? id]}]
  (h/update-file! main/system
                  :id id
                  :update-fn features.fdata/enable-pointer-map
                  :save? save?))

(defn enable-team-feature!
  [team-id feature]
  (dm/verify!
   "feature should be supported"
   (contains? cfeat/supported-features feature))

  (let [team-id (if (string? team-id)
                  (parse-uuid team-id)
                  team-id)]
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

  (let [team-id (if (string? team-id)
                  (parse-uuid team-id)
                  team-id)]
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

(defn enable-storage-features-on-file!
  [& {:as params}]
  (enable-objects-map-feature-on-file! main/system params)
  (enable-pointer-map-feature-on-file! main/system params))

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

          (parse-uuid [v]
            (if (uuid? v)
              v
              (d/parse-uuid v)))

          (resolve-dest [dest]
            (cond
              (uuid? dest)
              [dest]

              (string? dest)
              (some-> dest parse-uuid resolve-dest)

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
                               (keep parse-uuid))
                              param)

                    (uuid? param)
                    (resolve-team param)

                    (string? param)
                    (some-> param parse-uuid resolve-team))

                  (= op :profile-id)
                  (if (coll? param)
                    (sequence (keep parse-uuid) param)
                    (resolve-dest param))))))]

    (->> (resolve-dest dest)
         (filter some?)
         (into #{})
         (run! send))))

(defn duplicate-team
  [team-id & {:keys [name]}]
  (let [team-id (if (string? team-id) (parse-uuid team-id) team-id)
        name    (or name (fn [prev-name]
                           (str/ffmt "Cloned: % (%)" prev-name (dt/format-instant (dt/now)))))]
    (db/tx-run! main/system
                (fn [cfg]
                  (db/exec-one! cfg ["SET CONSTRAINTS ALL DEFERRED"])
                  (-> (assoc cfg ::bfc/timestamp (dt/now))
                      (mgmt/duplicate-team :team-id team-id :name name))))))
