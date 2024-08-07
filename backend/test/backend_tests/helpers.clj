;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.helpers
  (:require
   [app.auth]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.flags :as flags]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as main]
   [app.media]
   [app.media :as-alias mtx]
   [app.migrations]
   [app.msgbus :as-alias mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.files-create :as files.create]
   [app.rpc.commands.files-update :as files.update]
   [app.rpc.commands.teams :as teams]
   [app.rpc.helpers :as rph]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [app.worker.runner]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [environ.core :refer [env]]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [mockery.core :as mk]
   [promesa.core :as p]
   [promesa.exec :as px]
   [ring.response :as rres]
   [yetti.request :as yrq])
  (:import
   java.io.PipedInputStream
   java.io.PipedOutputStream
   java.util.UUID
   org.postgresql.ds.PGSimpleDataSource))

(def ^:dynamic *system* nil)
(def ^:dynamic *pool* nil)

(def default
  {:database-uri "postgresql://postgres/penpot_test"
   :redis-uri "redis://redis/1"
   :file-snapshot-every 1})

(def config
  (cf/read-config :prefix "penpot-test"
                  :default (merge cf/default default)))

(def default-flags
  [:enable-secure-session-cookies
   :enable-email-verification
   :enable-smtp
   :enable-quotes
   :enable-rpc-climit
   :enable-feature-fdata-pointer-map
   :enable-feature-fdata-objets-map
   :enable-feature-components-v2
   :enable-file-snapshot
   :disable-file-validation])

(defn state-init
  [next]
  (with-redefs [app.config/flags (flags/parse flags/default default-flags)
                app.config/config config
                app.loggers.audit/submit! (constantly nil)
                app.auth/derive-password identity
                app.auth/verify-password (fn [a b] {:valid (= a b)})
                app.common.features/get-enabled-features (fn [& _] app.common.features/supported-features)]

    (cf/validate! :exit-on-error? false)

    (fs/create-dir "/tmp/penpot")

    (let [templates [{:id "test"
                      :name "test"
                      :file-uri "test"
                      :thumbnail-uri "test"
                      :path (-> "backend_tests/test_files/template.penpot" io/resource fs/path)}]
          system (-> (merge main/system-config main/worker-config)
                     (assoc-in [:app.redis/redis :app.redis/uri] (:redis-uri config))
                     (assoc-in [::db/pool ::db/uri] (:database-uri config))
                     (assoc-in [::db/pool ::db/username] (:database-username config))
                     (assoc-in [::db/pool ::db/password] (:database-password config))
                     (assoc-in [:app.rpc/methods :app.setup/templates] templates)
                     (dissoc :app.srepl/server
                             :app.http/server
                             :app.http/router
                             :app.auth.oidc.providers/google
                             :app.auth.oidc.providers/gitlab
                             :app.auth.oidc.providers/github
                             :app.auth.oidc.providers/generic
                             :app.setup/templates
                             :app.auth.oidc/routes
                             :app.worker/monitor
                             :app.http.oauth/handler
                             :app.notifications/handler
                             :app.loggers.mattermost/reporter
                             :app.loggers.database/reporter
                             :app.worker/cron
                             :app.worker/dispatcher
                             [:app.main/default :app.worker/runner]
                             [:app.main/webhook :app.worker/runner]))
          _      (ig/load-namespaces system)
          system (-> (ig/prep system)
                     (ig/init))]
      (try
        (binding [*system* system
                  *pool*   (:app.db/pool system)]
          (next))
        (finally
          (ig/halt! system))))))

