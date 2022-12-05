;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main
  (:require
   [app.auth.oidc :as-alias oidc]
   [app.auth.oidc.providers :as-alias oidc.providers]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.http.client :as-alias http.client]
   [app.http.session :as-alias http.session]
   [app.loggers.audit :as-alias audit]
   [app.loggers.audit.tasks :as-alias audit.tasks]
   [app.loggers.zmq :as-alias lzmq]
   [app.metrics :as-alias mtx]
   [app.metrics.definition :as-alias mdef]
   [app.redis :as-alias rds]
   [app.storage :as-alias sto]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
   [integrant.core :as ig])
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
    ::mdef/type :summary}

   :redis-eval-timing
   {::mdef/name "penpot_redis_eval_timing"
    ::mdef/help "Redis EVAL commands execution timings (ms)"
    ::mdef/labels ["name"]
    ::mdef/type :summary}

   :rpc-climit-queue-size
   {::mdef/name "penpot_rpc_climit_queue_size"
    ::mdef/help "Current number of queued submissions on the CLIMIT."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :rpc-climit-concurrency
   {::mdef/name "penpot_rpc_climit_concurrency"
    ::mdef/help "Current number of used concurrency capacity on the CLIMIT"
    ::mdef/labels ["name"]
    ::mdef/type :gauge}

   :rpc-climit-timing
   {::mdef/name "penpot_rpc_climit_timing"
    ::mdef/help "Summary of the time between queuing and executing on the CLIMIT"
    ::mdef/labels ["name"]
    ::mdef/type :summary}

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
    ::mdef/type :summary}

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
    ::mdef/type :gauge}

   :executors-queued-submissions
   {::mdef/name "penpot_executors_queued_submissions"
    ::mdef/help "Current number of queued submissions."
    ::mdef/labels ["name"]
    ::mdef/type :gauge}})

