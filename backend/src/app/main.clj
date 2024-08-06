;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main
  (:require
   [app.auth.ldap :as-alias ldap]
   [app.auth.oidc :as-alias oidc]
   [app.auth.oidc.providers :as-alias oidc.providers]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.email :as-alias email]
   [app.http :as-alias http]
   [app.http.assets :as-alias http.assets]
   [app.http.awsns :as http.awsns]
   [app.http.client :as-alias http.client]
   [app.http.debug :as-alias http.debug]
   [app.http.session :as-alias session]
   [app.http.session.tasks :as-alias session.tasks]
   [app.http.websocket :as http.ws]
   [app.loggers.webhooks :as-alias webhooks]
   [app.metrics :as-alias mtx]
   [app.metrics.definition :as-alias mdef]
   [app.migrations.v2 :as migrations.v2]
   [app.msgbus :as-alias mbus]
   [app.redis :as-alias rds]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias rpc.doc]
   [app.setup :as-alias setup]
   [app.srepl :as-alias srepl]
   [app.storage :as-alias sto]
   [app.storage.fs :as-alias sto.fs]
   [app.storage.gc-deleted :as-alias sto.gc-deleted]
   [app.storage.gc-touched :as-alias sto.gc-touched]
   [app.storage.s3 :as-alias sto.s3]
   [app.svgo :as-alias svgo]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [cider.nrepl :refer [cider-nrepl-handler]]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [nrepl.server :as nrepl]
   [promesa.exec :as px])
  (:gen-class))

