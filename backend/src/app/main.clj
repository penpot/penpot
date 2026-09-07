;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main
  (:require
   [app.auth.ldap :as-alias ldap]
   [app.auth.oidc :as-alias oidc]
   [app.auth.oidc.providers :as-alias oidc.providers]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as-alias db]
   [app.email :as-alias email]
   [app.http :as-alias http]
   [app.http.assets :as-alias http.assets]
   [app.http.awsns :as http.awsns]
   [app.http.client :as-alias http.client]
   [app.http.debug :as-alias http.debug]
   [app.http.management :as mgmt]
   [app.http.session :as session]
   [app.http.session.tasks :as-alias session.tasks]
   [app.http.websocket :as http.ws]
   [app.jobs :as-alias jobs]
   [app.jobs.gc :as-alias jobs.gc]
   [app.loggers.webhooks :as-alias webhooks]
   [app.metrics :as-alias mtx]
   [app.metrics.definition :as-alias mdef]
   [app.msgbus :as-alias mbus]
   [app.redis :as-alias rds]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.setup :as-alias setup]
   [app.srepl :as-alias srepl]
   [app.storage :as-alias sto]
   [app.storage.fs :as-alias sto.fs]
   [app.storage.gc-deleted :as-alias sto.gc-deleted]
   [app.storage.gc-touched :as-alias sto.gc-touched]
   [app.storage.pending-gc :as-alias sto.pending-gc]
   [app.storage.s3 :as-alias sto.s3]
   [app.system :as sys]
   [app.util.cron]
   [app.worker :as-alias wrk]
   [app.worker.executor]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [cuerdas.core :as str]
   [integrant.core :as ig]
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

   :rpc-main-timing
   {::mdef/name "penpot_rpc_main_timing"
    ::mdef/help "RPC command method call timing for main"
    ::mdef/labels ["name"]
    ::mdef/type :histogram}

   :rpc-management-timing
   {::mdef/name "penpot_rpc_management_timing"
    ::mdef/help "RPC command method call timing for management."
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

   :http-server-dispatch-timing
   {::mdef/name "penpot_http_server_dispatch_timing"
    ::mdef/help "Histogram of dispatch handler"
    ::mdef/labels []
    ::mdef/type :histogram}})