(def system-config
  {::db/pool
   {:uri        (cf/get :database-uri)
    :username   (cf/get :database-username)
    :password   (cf/get :database-password)
    :read-only  (cf/get :database-readonly false)
    :metrics    (ig/ref ::mtx/metrics)
    :migrations (ig/ref :app.migrations/all)
    :name       :main
    :min-size   (cf/get :database-min-pool-size 0)
    :max-size   (cf/get :database-max-pool-size 60)}

   ;; Default thread pool for IO operations
   ::wrk/executor
   {::wrk/parallelism (cf/get :default-executor-parallelism 100)}

   ::wrk/scheduled-executor
   {::wrk/parallelism (cf/get :scheduled-executor-parallelism 20)}

   ::wrk/monitor
   {::mtx/metrics  (ig/ref ::mtx/metrics)
    ::wrk/name     "default"
    ::wrk/executor (ig/ref ::wrk/executor)}

   :app.migrations/migrations
   {}

   ::mtx/metrics
   {:default default-metrics}

   :app.migrations/all
   {:main (ig/ref :app.migrations/migrations)}

   ::rds/redis
   {::rds/uri     (cf/get :redis-uri)
    ::mtx/metrics (ig/ref ::mtx/metrics)}

   :app.msgbus/msgbus
   {:backend   (cf/get :msgbus-backend :redis)
    :executor  (ig/ref ::wrk/executor)
    :redis     (ig/ref ::rds/redis)}

   :app.storage.tmp/cleaner
   {::wrk/executor (ig/ref ::wrk/executor)
    ::wrk/scheduled-executor (ig/ref ::wrk/scheduled-executor)}

   ::sto/gc-deleted-task
   {:pool     (ig/ref ::db/pool)
    :storage  (ig/ref ::sto/storage)
    :executor (ig/ref ::wrk/executor)}

   ::sto/gc-touched-task
   {:pool (ig/ref ::db/pool)}

   ::http.client/client
   {::wrk/executor (ig/ref ::wrk/executor)}

   :app.http.session/manager
   {:pool     (ig/ref ::db/pool)
    :sprops   (ig/ref :app.setup/props)
    :executor (ig/ref ::wrk/executor)}

   :app.http.session/gc-task
   {:pool        (ig/ref ::db/pool)
    :max-age     (cf/get :auth-token-cookie-max-age)}

   :app.http.awsns/handler
   {::props              (ig/ref :app.setup/props)
    ::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)
    ::wrk/executor       (ig/ref ::wrk/executor)}

   :app.http/server
   {:port        (cf/get :http-server-port)
    :host        (cf/get :http-server-host)
    :router      (ig/ref :app.http/router)
    :metrics     (ig/ref ::mtx/metrics)
    :executor    (ig/ref ::wrk/executor)
    :io-threads  (cf/get :http-server-io-threads)
    :max-body-size           (cf/get :http-server-max-body-size)
    :max-multipart-body-size (cf/get :http-server-max-multipart-body-size)}

   :app.auth.ldap/provider
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
   {}

   ::oidc.providers/generic
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc/routes
   {::http.client/client   (ig/ref ::http.client/client)
    ::db/pool              (ig/ref ::db/pool)
    ::props                (ig/ref :app.setup/props)
    ::wrk/executor         (ig/ref ::wrk/executor)
    ::oidc/providers       {:google (ig/ref ::oidc.providers/google)
                            :github (ig/ref ::oidc.providers/github)
                            :gitlab (ig/ref ::oidc.providers/gitlab)
                            :oidc   (ig/ref ::oidc.providers/generic)}
    ::audit/collector      (ig/ref ::audit/collector)
    ::http.session/session (ig/ref :app.http.session/manager)}


   ;; TODO: revisit the dependencies of this service, looks they are too much unused of them
   :app.http/router
   {:assets        (ig/ref :app.http.assets/handlers)
    :feedback      (ig/ref :app.http.feedback/handler)
    :session       (ig/ref :app.http.session/manager)
    :awsns-handler (ig/ref :app.http.awsns/handler)
    :debug-routes  (ig/ref :app.http.debug/routes)
    :oidc-routes   (ig/ref ::oidc/routes)
    :ws            (ig/ref :app.http.websocket/handler)
    :metrics       (ig/ref ::mtx/metrics)
    :public-uri    (cf/get :public-uri)
    :storage       (ig/ref ::sto/storage)
    :rpc-routes    (ig/ref :app.rpc/routes)
    :doc-routes    (ig/ref :app.rpc.doc/routes)
    :executor      (ig/ref ::wrk/executor)}

   :app.http.debug/routes
   {:pool     (ig/ref ::db/pool)
    :executor (ig/ref ::wrk/executor)
    :storage  (ig/ref ::sto/storage)
    :session  (ig/ref :app.http.session/manager)}

   :app.http.websocket/handler
   {:pool     (ig/ref ::db/pool)
    :metrics  (ig/ref ::mtx/metrics)
    :msgbus   (ig/ref :app.msgbus/msgbus)}

   :app.http.assets/handlers
   {:metrics           (ig/ref ::mtx/metrics)
    :assets-path       (cf/get :assets-path)
    :storage           (ig/ref ::sto/storage)
    :executor          (ig/ref ::wrk/executor)
    :cache-max-age     (dt/duration {:hours 24})
    :signature-max-age (dt/duration {:hours 24 :minutes 5})}

   :app.http.feedback/handler
   {:pool     (ig/ref ::db/pool)
    :executor (ig/ref ::wrk/executor)}

   :app.rpc/climit
   {:metrics  (ig/ref ::mtx/metrics)
    :executor (ig/ref ::wrk/executor)}

   :app.rpc/rlimit
   {:executor  (ig/ref ::wrk/executor)
    :scheduled-executor (ig/ref ::wrk/scheduled-executor)}

   :app.rpc/methods
   {::audit/collector    (ig/ref ::audit/collector)
    ::http.client/client (ig/ref ::http.client/client)
    ::db/pool            (ig/ref ::db/pool)
    ::wrk/executor       (ig/ref ::wrk/executor)

    :pool                (ig/ref ::db/pool)
    :session             (ig/ref :app.http.session/manager)
    :sprops              (ig/ref :app.setup/props)
    :metrics             (ig/ref ::mtx/metrics)
    :storage             (ig/ref ::sto/storage)
    :msgbus              (ig/ref :app.msgbus/msgbus)
    :public-uri          (cf/get :public-uri)
    :redis               (ig/ref ::rds/redis)
    :ldap                (ig/ref :app.auth.ldap/provider)
    :http-client         (ig/ref ::http.client/client)
    :climit              (ig/ref :app.rpc/climit)
    :rlimit              (ig/ref :app.rpc/rlimit)
    :executor            (ig/ref ::wrk/executor)
    :templates           (ig/ref :app.setup/builtin-templates)
    }

   :app.rpc.doc/routes
   {:methods (ig/ref :app.rpc/methods)}

   :app.rpc/routes
   {:methods (ig/ref :app.rpc/methods)}

   ::wrk/registry
   {:metrics (ig/ref ::mtx/metrics)
    :tasks
    {:sendmail           (ig/ref :app.emails/handler)
     :objects-gc         (ig/ref :app.tasks.objects-gc/handler)
     :file-gc            (ig/ref :app.tasks.file-gc/handler)
     :file-xlog-gc       (ig/ref :app.tasks.file-xlog-gc/handler)
     :storage-gc-deleted (ig/ref ::sto/gc-deleted-task)
     :storage-gc-touched (ig/ref ::sto/gc-touched-task)
     :tasks-gc           (ig/ref :app.tasks.tasks-gc/handler)
     :telemetry          (ig/ref :app.tasks.telemetry/handler)
     :session-gc         (ig/ref :app.http.session/gc-task)
     :audit-log-archive  (ig/ref ::audit.tasks/archive)
     :audit-log-gc       (ig/ref ::audit.tasks/gc)}}


   :app.emails/sendmail
   {:host             (cf/get :smtp-host)
    :port             (cf/get :smtp-port)
    :ssl              (cf/get :smtp-ssl)
    :tls              (cf/get :smtp-tls)
    :username         (cf/get :smtp-username)
    :password         (cf/get :smtp-password)
    :default-reply-to (cf/get :smtp-default-reply-to)
    :default-from     (cf/get :smtp-default-from)}

   :app.emails/handler
   {:sendmail (ig/ref :app.emails/sendmail)
    :metrics  (ig/ref ::mtx/metrics)}

   :app.tasks.tasks-gc/handler
   {:pool    (ig/ref ::db/pool)
    :max-age cf/deletion-delay}

   :app.tasks.objects-gc/handler
   {:pool    (ig/ref ::db/pool)
    :storage (ig/ref ::sto/storage)}

   :app.tasks.file-gc/handler
   {:pool (ig/ref ::db/pool)}

   :app.tasks.file-xlog-gc/handler
   {:pool (ig/ref ::db/pool)}

   :app.tasks.telemetry/handler
   {::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)
    ::props              (ig/ref :app.setup/props)}

   :app.srepl/server
   {:port (cf/get :srepl-port)
    :host (cf/get :srepl-host)}

   :app.setup/builtin-templates
   {::http.client/client (ig/ref ::http.client/client)}

   :app.setup/props
   {:pool (ig/ref ::db/pool)
    :key  (cf/get :secret-key)}

   ::lzmq/receiver
   {}

   ::audit/collector
   {::db/pool           (ig/ref ::db/pool)
    ::wrk/executor      (ig/ref ::wrk/executor)
    ::mtx/metrics       (ig/ref ::mtx/metrics)}

   ::audit.tasks/archive
   {::props              (ig/ref :app.setup/props)
    ::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)}

   ::audit.tasks/gc
   {::db/pool (ig/ref ::db/pool)}

   :app.loggers.loki/reporter
   {::lzmq/receiver      (ig/ref ::lzmq/receiver)
    ::http.client/client (ig/ref ::http.client/client)}

   :app.loggers.mattermost/reporter
   {::lzmq/receiver      (ig/ref ::lzmq/receiver)
    ::http.client/client (ig/ref ::http.client/client)}

   :app.loggers.database/reporter
   {:receiver (ig/ref :app.loggers.zmq/receiver)
    :pool     (ig/ref ::db/pool)
    :executor (ig/ref ::wrk/executor)}

   ::sto/storage
   {:pool     (ig/ref ::db/pool)
    :executor (ig/ref ::wrk/executor)

    :backends
    {:assets-s3 (ig/ref [::assets :app.storage.s3/backend])
     :assets-fs (ig/ref [::assets :app.storage.fs/backend])

     ;; keep this for backward compatibility
     :s3        (ig/ref [::assets :app.storage.s3/backend])
     :fs        (ig/ref [::assets :app.storage.fs/backend])}}

   [::assets :app.storage.s3/backend]
   {:region   (cf/get :storage-assets-s3-region)
    :endpoint (cf/get :storage-assets-s3-endpoint)
    :bucket   (cf/get :storage-assets-s3-bucket)
    :executor (ig/ref ::wrk/executor)}

   [::assets :app.storage.fs/backend]
   {:directory (cf/get :storage-assets-fs-directory)}
   })