(def default-metrics
  {:update-file-changes
   {::mdef/name "penpot_rpc_update_file_changes_total"
    ::mdef/help "A total number of changes submitted to update-file."
    ::mdef/type :counter}

   :update-file-bytes-processed
   {::mdef/name "penpot_rpc_update_file_bytes_processed_total"
    ::mdef/help "A total number of bytes processed by update-file."
    ::mdef/type :counter}

   :rpc-mutation-timing
   {::mdef/name "penpot_rpc_mutation_timing"
    ::mdef/help "RPC mutation method call timing."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :rpc-command-timing
   {::mdef/name "penpot_rpc_command_timing"
    ::mdef/help "RPC command method call timing."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :rpc-query-timing
   {::mdef/name "penpot_rpc_query_timing"
    ::mdef/help "RPC query method call timing."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :websocket-active-connections
   {::mdef/name "penpot_websocket_active_connections"
    ::mdef/help "Active websocket connections gauge"
    ::mdef/type :gauge}

   :websocket-messages-total
   {::mdef/name "penpot_websocket_message_total"
    ::mdef/help "Counter of processed messages."
    ::mdef/labels ["op"]
    ::mdef/type :counter}

   :websocket-session-timing
   {::mdef/name "penpot_websocket_session_timing"
    ::mdef/help "Websocket session timing (seconds)."
    ::mdef/type :summary}

   :session-update-total
   {::mdef/name "penpot_http_session_update_total"
    ::mdef/help "A counter of session update batch events."
    ::mdef/type :counter}

   :tasks-timing
   {::mdef/name "penpot_tasks_timing"
    ::mdef/help "Background tasks timing (milliseconds)."
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :redis-eval-timing
   {::mdef/name "penpot_redis_eval_timing"
    ::mdef/help "Redis EVAL commands execution timings (ms)"
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :rpc-climit-queue
   {::mdef/name "penpot_rpc_climit_queue"
    ::mdef/help "Current number of queued submissions."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :rpc-climit-permits
   {::mdef/name "penpot_rpc_climit_permits"
    ::mdef/help "Current number of available permits"
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :rpc-climit-timing
   {::mdef/name "penpot_rpc_climit_timing"
    ::mdef/help "Summary of the time between queuing and executing on the CLIMIT"
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :audit-http-handler-queue-size
   {::mdef/name "penpot_audit_http_handler_queue_size"
    ::mdef/help "Current number of queued submissions on the audit log http handler"
    ::mdef/labels []
    ::mdef/type :gauge}

   :audit-http-handler-concurrency
   {::mdef/name "penpot_audit_http_handler_concurrency"
    ::mdef/help "Current number of used concurrency capacity on the audit log http handler"
    ::mdef/labels []
    ::mdef/type :gauge}

   :audit-http-handler-timing
   {::mdef/name "penpot_audit_http_handler_timing"
    ::mdef/help "Summary of the time between queuing and executing on the audit log http handler"
    ::mdef/labels []
    ::mdef/type :histogram}

   :executors-active-threads
   {::mdef/name "penpot_executors_active_threads"
    ::mdef/help "Current number of threads available in the executor service."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :executors-completed-tasks
   {::mdef/name "penpot_executors_completed_tasks_total"
    ::mdef/help "Approximate number of completed tasks by the executor."
    ::mdef/labels ["name"]
    ::mdef/type :counter}

   :executors-running-threads
   {::mdef/name "penpot_executors_running_threads"
    ::mdef/help "Current number of threads with state RUNNING."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}})

(def system-config
  {::db/pool
   {::db/uri        (cf/get :database-uri)
    ::db/username   (cf/get :database-username)
    ::db/password   (cf/get :database-password)
    ::db/read-only? (cf/get :database-readonly false)
    ::db/min-size   (cf/get :database-min-pool-size 0)
    ::db/max-size   (cf/get :database-max-pool-size 60)
    ::mtx/metrics   (ig/ref ::mtx/metrics)}

   ;; Default thread pool for IO operations
   ::wrk/executor
   {}

   ::wrk/monitor
   {::mtx/metrics  (ig/ref ::mtx/metrics)
    ::wrk/executor (ig/ref ::wrk/executor)
    ::wrk/name     "default"}

   :app.migrations/migrations
   {::db/pool (ig/ref ::db/pool)}

   ::mtx/metrics
   {:default default-metrics}

   ::mtx/routes
   {::mtx/metrics (ig/ref ::mtx/metrics)}

   ::rds/redis
   {::rds/uri      (cf/get :redis-uri)
    ::mtx/metrics  (ig/ref ::mtx/metrics)
    ::wrk/executor (ig/ref ::wrk/executor)}

   ::mbus/msgbus
   {::wrk/executor  (ig/ref ::wrk/executor)
    ::rds/redis     (ig/ref ::rds/redis)}

   :app.storage.tmp/cleaner
   {::wrk/executor (ig/ref ::wrk/executor)}

   ::sto.gc-deleted/handler
   {::db/pool      (ig/ref ::db/pool)
    ::sto/storage  (ig/ref ::sto/storage)}

   ::sto.gc-touched/handler
   {::db/pool (ig/ref ::db/pool)}

   ::http.client/client
   {}

   ::session/manager
   {::db/pool (ig/ref ::db/pool)}

   ::session.tasks/gc
   {::db/pool (ig/ref ::db/pool)}

   ::http.awsns/routes
   {::setup/props        (ig/ref ::setup/props)
    ::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)}

   ::http/server
   {::http/port                    (cf/get :http-server-port)
    ::http/host                    (cf/get :http-server-host)
    ::http/router                  (ig/ref ::http/router)
    ::http/io-threads              (cf/get :http-server-io-threads)
    ::http/max-body-size           (cf/get :http-server-max-body-size)
    ::http/max-multipart-body-size (cf/get :http-server-max-multipart-body-size)}

   ::ldap/provider
   {:host           (cf/get :ldap-host)
    :port           (cf/get :ldap-port)
    :ssl            (cf/get :ldap-ssl)
    :tls            (cf/get :ldap-starttls)
    :query          (cf/get :ldap-user-query)
    :attrs-email    (cf/get :ldap-attrs-email)
    :attrs-fullname (cf/get :ldap-attrs-fullname)
    :attrs-username (cf/get :ldap-attrs-username)
    :base-dn        (cf/get :ldap-base-dn)
    :bind-dn        (cf/get :ldap-bind-dn)
    :bind-password  (cf/get :ldap-bind-password)
    :enabled?       (contains? cf/flags :login-with-ldap)}

   ::oidc.providers/google
   {}

   ::oidc.providers/github
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc.providers/gitlab
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc.providers/generic
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc/routes
   {::http.client/client (ig/ref ::http.client/client)
    ::db/pool            (ig/ref ::db/pool)
    ::setup/props        (ig/ref ::setup/props)
    ::oidc/providers     {:google (ig/ref ::oidc.providers/google)
                          :github (ig/ref ::oidc.providers/github)
                          :gitlab (ig/ref ::oidc.providers/gitlab)
                          :oidc   (ig/ref ::oidc.providers/generic)}
    ::session/manager    (ig/ref ::session/manager)
    ::email/blacklist    (ig/ref ::email/blacklist)
    ::email/whitelist    (ig/ref ::email/whitelist)}

   :app.http/router
   {::session/manager    (ig/ref ::session/manager)
    ::db/pool            (ig/ref ::db/pool)
    ::rpc/routes         (ig/ref ::rpc/routes)
    ::rpc.doc/routes     (ig/ref ::rpc.doc/routes)
    ::setup/props        (ig/ref ::setup/props)
    ::mtx/routes         (ig/ref ::mtx/routes)
    ::oidc/routes        (ig/ref ::oidc/routes)
    ::http.debug/routes  (ig/ref ::http.debug/routes)
    ::http.assets/routes (ig/ref ::http.assets/routes)
    ::http.ws/routes     (ig/ref ::http.ws/routes)
    ::http.awsns/routes  (ig/ref ::http.awsns/routes)}

   ::http.debug/routes
   {::db/pool         (ig/ref ::db/pool)
    ::session/manager (ig/ref ::session/manager)
    ::sto/storage     (ig/ref ::sto/storage)
    ::setup/props     (ig/ref ::setup/props)}

   ::http.ws/routes
   {::db/pool         (ig/ref ::db/pool)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::mbus/msgbus     (ig/ref ::mbus/msgbus)
    ::session/manager (ig/ref ::session/manager)}

   :app.http.assets/routes
   {::http.assets/path  (cf/get :assets-path)
    ::http.assets/cache-max-age (dt/duration {:hours 24})
    ::http.assets/cache-max-agesignature-max-age (dt/duration {:hours 24 :minutes 5})
    ::sto/storage  (ig/ref ::sto/storage)}

   :app.rpc/climit
   {::mtx/metrics  (ig/ref ::mtx/metrics)
    ::wrk/executor (ig/ref ::wrk/executor)}

   :app.rpc/rlimit
   {::wrk/executor (ig/ref ::wrk/executor)}

   :app.rpc/methods
   {::http.client/client (ig/ref ::http.client/client)
    ::db/pool            (ig/ref ::db/pool)
    ::wrk/executor       (ig/ref ::wrk/executor)
    ::session/manager    (ig/ref ::session/manager)
    ::ldap/provider      (ig/ref ::ldap/provider)
    ::sto/storage        (ig/ref ::sto/storage)
    ::mtx/metrics        (ig/ref ::mtx/metrics)
    ::mbus/msgbus        (ig/ref ::mbus/msgbus)
    ::rds/redis          (ig/ref ::rds/redis)
    ::svgo/optimizer     (ig/ref ::svgo/optimizer)

    ::rpc/climit         (ig/ref ::rpc/climit)
    ::rpc/rlimit         (ig/ref ::rpc/rlimit)
    ::setup/templates    (ig/ref ::setup/templates)
    ::setup/props        (ig/ref ::setup/props)

    ::email/blacklist    (ig/ref ::email/blacklist)
    ::email/whitelist    (ig/ref ::email/whitelist)}

   :app.rpc.doc/routes
   {:methods (ig/ref :app.rpc/methods)}

   :app.rpc/routes
   {::rpc/methods     (ig/ref :app.rpc/methods)
    ::db/pool         (ig/ref ::db/pool)
    ::session/manager (ig/ref ::session/manager)
    ::setup/props     (ig/ref ::setup/props)}

   ::wrk/registry
   {::mtx/metrics (ig/ref ::mtx/metrics)
    ::wrk/tasks
    {:sendmail           (ig/ref ::email/handler)
     :objects-gc         (ig/ref :app.tasks.objects-gc/handler)
     :file-gc            (ig/ref :app.tasks.file-gc/handler)
     :file-xlog-gc       (ig/ref :app.tasks.file-xlog-gc/handler)
     :tasks-gc           (ig/ref :app.tasks.tasks-gc/handler)
     :telemetry          (ig/ref :app.tasks.telemetry/handler)
     :storage-gc-deleted (ig/ref ::sto.gc-deleted/handler)
     :storage-gc-touched (ig/ref ::sto.gc-touched/handler)
     :session-gc         (ig/ref ::session.tasks/gc)
     :audit-log-archive  (ig/ref :app.loggers.audit.archive-task/handler)
     :audit-log-gc       (ig/ref :app.loggers.audit.gc-task/handler)

     :delete-object
     (ig/ref :app.tasks.delete-object/handler)
     :process-webhook-event
     (ig/ref ::webhooks/process-event-handler)
     :run-webhook
     (ig/ref ::webhooks/run-webhook-handler)}}

   ::email/blacklist
   {}

   ::email/whitelist
   {}

   ::email/sendmail
   {::email/host             (cf/get :smtp-host)
    ::email/port             (cf/get :smtp-port)
    ::email/ssl              (cf/get :smtp-ssl)
    ::email/tls              (cf/get :smtp-tls)
    ::email/username         (cf/get :smtp-username)
    ::email/password         (cf/get :smtp-password)
    ::email/default-reply-to (cf/get :smtp-default-reply-to)
    ::email/default-from     (cf/get :smtp-default-from)}

   ::email/handler
   {::email/sendmail (ig/ref ::email/sendmail)
    ::mtx/metrics    (ig/ref ::mtx/metrics)}

   :app.tasks.tasks-gc/handler
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.objects-gc/handler
   {::db/pool     (ig/ref ::db/pool)
    ::sto/storage (ig/ref ::sto/storage)}

   :app.tasks.delete-object/handler
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.file-gc/handler
   {::db/pool     (ig/ref ::db/pool)
    ::sto/storage (ig/ref ::sto/storage)}

   :app.tasks.file-xlog-gc/handler
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.telemetry/handler
   {::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)
    ::setup/props        (ig/ref ::setup/props)}

   [::srepl/urepl ::srepl/server]
   {::srepl/port (cf/get :urepl-port 6062)
    ::srepl/host (cf/get :urepl-host "localhost")}

   [::srepl/prepl ::srepl/server]
   {::srepl/port (cf/get :prepl-port 6063)
    ::srepl/host (cf/get :prepl-host "localhost")}

   ::setup/templates {}

   ::setup/props
   {::db/pool    (ig/ref ::db/pool)
    ::setup/key  (cf/get :secret-key)

    ;; NOTE: this dependency is only necessary for proper initialization ordering, props
    ;; module requires the migrations to run before initialize.
    ::migrations (ig/ref :app.migrations/migrations)}

   ::svgo/optimizer
   {}

   :app.loggers.audit.archive-task/handler
   {::setup/props        (ig/ref ::setup/props)
    ::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)}

   :app.loggers.audit.gc-task/handler
   {::db/pool (ig/ref ::db/pool)}

   ::webhooks/process-event-handler
   {::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)}

   ::webhooks/run-webhook-handler
   {::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)}

   :app.loggers.mattermost/reporter
   {::http.client/client (ig/ref ::http.client/client)}

   :app.loggers.database/reporter
   {::db/pool (ig/ref ::db/pool)}

   ::sto/storage
   {::db/pool      (ig/ref ::db/pool)
    ::sto/backends
    {:assets-s3 (ig/ref [::assets :app.storage.s3/backend])
     :assets-fs (ig/ref [::assets :app.storage.fs/backend])}}

   [::assets :app.storage.s3/backend]
   {::sto.s3/region     (cf/get :storage-assets-s3-region)
    ::sto.s3/endpoint   (cf/get :storage-assets-s3-endpoint)
    ::sto.s3/bucket     (cf/get :storage-assets-s3-bucket)
    ::sto.s3/io-threads (cf/get :storage-assets-s3-io-threads)}

   [::assets :app.storage.fs/backend]
   {::sto.fs/directory (cf/get :storage-assets-fs-directory)}})


(def worker-config
  {::wrk/cron
   {::wrk/registry            (ig/ref ::wrk/registry)
    ::db/pool                 (ig/ref ::db/pool)
    ::wrk/entries
    [{:cron #app/cron "0 0 * * * ?" ;; hourly
      :task :file-xlog-gc}

     {:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :session-gc}

     {:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :objects-gc}

     {:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :storage-gc-deleted}

     {:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :storage-gc-touched}

     {:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :tasks-gc}

     {:cron #app/cron "0 0 2 * * ?" ;; daily
      :task :file-gc}

     {:cron #app/cron "0 30 */3,23 * * ?"
      :task :telemetry}

     (when (contains? cf/flags :audit-log-archive)
       {:cron #app/cron "0 */5 * * * ?" ;; every 5m
        :task :audit-log-archive})

     (when (contains? cf/flags :audit-log-gc)
       {:cron #app/cron "30 */5 * * * ?" ;; every 5m
        :task :audit-log-gc})]}

   ::wrk/dispatcher
   {::rds/redis   (ig/ref ::rds/redis)
    ::mtx/metrics (ig/ref ::mtx/metrics)
    ::db/pool     (ig/ref ::db/pool)}

   [::default ::wrk/runner]
   {::wrk/parallelism (cf/get ::worker-default-parallelism 1)
    ::wrk/queue       :default
    ::rds/redis       (ig/ref ::rds/redis)
    ::wrk/registry    (ig/ref ::wrk/registry)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}

   [::webhook ::wrk/runner]
   {::wrk/parallelism (cf/get ::worker-webhook-parallelism 1)
    ::wrk/queue       :webhooks
    ::rds/redis       (ig/ref ::rds/redis)
    ::wrk/registry    (ig/ref ::wrk/registry)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}})


(def system nil)

(defn start
  []
  (cf/validate!)
  (ig/load-namespaces (merge system-config worker-config))
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> system-config
                                 (cond-> (contains? cf/flags :backend-worker)
                                   (merge worker-config))
                                 (ig/prep)
                                 (ig/init))))
  (l/inf :hint "welcome to penpot"
         :flags (str/join "," (map name cf/flags))
         :worker? (contains? cf/flags :backend-worker)
         :version (:full cf/version)))

(defn start-custom
  [config]
  (ig/load-namespaces config)
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> config
                                 (ig/prep)
                                 (ig/init)))))