(def system-config
  {::db/pool
   {::db/uri        (cf/get :database-uri)
    ::db/username   (cf/get :database-username)
    ::db/password   (cf/get :database-password)
    ::db/read-only  (cf/get :database-readonly false)
    ::db/min-size   (cf/get :database-min-pool-size)
    ::db/max-size   (cf/get :database-max-pool-size)
    ::mtx/metrics   (ig/ref ::mtx/metrics)}

   ;; Default netty IO pool (shared between several services)
   ::wrk/netty-io-executor
   {:threads (cf/get :netty-io-threads)}

   ::wrk/executor
   {}

   :app.migrations/migrations
   {::db/pool (ig/ref ::db/pool)}

   ::mtx/metrics
   {:default default-metrics}

   ::mtx/routes
   {::mtx/metrics (ig/ref ::mtx/metrics)}

   ::rds/client
   {::rds/uri
    (cf/get :redis-uri)

    ::wrk/netty-io-executor
    (ig/ref ::wrk/netty-io-executor)}

   ::rds/pool
   {::rds/client  (ig/ref ::rds/client)
    ::mtx/metrics (ig/ref ::mtx/metrics)}

   ::mbus/msgbus
   {::wrk/executor (ig/ref ::wrk/executor)
    ::rds/client   (ig/ref ::rds/client)
    ::mtx/metrics  (ig/ref ::mtx/metrics)}

   :app.storage.tmp/cleaner
   {::wrk/executor (ig/ref ::wrk/executor)}

   ::http.client/client
   {}

   ::session/manager
   {::db/pool (ig/ref ::db/pool)}

   ::http.awsns/routes
   {::setup/props        (ig/ref ::setup/props)
    ::db/pool            (ig/ref ::db/pool)
    ::http.client/client (ig/ref ::http.client/client)}

   ::http/server
   {::http/port                    (cf/get :http-server-port)
    ::http/host                    (cf/get :http-server-host)
    ::http/io-threads              (cf/get :http-server-io-threads)
    ::http/max-worker-threads      (cf/get :http-server-max-worker-threads)
    ::http/max-body-size           (cf/get :http-server-max-body-size)
    ::http/router                  (ig/ref ::http/router)
    ::mtx/metrics                  (ig/ref ::mtx/metrics)}

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
    :enabled        (contains? cf/flags :login-with-ldap)}

   ::oidc.providers/google
   {}

   ::oidc.providers/github
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc.providers/gitlab
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc.providers/generic
   {::http.client/client (ig/ref ::http.client/client)}

   ::oidc/providers
   [(ig/ref ::oidc.providers/google)
    (ig/ref ::oidc.providers/github)
    (ig/ref ::oidc.providers/gitlab)
    (ig/ref ::oidc.providers/generic)]

   ::oidc/routes
   {::http.client/client (ig/ref ::http.client/client)
    ::jobs/defs          (ig/ref ::jobs/defs)
    ::db/pool            (ig/ref ::db/pool)
    ::setup/props        (ig/ref ::setup/props)
    ::oidc/providers     (ig/ref ::oidc/providers)
    ::session/manager    (ig/ref ::session/manager)
    ::email/blacklist    (ig/ref ::email/blacklist)
    ::email/whitelist    (ig/ref ::email/whitelist)
    :app.nitrate/client (ig/ref :app.nitrate/client)}

   ::mgmt/routes
   {::db/pool            (ig/ref ::db/pool)
    ::setup/props        (ig/ref ::setup/props)}

   :app.http/router
   {::session/manager    (ig/ref ::session/manager)
    ::db/pool            (ig/ref ::db/pool)
    ::rpc/routes         (ig/ref ::rpc/routes)
    ::setup/props        (ig/ref ::setup/props)
    ::mtx/routes         (ig/ref ::mtx/routes)
    ::oidc/routes        (ig/ref ::oidc/routes)
    ::mgmt/routes        (ig/ref ::mgmt/routes)
    ::http.debug/routes  (ig/ref ::http.debug/routes)
    ::http.assets/routes (ig/ref ::http.assets/routes)
    ::http.ws/routes     (ig/ref ::http.ws/routes)
    ::http.awsns/routes  (ig/ref ::http.awsns/routes)}

   ::http.debug/routes
   {::db/pool         (ig/ref ::db/pool)
    ::rds/pool        (ig/ref ::rds/pool)
    ::session/manager (ig/ref ::session/manager)
    ::mbus/msgbus     (ig/ref ::mbus/msgbus)
    ::sto/storage     (ig/ref ::sto/storage)
    ::setup/props     (ig/ref ::setup/props)}

   ::http.ws/routes
   {::db/pool         (ig/ref ::db/pool)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::mbus/msgbus     (ig/ref ::mbus/msgbus)
    ::setup/props     (ig/ref ::setup/props)
    ::session/manager (ig/ref ::session/manager)}

   :app.http.assets/routes
   {::http.assets/path              (cf/get :assets-path)
    ::http.assets/cache-max-age     (ct/duration {:hours 24})
    ::http.assets/signature-max-age (ct/duration {:hours 24 :minutes 15})
    ::sto/storage                   (ig/ref ::sto/storage)
    ::session/manager               (ig/ref ::session/manager)
    ::setup/props                   (ig/ref ::setup/props)
    ::db/pool                       (ig/ref ::db/pool)}

   ::rpc/climit
   {::mtx/metrics        (ig/ref ::mtx/metrics)
    ::wrk/executor       (ig/ref ::wrk/executor)
    ::climit/config      (cf/get :rpc-climit-config)
    ::climit/enabled     (contains? cf/flags :rpc-climit)}

   :app.rpc/rlimit
   {::wrk/executor (ig/ref ::wrk/executor)

    :app.loggers.mattermost/reporter
    (ig/ref :app.loggers.mattermost/reporter)

    :app.loggers.database/reporter
    (ig/ref :app.loggers.database/reporter)}

   :app.rpc/methods
   {::http.client/client (ig/ref ::http.client/client)
    ::jobs/defs           (ig/ref ::jobs/defs)
    ::db/pool            (ig/ref ::db/pool)
    ::rds/pool           (ig/ref ::rds/pool)
    :app.nitrate/client  (ig/ref :app.nitrate/client)
    ::wrk/executor       (ig/ref ::wrk/executor)
    ::session/manager    (ig/ref ::session/manager)
    ::ldap/provider      (ig/ref ::ldap/provider)
    ::sto/storage        (ig/ref ::sto/storage)
    ::mtx/metrics        (ig/ref ::mtx/metrics)
    ::mbus/msgbus        (ig/ref ::mbus/msgbus)
    ::rds/client         (ig/ref ::rds/client)

    ::rpc/climit         (ig/ref ::rpc/climit)
    ::rpc/rlimit         (ig/ref ::rpc/rlimit)
    ::setup/templates    (ig/ref ::setup/templates)
    ::setup/props        (ig/ref ::setup/props)
    ::setup/shared-keys  (ig/ref ::setup/shared-keys)

    ::email/blacklist    (ig/ref ::email/blacklist)
    ::email/whitelist    (ig/ref ::email/whitelist)

    :app.loggers.database/reporter
    (ig/ref :app.loggers.database/reporter)

    :app.loggers.mattermost/reporter
    (ig/ref :app.loggers.mattermost/reporter)}

   :app.nitrate/client
   {::http.client/client (ig/ref ::http.client/client)
    ::jobs/defs          (ig/ref ::jobs/defs)
    ::setup/shared-keys  (ig/ref ::setup/shared-keys)}

   :app.rpc/management-methods
   {::http.client/client (ig/ref ::http.client/client)
    ::jobs/defs          (ig/ref ::jobs/defs)
    ::db/pool            (ig/ref ::db/pool)
    ::rds/pool           (ig/ref ::rds/pool)
    ::wrk/executor       (ig/ref ::wrk/executor)
    ::session/manager    (ig/ref ::session/manager)
    ::sto/storage        (ig/ref ::sto/storage)
    ::mtx/metrics        (ig/ref ::mtx/metrics)
    ::mbus/msgbus        (ig/ref ::mbus/msgbus)
    :app.nitrate/client  (ig/ref :app.nitrate/client)
    ::rds/client         (ig/ref ::rds/client)
    ::setup/props        (ig/ref ::setup/props)}

   ::rpc/routes
   {::rpc/methods            (ig/ref :app.rpc/methods)
    ::rpc/management-methods (ig/ref :app.rpc/management-methods)

    ;; FIXME: revisit if db/pool is necessary here
    ::db/pool                (ig/ref ::db/pool)
    ::session/manager        (ig/ref ::session/manager)
    ::setup/props            (ig/ref ::setup/props)
    ::setup/shared-keys      (ig/ref ::setup/shared-keys)}

   ::jobs/defs
   {:sendmail              (ig/ref ::email/job-def)
    :delete-object         (ig/ref :app.tasks.delete-object/job-def)
    :demo-purge            (ig/ref :app.tasks.demo-purge/demo-purge-job-def)
    :run-webhook           (ig/ref ::webhooks/run-webhook-job-def)
    :process-webhook-event (ig/ref ::webhooks/process-webhook-event-job-def)
    :file-gc               (ig/ref :app.tasks.file-gc/file-gc-job-def)
    :offload-file-data     (ig/ref :app.tasks.offload-file-data/offload-file-data-job-def)
    :objects-gc            (ig/ref :app.tasks.objects-gc/objects-gc-job-def)
    :storage-gc-deleted    (ig/ref ::sto.gc-deleted/storage-gc-deleted-job-def)
    :storage-gc-touched    (ig/ref ::sto.gc-touched/storage-gc-touched-job-def)
    :jobs-gc               (ig/ref :app.jobs.gc/jobs-gc-job-def)
    :tasks-gc              (ig/ref :app.tasks.tasks-gc/tasks-gc-job-def)
    :telemetry             (ig/ref :app.tasks.telemetry/telemetry-job-def)
    :upload-session-gc     (ig/ref :app.tasks.upload-session-gc/upload-session-gc-job-def)
    :session-gc            (ig/ref ::session/session-gc-job-def)
    :file-gc-scheduler     (ig/ref :app.tasks.file-gc-scheduler/file-gc-scheduler-job-def)
    :audit-log-archive     (ig/ref :app.loggers.audit.archive-task/audit-log-archive-job-def)
    :audit-log-gc          (ig/ref :app.loggers.audit.gc-task/audit-log-gc-job-def)}

   :app.email/job-def
   {::email/sendmail (ig/ref ::email/sendmail)}

   :app.tasks.delete-object/job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.demo-purge/demo-purge-job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.loggers.webhooks/run-webhook-job-def
   {::db/pool     (ig/ref ::db/pool)
    ::http/client (ig/ref ::http.client/client)}

   :app.loggers.webhooks/process-webhook-event-job-def
   {::db/pool     (ig/ref ::db/pool)
    ::http/client (ig/ref ::http.client/client)}

   :app.tasks.file-gc/file-gc-job-def
   {::db/pool   (ig/ref ::db/pool)
    ::sto/storage (ig/ref ::sto/storage)}

   :app.tasks.offload-file-data/offload-file-data-job-def
   {::db/pool (ig/ref ::db/pool)
    ::sto/storage (ig/ref ::sto/storage)}

   :app.tasks.objects-gc/objects-gc-job-def
   {::db/pool    (ig/ref ::db/pool)
    ::sto/storage (ig/ref ::sto/storage)}

   :app.storage.gc-deleted/storage-gc-deleted-job-def
   {::db/pool     (ig/ref ::db/pool)
    ::sto/storage (ig/ref ::sto/storage)}

   :app.storage.gc-touched/storage-gc-touched-job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.jobs.gc/jobs-gc-job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.tasks-gc/tasks-gc-job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.telemetry/telemetry-job-def
   {::db/pool     (ig/ref ::db/pool)
    ::http/client (ig/ref ::http.client/client)
    ::setup/props (ig/ref ::setup/props)}

   :app.tasks.upload-session-gc/upload-session-gc-job-def
   {::db/pool (ig/ref ::db/pool)}

   ::session/session-gc-job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.tasks.file-gc-scheduler/file-gc-scheduler-job-def
   {::db/pool (ig/ref ::db/pool)}

   :app.loggers.audit.archive-task/audit-log-archive-job-def
   {::db/pool        (ig/ref ::db/pool)
    ::http/client    (ig/ref ::http.client/client)
    ::setup/shared-keys (ig/ref ::setup/shared-keys)}

   :app.loggers.audit.gc-task/audit-log-gc-job-def
   {::db/pool (ig/ref ::db/pool)}

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

   ::srepl/urepl
   {:port (cf/get :urepl-port 6062)
    :host (cf/get :urepl-host "localhost")}

   ::srepl/prepl
   {:port (cf/get :prepl-port 6063)
    :host (cf/get :prepl-host "localhost")}

   ::srepl/nrepl
   {:port (cf/get :nrepl-port 6064)
    :host (cf/get :nrepl-host "localhost")}

   ::setup/templates {}

   ::setup/props
   {::db/pool    (ig/ref ::db/pool)
    ::setup/key  (cf/get :secret-key)

    ;; NOTE: this dependency is only necessary for proper initialization ordering, props
    ;; module requires the migrations to run before initialize.
    ::migrations (ig/ref :app.migrations/migrations)}

   ::setup/shared-keys
   {::setup/props    (ig/ref ::setup/props)
    :nexus           (cf/get :nexus-shared-key)
    :admin-console   (cf/get :admin-console-shared-key)
    :exporter        (cf/get :exporter-shared-key)
    :media-processor (cf/get :media-processor-shared-key)}

   ::setup/clock
   {}

   :app.loggers.mattermost/reporter
   {::http.client/client (ig/ref ::http.client/client)}

   :app.loggers.database/reporter
   {::db/pool (ig/ref ::db/pool)}

   ::sto/storage
   {::db/pool      (ig/ref ::db/pool)
    ::sto/backends
    {:s3 (ig/ref :app.storage.s3/backend)
     :fs (ig/ref :app.storage.fs/backend)

     ;; LEGACY (should not be removed, can only be removed after an
     ;; explicit migration because the database objects/rows will
     ;; still reference the old names).
     :assets-s3 (ig/ref :app.storage.s3/backend)
     :assets-fs (ig/ref :app.storage.fs/backend)}}

   :app.storage.s3/backend
   {::sto.s3/region     (or (cf/get :storage-assets-s3-region)
                            (cf/get :objects-storage-s3-region))
    ::sto.s3/endpoint   (or (cf/get :storage-assets-s3-endpoint)
                            (cf/get :objects-storage-s3-endpoint))
    ::sto.s3/bucket     (or (cf/get :storage-assets-s3-bucket)
                            (cf/get :objects-storage-s3-bucket))
    ::sto.s3/io-threads (or (cf/get :storage-assets-s3-io-threads)
                            (cf/get :objects-storage-s3-io-threads))

    ::wrk/netty-io-executor
    (ig/ref ::wrk/netty-io-executor)}

   :app.storage.fs/backend
   {::sto.fs/directory (or (cf/get :storage-assets-fs-directory)
                           (cf/get :objects-storage-fs-directory))}})

