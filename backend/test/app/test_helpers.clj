;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.test-helpers
  (:require
   [app.common.data :as d]
   [app.common.flags :as flags]
   [app.common.pages :as cp]
   [app.common.pprint :as pp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as main]
   [app.media]
   [app.migrations]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.mutations.files :as files]
   [app.rpc.mutations.profile :as profile]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.mutations.teams :as teams]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [environ.core :refer [env]]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [mockery.core :as mk]
   [promesa.core :as p]
   [yetti.request :as yrq])
  (:import
   java.util.UUID
   org.postgresql.ds.PGSimpleDataSource))

(def ^:dynamic *system* nil)
(def ^:dynamic *pool* nil)

(def defaults
  {:database-uri "postgresql://postgres/penpot_test"
   :redis-uri "redis://redis/1"})

(def config
  (->> (cf/read-env "penpot-test")
       (merge cf/defaults defaults)
       (us/conform ::cf/config)))

(def default-flags
  [:enable-secure-session-cookies
   :enable-email-verification
   :enable-smtp])

(defn state-init
  [next]
  (let [templates [{:id "test"
                    :name "test"
                    :file-uri "test"
                    :thumbnail-uri "test"
                    :path (-> "app/test_files/template.penpot" io/resource fs/path)}]
        system (-> (merge main/system-config main/worker-config)
                   (assoc-in [:app.redis/redis :uri] (:redis-uri config))
                   (assoc-in [:app.db/pool :uri] (:database-uri config))
                   (assoc-in [:app.db/pool :username] (:database-username config))
                   (assoc-in [:app.db/pool :password] (:database-password config))
                   (assoc-in [:app.rpc/methods :templates] templates)
                   (dissoc :app.srepl/server
                           :app.http/server
                           :app.http/router
                           :app.http.awsns/handler
                           :app.http.session/updater
                           :app.auth.oidc/google-provider
                           :app.auth.oidc/gitlab-provider
                           :app.auth.oidc/github-provider
                           :app.auth.oidc/generic-provider
                           :app.setup/builtin-templates
                           :app.auth.oidc/routes
                           :app.worker/executors-monitor
                           :app.http.oauth/handler
                           :app.notifications/handler
                           :app.loggers.sentry/reporter
                           :app.loggers.mattermost/reporter
                           :app.loggers.loki/reporter
                           :app.loggers.database/reporter
                           :app.loggers.zmq/receiver
                           :app.worker/cron
                           :app.worker/worker))
        _      (ig/load-namespaces system)
        system (-> (ig/prep system)
                   (ig/init))]
    (try
      (binding [*system* system
                *pool*   (:app.db/pool system)]
        (with-redefs [app.config/flags (flags/parse flags/default default-flags (:flags config))
                      app.config/config config
                      app.rpc.commands.auth/derive-password identity
                      app.rpc.commands.auth/verify-password (fn [a b] {:valid (= a b)})]
          (next)))
      (finally
        (ig/halt! system)))))

(defn database-reset
  [next]
  (let [sql (str "SELECT table_name "
                 "  FROM information_schema.tables "
                 " WHERE table_schema = 'public' "
                 "   AND table_name != 'migrations';")]
    (db/with-atomic [conn *pool*]
      (let [result (->> (db/exec! conn [sql])
                        (map :table-name))]
        (db/exec! conn [(str "TRUNCATE "
                             (apply str (interpose ", " result))
                             " CASCADE;")]))))
  (next))