(defn stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil)))
(defn restart
  []
  (stop)
  (repl/refresh :after 'app.main/start))

(defn restart-all
  []
  (stop)
  (repl/refresh-all :after 'app.main/start))

(defmacro run-bench
  [& exprs]
  `(do
     (require 'criterium.core)
     (criterium.core/with-progress-reporting (crit/quick-bench (do ~@exprs) :verbose))))

(defn run-tests
  ([] (run-tests #"^backend-tests.*-test$"))
  ([o]
   (repl/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

(repl/disable-reload! (find-ns 'integrant.core))

(defn -main
  [& _args]
  (try
    (let [p (promise)]
      (when (contains? cf/flags :nrepl-server)
        (l/inf :hint "start nrepl server" :port 6064)
        (nrepl/start-server :bind "0.0.0.0" :port 6064 :handler cider-nrepl-handler))

      (start)

      (when (contains? cf/flags :v2-migration)
        (px/sleep 5000)
        (migrations.v2/migrate app.main/system))

      (deref p))
    (catch Throwable cause
      (binding [*out* *err*]
        (println "==== ERROR ===="))
      (.printStackTrace cause)
      (when-let [cause' (ex-cause cause)]
        (binding [*out* *err*]
          (println "==== CAUSE ===="))
        (.printStackTrace cause'))
      (px/sleep 500)
      (System/exit -1))))