(def worker-config
  {::wrk/cron
   {::jobs/defs               (ig/ref ::jobs/defs)
    ::db/pool                 (ig/ref ::db/pool)
    ::wrk/entries
    [{:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :session-gc}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :objects-gc}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :storage-gc-deleted}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :storage-gc-touched}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :storage-pending-gc}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :jobs-gc}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :tasks-gc}

     {:cron #penpot/cron "0 0 0 * * ?" ;; daily
      :task :upload-session-gc}

     {:cron #penpot/cron "0 0 2 * * ?" ;; daily
      :task :file-gc-scheduler}

     {:cron #penpot/cron "0 30 */3,23 * * ?"
      :task :telemetry}

     (when (contains? cf/flags :audit-log-archive)
       {:cron #penpot/cron "0 */5 * * * ?" ;; every 5m
        :task :audit-log-archive})

     (when (contains? cf/flags :audit-log-gc)
       {:cron #penpot/cron "30 */5 * * * ?" ;; every 5m
        :task :audit-log-gc})]}

   ::wrk/dispatcher
   {::rds/client  (ig/ref ::rds/client)
    ::mtx/metrics (ig/ref ::mtx/metrics)
    ::db/pool     (ig/ref ::db/pool)
    ::wrk/tenant  (cf/get :tenant)}

   [::default ::wrk/runner]
   {::wrk/parallelism (cf/get ::worker-default-parallelism 1)
    ::wrk/queue       :default
    ::wrk/tenant      (cf/get :tenant)
    ::rds/client      (ig/ref ::rds/client)
    ::jobs/defs       (ig/ref ::jobs/defs)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}

   [::webhook ::wrk/runner]
   {::wrk/parallelism (cf/get ::worker-webhook-parallelism 1)
    ::wrk/queue       :webhooks
    ::wrk/tenant      (cf/get :tenant)
    ::rds/client      (ig/ref ::rds/client)
    ::jobs/defs       (ig/ref ::jobs/defs)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}

   [::binfile ::wrk/runner]
   {::wrk/parallelism (cf/get ::worker-binfile-parallelism 1)
    ::wrk/queue       :binfile
    ::wrk/tenant      (cf/get :tenant)
    ::rds/client      (ig/ref ::rds/client)
    ::jobs/defs       (ig/ref ::jobs/defs)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}

   [::cron ::wrk/runner]
   {::wrk/parallelism (cf/get ::worker-cron-parallelism 2)
    ::wrk/queue       :cron
    ::wrk/tenant      (cf/get :tenant)
    ::rds/client      (ig/ref ::rds/client)
    ::jobs/defs       (ig/ref ::jobs/defs)
    ::mtx/metrics     (ig/ref ::mtx/metrics)
    ::db/pool         (ig/ref ::db/pool)}})