(defn clean-storage
  [next]
  (let [path (fs/path "/tmp/penpot")]
    (when (fs/exists? path)
      (fs/delete (fs/path "/tmp/penpot")))
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
  ([i] (create-profile* *pool* i {}))
  ([i params] (create-profile* *pool* i params))
  ([conn i params]
   (let [params (merge {:id (mk-uuid "profile" i)
                        :fullname (str "Profile " i)
                        :email (str "profile" i ".test@nodomain.com")
                        :password "123123"
                        :is-demo false}
                       params)]
     (->> params
          (cmd.auth/create-profile conn)
          (cmd.auth/create-profile-relations conn)))))

(defn create-project*
  ([i params] (create-project* *pool* i params))
  ([conn i {:keys [profile-id team-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? team-id)
   (->> (merge {:id (mk-uuid "project" i)
                :name (str "project" i)}
               params)
        (#'projects/create-project conn))))

(defn create-file*
  ([i params]
   (create-file* *pool* i params))
  ([conn i {:keys [profile-id project-id] :as params}]
   (us/assert uuid? profile-id)
   (us/assert uuid? project-id)
   (#'files/create-file conn
                        (merge {:id (mk-uuid "file" i)
                                :name (str "file" i)
                                :components-v2 true}
                               params))))

(defn mark-file-deleted*
  ([params] (mark-file-deleted* *pool* params))
  ([conn {:keys [id] :as params}]
   (#'files/mark-file-deleted conn {:id id})))

(defn create-team*
  ([i params] (create-team* *pool* i params))
  ([conn i {:keys [profile-id] :as params}]
   (us/assert uuid? profile-id)
   (let [id   (mk-uuid "team" i)]
     (teams/create-team conn {:id id
                              :profile-id profile-id
                              :name (str "team" i)}))))

(defn create-file-media-object*
  ([params] (create-file-media-object* *pool* params))
  ([conn {:keys [name width height mtype file-id is-local media-id]
          :or {name "sample" width 100 height 100 mtype "image/svg+xml" is-local true}}]
   (db/insert! conn :file-media-object
               {:id (uuid/next)
                :file-id file-id
                :is-local is-local
                :name name
                :media-id media-id
                :width  width
                :height height
                :mtype  mtype})))

(defn link-file-to-library*
  ([params] (link-file-to-library* *pool* params))
  ([conn {:keys [file-id library-id] :as params}]
   (#'files/link-file-to-library conn {:file-id file-id :library-id library-id})))

(defn create-complaint-for
  [conn {:keys [id created-at type]}]
  (db/insert! conn :profile-complaint-report
              {:profile-id id
               :created-at (or created-at (dt/now))
               :type (name type)
               :content (db/tjson {})}))

(defn create-global-complaint-for
  [conn {:keys [email type created-at]}]
  (db/insert! conn :global-complaint-report
              {:email email
               :type (name type)
               :created-at (or created-at (dt/now))
               :content (db/tjson {})}))

(defn create-team-role*
  ([params] (create-team-role* *pool* params))
  ([conn {:keys [team-id profile-id role] :or {role :owner}}]
   (#'teams/create-team-role conn {:team-id team-id
                                  :profile-id profile-id
                                  :role role})))

(defn create-project-role*
  ([params] (create-project-role* *pool* params))
  ([conn {:keys [project-id profile-id role] :or {role :owner}}]
   (#'projects/create-project-role conn {:project-id project-id
                                         :profile-id profile-id
                                         :role role})))

(defn create-file-role*
  ([params] (create-file-role* *pool* params))
  ([conn {:keys [file-id profile-id role] :or {role :owner}}]
   (#'files/create-file-role conn {:file-id file-id
                                   :profile-id profile-id
                                   :role role})))

(defn update-file*
  ([params] (update-file* *pool* params))
  ([conn {:keys [file-id changes session-id profile-id revn]
          :or {session-id (uuid/next) revn 0}}]
   (let [file    (db/get-by-id conn :file file-id)
         msgbus  (:app.msgbus/msgbus *system*)
         metrics (:app.metrics/metrics *system*)]
     (#'files/update-file {:conn conn
                           :msgbus msgbus
                           :metrics metrics}
                          {:file file
                           :revn revn
                           :components-v2 true
                           :changes changes
                           :session-id session-id
                           :profile-id profile-id}))))

;; --- RPC HELPERS

(defn handle-error
  [^Throwable err]
  (if (instance? java.util.concurrent.ExecutionException err)
    (handle-error (.getCause err))
    err))

(defmacro try-on!
  [expr]
  `(try
     {:error nil
      :result (deref ~expr)}
     (catch Exception e#
       {:error (handle-error e#)
        :result nil})))

(defn command!
  [{:keys [::type] :as data}]
  (let [method-fn (get-in *system* [:app.rpc/methods :commands type])]
    ;; (app.common.pprint/pprint (:app.rpc/methods *system*))
    (try-on! (method-fn (dissoc data ::type)))))

(defn mutation!
  [{:keys [::type] :as data}]
  (let [method-fn (get-in *system* [:app.rpc/methods :mutations type])]
    (try-on! (method-fn (dissoc data ::type)))))

(defn query!
  [{:keys [::type] :as data}]
  (let [method-fn (get-in *system* [:app.rpc/methods :queries type])]
    (try-on! (method-fn (dissoc data ::type)))))

(defn run-task!
  ([name]
   (run-task! name {}))
  ([name params]
   (let [tasks (:app.worker/registry *system*)]
     (let [task-fn (get tasks name)]
       (task-fn params)))))

;; --- UTILS

(defn print-error!
  [error]
  (let [data (ex-data error)]
    (cond
      (= :spec-validation (:code data))
      (println
       (us/pretty-explain data))

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
  (instance? clojure.lang.ExceptionInfo v))

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
        tmp (fs/create-tempfile)]
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

(defn db-insert!
  [& params]
  (apply db/insert! *pool* params))

(defn db-query
  [& params]
  (apply db/query *pool* params))

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
  (reset! m @(mk/make-mock {})))