(def worker-config
  {::wrk/cron
   {::wrk/scheduled-executor  (ig/ref ::wrk/scheduled-executor)
    ::wrk/registry            (ig/ref ::wrk/registry)
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

   ::wrk/scheduler
   {::rds/redis   (ig/ref ::rds/redis)
    ::mtx/metrics (ig/ref ::mtx/metrics)
    ::db/pool     (ig/ref ::db/pool)}

   ::wrk/worker
   {::wrk/parallelism (cf/get ::worker-parallelism 1)
    ;; FIXME: read queues from configuration
    ::wrk/queue       "default"
    ::rds/redis       (ig/ref ::rds/redis)
    ::wrk/registry    (ig/ref ::wrk/registry)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}})

(def system nil)

(defn start
  []
  (ig/load-namespaces (merge system-config worker-config))
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> system-config
                                 (cond-> (contains? cf/flags :backend-worker)
                                   (merge worker-config))
                                 (ig/prep)
                                 (ig/init))))
  (l/info :hint "welcome to penpot"
          :flags (str/join "," (map name cf/flags))
          :worker? (contains? cf/flags :backend-worker)
          :version (:full cf/version)))

(defn stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil)))

(defn -main
  [& _args]
  (try
    (start)
    (catch Throwable cause
      (l/error :hint (ex-message cause)
               :cause cause)
      (System/exit -1))))