(defn start
  []
  (cf/validate!)
  (ig/load-namespaces (merge system-config worker-config))
  (alter-var-root #'app.system/system
                  (fn [sys]
                    (some-> sys not-empty ig/halt!)
                    (-> system-config
                        (cond-> (contains? cf/flags :backend-worker)
                          (merge worker-config))
                        (ig/expand)
                        (ig/init))))

  (l/inf :hint "welcome to penpot"
         :flags (str/join "," (map name cf/flags))
         :worker? (contains? cf/flags :backend-worker)
         :version (:full cf/version))
  :start)

(defn resume
  []
  (cf/validate!)
  (ig/load-namespaces (merge system-config worker-config))
  (alter-var-root #'app.system/system
                  (fn [sys]
                    (let [config (-> system-config
                                     (cond-> (contains? cf/flags :backend-worker)
                                       (merge worker-config))
                                     (ig/expand))]
                      (if-let [sys (not-empty sys)]
                        (ig/resume config sys)
                        (ig/init config)))))
  :resume)

(defn start-custom
  [config]
  (ig/load-namespaces config)
  (alter-var-root #'app.system/system
                  (fn [sys]
                    (some-> sys not-empty ig/halt!)
                    (-> config
                        (ig/expand)
                        (ig/init)))))

(defn stop
  []
  (alter-var-root #'app.system/system
                  (fn [sys]
                    (some-> sys not-empty ig/halt!)
                    {}))
  :stop)

(defn suspend
  []
  (alter-var-root #'app.system/system
                  (fn [sys]
                    (some-> sys not-empty ig/suspend!)
                    sys))
  :suspend)

(defn restart
  []
  (suspend)
  (repl/refresh :after 'app.main/resume))

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

(defn -main
  [& _args]
  (try
    (ex/ignoring
     (repl/disable-reload! (find-ns 'integrant.core))
     (repl/disable-reload! (find-ns 'app.system))
     (repl/disable-reload! (find-ns 'app.common.debug)))

    (let [p (promise)]
      (start)
      (deref p))
    (catch Throwable cause
      (ex/print-throwable cause)
      (px/sleep 500)
      (System/exit -1))))