(defn database-reset
  [next]
  (let [sql (str "SELECT table_name "
                 "  FROM information_schema.tables "
                 " WHERE table_schema = 'public' "
                 "   AND table_name != 'migrations';")]
    (db/with-atomic [conn *pool*]
      (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
      (db/exec-one! conn ["SET LOCAL rules.deletion_protection TO off"])
      (let [result (->> (db/exec! conn [sql])
                        (map :table-name))]
        (doseq [table result]
          (db/exec! conn [(str "delete from " table ";")]))))

    (next)))

(defn clean-storage
  [next]
  (let [path (fs/path "/tmp/penpot")]
    (when (fs/exists? path)
      (fs/delete (fs/path "/tmp/penpot")))
    (fs/create-dir "/tmp/penpot")
    (next)))

(defn serial
  [& funcs]
  (fn [next]
    (loop [f   (first funcs)
           fs  (rest funcs)]
      (when f
        (let [prm (promise)]
          (f #(deliver prm true))
          (deref prm)
          (recur (first fs)
                 (rest fs)))))
    (next)))

(defn mk-uuid
  [prefix & args]
  (UUID/nameUUIDFromBytes (-> (apply str prefix args)
                              (.getBytes "UTF-8"))))
;; --- FACTORIES

(defn create-profile*
  ([i] (create-profile* *system* i {}))
  ([i params] (create-profile* *system* i params))
  ([system i params]
   (let [params (merge {:id (mk-uuid "profile" i)
                        :fullname (str "Profile " i)
                        :email (str "profile" i ".test@nodomain.com")
                        :password "123123"
                        :is-demo false}
                       params)]
     (db/run! system
              (fn [{:keys [::db/conn]}]
                (->> params
                     (cmd.auth/create-profile! conn)
                     (cmd.auth/create-profile-rels! conn)))))))

(defn create-project*
  ([i params] (create-project* *system* i params))
  ([system i {:keys [profile-id team-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? team-id)

   (db/run! system
            (fn [{:keys [::db/conn]}]
              (->> (merge {:id (mk-uuid "project" i)
                           :name (str "project" i)}
                          params)
                   (#'teams/create-project conn))))))

(defn create-file*
  ([i params]
   (create-file* *system* i params))
  ([system i {:keys [profile-id project-id] :as params}]
   (dm/assert! "expected uuid" (uuid? profile-id))
   (dm/assert! "expected uuid" (uuid? project-id))
   (db/run! system
            (fn [system]
              (let [features (cfeat/get-enabled-features cf/flags)]
                (files.create/create-file system
                                          (merge {:id (mk-uuid "file" i)
                                                  :name (str "file" i)
                                                  :features features}
                                                 params)))))))

(defn mark-file-deleted*
  ([params]
   (mark-file-deleted* *system* params))
  ([conn {:keys [id] :as params}]
   (#'files/mark-file-deleted conn id)))

(defn create-team*
  ([i params] (create-team* *system* i params))
  ([system i {:keys [profile-id] :as params}]
   (us/assert uuid? profile-id)
   (dm/with-open [conn (db/open system)]
     (let [id       (mk-uuid "team" i)
           features (cfeat/get-enabled-features cf/flags)]
       (teams/create-team conn {:id id
                                :profile-id profile-id
                                :features features
                                :name (str "team" i)})))))

(defn create-file-media-object*
  ([params] (create-file-media-object* *system* params))
  ([system {:keys [name width height mtype file-id is-local media-id]
            :or {name "sample" width 100 height 100 mtype "image/svg+xml" is-local true}}]

   (dm/with-open [conn (db/open system)]
     (db/insert! conn :file-media-object
                 {:id (uuid/next)
                  :file-id file-id
                  :is-local is-local
                  :name name
                  :media-id media-id
                  :width  width
                  :height height
                  :mtype  mtype}))))

(defn link-file-to-library*
  ([params] (link-file-to-library* *system* params))
  ([system {:keys [file-id library-id] :as params}]
   (dm/with-open [conn (db/open system)]
     (#'files/link-file-to-library conn {:file-id file-id :library-id library-id}))))

(defn create-complaint-for
  [system {:keys [id created-at type]}]
  (dm/with-open [conn (db/open system)]
    (db/insert! conn :profile-complaint-report
                {:profile-id id
                 :created-at (or created-at (dt/now))
                 :type (name type)
                 :content (db/tjson {})})))

(defn create-global-complaint-for
  [system {:keys [email type created-at]}]
  (dm/with-open [conn (db/open system)]
    (db/insert! conn :global-complaint-report
                {:email email
                 :type (name type)
                 :created-at (or created-at (dt/now))
                 :content (db/tjson {})})))

(defn create-team-role*
  ([params] (create-team-role* *system* params))
  ([system {:keys [team-id profile-id role] :or {role :owner}}]
   (dm/with-open [conn (db/open system)]
     (#'teams/create-team-role conn {:team-id team-id
                                     :profile-id profile-id
                                     :role role}))))

(defn create-project-role*
  ([params] (create-project-role* *system* params))
  ([system {:keys [project-id profile-id role] :or {role :owner}}]
   (dm/with-open [conn (db/open system)]
     (#'teams/create-project-role conn {:project-id project-id
                                        :profile-id profile-id
                                        :role role}))))

(defn create-file-role*
  ([params] (create-file-role* *system* params))
  ([system {:keys [file-id profile-id role] :or {role :owner}}]
   (dm/with-open [conn (db/open system)]
     (files.create/create-file-role! conn {:file-id file-id
                                           :profile-id profile-id
                                           :role role}))))

(defn update-file*
  ([params] (update-file* *system* params))
  ([system {:keys [file-id changes session-id profile-id revn]
            :or {session-id (uuid/next) revn 0}}]
   (db/tx-run! system (fn [{:keys [::db/conn] :as system}]
                        (let [file (files.update/get-file conn file-id)]
                          (files.update/update-file system
                                                    {:id file-id
                                                     :revn revn
                                                     :file file
                                                     :features (:features file)
                                                     :changes changes
                                                     :session-id session-id
                                                     :profile-id profile-id}))))))

(declare command!)

(defn update-file! [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [features (cfeat/get-enabled-features cf/flags)
        params   {::type :update-file
                  ::rpc/profile-id profile-id
                  :id file-id
                  :session-id (uuid/random)
                  :revn revn
                  :features features
                  :changes changes}
        out      (command! params)]
    (t/is (nil? (:error out)))
    (:result out)))

(defn create-webhook*
  ([params] (create-webhook* *system* params))
  ([system {:keys [team-id id uri mtype is-active]
            :or {is-active true
                 mtype "application/json"
                 uri "http://example.com/webhook"}}]
   (db/run! system (fn [{:keys [::db/conn]}]
                     (db/insert! conn :webhook
                                 {:id (or id (uuid/next))
                                  :team-id team-id
                                  :uri uri
                                  :is-active is-active
                                  :mtype mtype})))))

;; --- RPC HELPERS

(defn handle-error
  [^Throwable err]
  (if (instance? java.util.concurrent.ExecutionException err)
    (handle-error (.getCause err))
    err))

(defmacro try-on!
  [expr]
  `(try
     (let [result# ~expr
           result# (cond-> result# (rph/wrapped? result#) deref)]
       {:error nil
        :result result#})
     (catch Exception e#
       {:error (handle-error e#)
        :result nil})))

(defn command!
  [{:keys [::type] :as data}]
  (let [[mdata method-fn] (get-in *system* [:app.rpc/methods type])]
    (when-not method-fn
      (ex/raise :type :assertion
                :code :rpc-method-not-found
                :hint (str/ffmt "rpc method '%' not found" (name type))))

    ;; (app.common.pprint/pprint (:app.rpc/methods *system*))
    (try-on! (method-fn (-> data
                            (dissoc ::type)
                            (assoc :app.rpc/request-at (dt/now)))))))

(defn run-task!
  ([name]
   (run-task! name {}))
  ([name params]
   (wrk/invoke! (-> *system*
                    (assoc ::wrk/task name)
                    (assoc ::wrk/params params)))))

(def sql:pending-tasks
  "select t.* from task as t
    where t.status = 'new'
    order by t.priority desc, t.scheduled_at")

(defn run-pending-tasks!
  []
  (db/tx-run! *system* (fn [{:keys [::db/conn] :as cfg}]
                         (let [tasks (->> (db/exec! conn [sql:pending-tasks])
                                          (map #'app.worker.runner/decode-task-row))]
                           (run! (partial #'app.worker.runner/run-task cfg) tasks)))))

;; --- UTILS

(defn print-error!
  [error]
  (let [data (ex-data error)]
    (cond
      (= :spec-validation (:code data))
      (println
       (us/pretty-explain data))

      (= :params-validation (:code data))
      (println
       (sm/humanize-explain (::sm/explain data)))

      (= :data-validation (:code data))
      (println
       (sm/humanize-explain (::sm/explain data)))

      (= :service-error (:type data))
      (print-error! (.getCause ^Throwable error))

      :else
      (.printStackTrace ^Throwable error))))

(defn print-result!
  [{:keys [error result]}]
  (if error
    (do
      (println "====> START ERROR")
      (print-error! error)
      (println "====> END ERROR"))
    (do
      (println "====> START RESPONSE")
      (pp/pprint result)
      (println "====> END RESPONSE"))))

(defn exception?
  [v]
  (instance? Throwable v))

(defn ex-info?
  [v]
  (ex/error? v))

(defn ex-type
  [e]
  (:type (ex-data e)))

(defn ex-code
  [e]
  (:code (ex-data e)))

(defn ex-of-type?
  [e type]
  (let [data (ex-data e)]
    (= type (:type data))))

(defn ex-of-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

(defn ex-with-code?
  [e code]
  (let [data (ex-data e)]
    (= code (:code data))))

(defn success?
  [{:keys [result error]}]
  (nil? error))

(defn tempfile
  [source]
  (let [rsc (io/resource source)
        tmp (fs/create-tempfile :dir "/tmp/penpot" :prefix "test-")]
    (io/copy (io/file rsc)
             (io/file tmp))
    tmp))

(defn pause
  []
  (let [^java.io.Console cnsl (System/console)]
    (println "[waiting RETURN]")
    (.readLine cnsl)
    nil))

(defn db-exec!
  [sql]
  (db/exec! *pool* sql))

(defn db-exec-one!
  [sql]
  (db/exec-one! *pool* sql))

(defn db-delete!
  [& params]
  (apply db/delete! *pool* params))

(defn db-update!
  [& params]
  (apply db/update! *pool* params))

(defn db-insert!
  [& params]
  (apply db/insert! *pool* params))

(defn db-delete!
  [& params]
  (apply db/delete! *pool* params))

(defn db-query
  [& params]
  (apply db/query *pool* params))

(defn db-get
  [& params]
  (apply db/get* *pool* params))

(defn sleep
  [ms-or-duration]
  (Thread/sleep (inst-ms (dt/duration ms-or-duration))))

(defn config-get-mock
  [data]
  (fn
    ([key]
     (get data key (get cf/config key)))
    ([key default]
     (get data key (get cf/config key default)))))

(defn reset-mock!
  [m]
  (swap! m (fn [m]
             (-> m
                 (assoc :called? false)
                 (assoc :call-count 0)
                 (assoc :return-list [])
                 (assoc :call-args nil)
                 (assoc :call-args-list [])))))

(defn- slurp'
  [input & opts]
  (let [sw (java.io.StringWriter.)]
    (with-open [^java.io.Reader r (java.io.InputStreamReader. input "UTF-8")]
      (io/copy r sw)
      (.toString sw))))

(defn consume-sse
  [callback]
  (let [{:keys [::rres/status ::rres/body ::rres/headers] :as response} (callback {})
        output (PipedOutputStream.)
        input  (PipedInputStream. output)]

    (try
      (px/exec! :virtual #(rres/-write-body-to-stream body nil output))
      (into []
            (map (fn [event]
                   (let [[item1 item2] (re-seq #"(.*): (.*)\n?" event)]
                     [(keyword (nth item1 2))
                      (tr/decode-str (nth item2 2))])))
            (-> (slurp' input)
                (str/split "\n\n")))
      (finally
        (.close input)))))
