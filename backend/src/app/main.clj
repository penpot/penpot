;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.util.time :as dt]
   [integrant.core :as ig])
  (:gen-class))

(def system-config
  {:app.db/pool
   {:uri        (cf/get :database-uri)
    :username   (cf/get :database-username)
    :password   (cf/get :database-password)
    :read-only  (cf/get :database-readonly false)
    :metrics    (ig/ref :app.metrics/metrics)
    :migrations (ig/ref :app.migrations/all)
    :name :main
    :min-size (cf/get :database-min-pool-size 0)
    :max-size (cf/get :database-max-pool-size 30)}

   ;; Default thread pool for IO operations
   [::default :app.worker/executor]
   {:parallelism (cf/get :default-executor-parallelism 60)
    :prefix :default}

   ;; Constrained thread pool. Should only be used from high resources
   ;; demanding operations.
   [::blocking :app.worker/executor]
   {:parallelism (cf/get :blocking-executor-parallelism 10)
    :prefix :blocking}

   ;; Dedicated thread pool for backround tasks execution.
   [::worker :app.worker/executor]
   {:parallelism (cf/get :worker-executor-parallelism 10)
    :prefix :worker}

   :app.worker/scheduler
   {:parallelism 1
    :prefix :scheduler}

   :app.worker/executors
   {:default  (ig/ref [::default :app.worker/executor])
    :worker   (ig/ref [::worker :app.worker/executor])
    :blocking (ig/ref [::blocking :app.worker/executor])}

   :app.worker/executors-monitor
   {:metrics   (ig/ref :app.metrics/metrics)
    :scheduler (ig/ref :app.worker/scheduler)
    :executors (ig/ref :app.worker/executors)}

   :app.migrations/migrations
   {}

   :app.metrics/metrics
   {}

   :app.migrations/all
   {:main (ig/ref :app.migrations/migrations)}

   :app.msgbus/msgbus
   {:backend   (cf/get :msgbus-backend :redis)
    :executor  (ig/ref [::default :app.worker/executor])
    :redis-uri (cf/get :redis-uri)}

   :app.tokens/tokens
   {:keys (ig/ref :app.setup/keys)}

   :app.storage/gc-deleted-task
   {:pool     (ig/ref :app.db/pool)
    :storage  (ig/ref :app.storage/storage)
    :executor (ig/ref [::worker :app.worker/executor])
    :min-age  (dt/duration {:hours 2})}

   :app.storage/gc-touched-task
   {:pool (ig/ref :app.db/pool)}

   :app.http/client
   {:executor (ig/ref [::default :app.worker/executor])}

   :app.http/session
   {:store (ig/ref :app.http.session/store)}

   :app.http.session/store
   {:pool     (ig/ref :app.db/pool)
    :tokens   (ig/ref :app.tokens/tokens)
    :executor (ig/ref [::default :app.worker/executor])}

   :app.http.session/gc-task
   {:pool        (ig/ref :app.db/pool)
    :max-age     (cf/get :http-session-idle-max-age)}

   :app.http.session/updater
   {:pool           (ig/ref :app.db/pool)
    :metrics        (ig/ref :app.metrics/metrics)
    :executor       (ig/ref [::worker :app.worker/executor])
    :session        (ig/ref :app.http/session)
    :max-batch-age  (cf/get :http-session-updater-batch-max-age)
    :max-batch-size (cf/get :http-session-updater-batch-max-size)}

   :app.http.awsns/handler
   {:tokens      (ig/ref :app.tokens/tokens)
    :pool        (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)
    :executor    (ig/ref [::worker :app.worker/executor])}

   :app.http/server
   {:port        (cf/get :http-server-port)
    :host        (cf/get :http-server-host)
    :router      (ig/ref :app.http/router)
    :metrics     (ig/ref :app.metrics/metrics)
    :executor    (ig/ref [::default :app.worker/executor])
    :io-threads  (cf/get :http-server-io-threads)}

   :app.http/router
   {:assets        (ig/ref :app.http.assets/handlers)
    :feedback      (ig/ref :app.http.feedback/handler)
    :session       (ig/ref :app.http/session)
    :awsns-handler (ig/ref :app.http.awsns/handler)
    :oauth         (ig/ref :app.http.oauth/handler)
    :debug         (ig/ref :app.http.debug/handlers)
    :ws            (ig/ref :app.http.websocket/handler)
    :metrics       (ig/ref :app.metrics/metrics)
    :public-uri    (cf/get :public-uri)
    :storage       (ig/ref :app.storage/storage)
    :tokens        (ig/ref :app.tokens/tokens)
    :audit-handler (ig/ref :app.loggers.audit/http-handler)
    :rpc           (ig/ref :app.rpc/rpc)
    :executor      (ig/ref [::default :app.worker/executor])}

   :app.http.debug/handlers
   {:pool (ig/ref :app.db/pool)
    :executor (ig/ref [::worker :app.worker/executor])}

   :app.http.websocket/handler
   {:pool     (ig/ref :app.db/pool)
    :metrics  (ig/ref :app.metrics/metrics)
    :msgbus   (ig/ref :app.msgbus/msgbus)}

   :app.http.assets/handlers
   {:metrics           (ig/ref :app.metrics/metrics)
    :assets-path       (cf/get :assets-path)
    :storage           (ig/ref :app.storage/storage)
    :executor          (ig/ref [::default :app.worker/executor])
    :cache-max-age     (dt/duration {:hours 24})
    :signature-max-age (dt/duration {:hours 24 :minutes 5})}

   :app.http.feedback/handler
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref [::default :app.worker/executor])}

   :app.http.oauth/handler
   {:rpc         (ig/ref :app.rpc/rpc)
    :session     (ig/ref :app.http/session)
    :pool        (ig/ref :app.db/pool)
    :tokens      (ig/ref :app.tokens/tokens)
    :audit       (ig/ref :app.loggers.audit/collector)
    :executor    (ig/ref [::default :app.worker/executor])
    :http-client (ig/ref :app.http/client)
    :public-uri  (cf/get :public-uri)}

   :app.rpc/rpc
   {:pool        (ig/ref :app.db/pool)
    :session     (ig/ref :app.http/session)
    :tokens      (ig/ref :app.tokens/tokens)
    :metrics     (ig/ref :app.metrics/metrics)
    :storage     (ig/ref :app.storage/storage)
    :msgbus      (ig/ref :app.msgbus/msgbus)
    :public-uri  (cf/get :public-uri)
    :audit       (ig/ref :app.loggers.audit/collector)
    :http-client (ig/ref :app.http/client)
    :executors   (ig/ref :app.worker/executors)}

   :app.worker/worker
   {:executor (ig/ref [::worker :app.worker/executor])
    :tasks    (ig/ref :app.worker/registry)
    :metrics  (ig/ref :app.metrics/metrics)
    :pool     (ig/ref :app.db/pool)}

   :app.worker/cron
   {:executor   (ig/ref [::worker :app.worker/executor])
    :scheduler  (ig/ref :app.worker/scheduler)
    :tasks      (ig/ref :app.worker/registry)
    :pool       (ig/ref :app.db/pool)
    :entries
    [{:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :file-gc}

     {:cron #app/cron "0 0 * * * ?"  ;; hourly
      :task :file-xlog-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :storage-deleted-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :storage-touched-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :session-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :objects-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :tasks-gc}

     (when (cf/get :fdata-storage-backed)
       {:cron #app/cron "0 0 * * * ?"  ;; hourly
        :task :file-offload})

     (when (contains? cf/flags :audit-log-archive)
       {:cron #app/cron "0 */5 * * * ?" ;; every 5m
        :task :audit-log-archive})

     (when (contains? cf/flags :audit-log-gc)
       {:cron #app/cron "0 0 0 * * ?" ;; daily
        :task :audit-log-gc})

     (when (or (contains? cf/flags :telemetry)
               (cf/get :telemetry-enabled))
       {:cron #app/cron "0 30 */3,23 * * ?"
        :task :telemetry})]}

   :app.worker/registry
   {:metrics (ig/ref :app.metrics/metrics)
    :tasks
    {:sendmail           (ig/ref :app.emails/sendmail-handler)
     :objects-gc         (ig/ref :app.tasks.objects-gc/handler)
     :file-gc            (ig/ref :app.tasks.file-gc/handler)
     :file-xlog-gc       (ig/ref :app.tasks.file-xlog-gc/handler)
     :storage-deleted-gc (ig/ref :app.storage/gc-deleted-task)
     :storage-touched-gc (ig/ref :app.storage/gc-touched-task)
     :tasks-gc           (ig/ref :app.tasks.tasks-gc/handler)
     :telemetry          (ig/ref :app.tasks.telemetry/handler)
     :session-gc         (ig/ref :app.http.session/gc-task)
     :file-offload       (ig/ref :app.tasks.file-offload/handler)
     :audit-log-archive  (ig/ref :app.loggers.audit/archive-task)
     :audit-log-gc       (ig/ref :app.loggers.audit/gc-task)}}

   :app.emails/sendmail-handler
   {:host             (cf/get :smtp-host)
    :port             (cf/get :smtp-port)
    :ssl              (cf/get :smtp-ssl)
    :tls              (cf/get :smtp-tls)
    :username         (cf/get :smtp-username)
    :password         (cf/get :smtp-password)
    :metrics          (ig/ref :app.metrics/metrics)
    :default-reply-to (cf/get :smtp-default-reply-to)
    :default-from     (cf/get :smtp-default-from)}

   :app.tasks.tasks-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :max-age cf/deletion-delay}

   :app.tasks.objects-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :storage (ig/ref :app.storage/storage)
    :max-age cf/deletion-delay}

   :app.tasks.file-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :max-age cf/deletion-delay}

   :app.tasks.file-xlog-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :max-age (dt/duration {:hours 72})}

   :app.tasks.file-offload/handler
   {:pool    (ig/ref :app.db/pool)
    :max-age (dt/duration {:seconds 5})
    :storage (ig/ref :app.storage/storage)
    :backend (cf/get :fdata-storage-backed :fdata-s3)}

   :app.tasks.telemetry/handler
   {:pool        (ig/ref :app.db/pool)
    :version     (:full cf/version)
    :uri         (cf/get :telemetry-uri)
    :sprops      (ig/ref :app.setup/props)
    :http-client (ig/ref :app.http/client)}

   :app.srepl/server
   {:port (cf/get :srepl-port)
    :host (cf/get :srepl-host)}

   :app.setup/props
   {:pool (ig/ref :app.db/pool)
    :key  (cf/get :secret-key)}

   :app.setup/keys
   {:props (ig/ref :app.setup/props)}

   :app.loggers.zmq/receiver
   {:endpoint (cf/get :loggers-zmq-uri)}

   :app.loggers.audit/http-handler
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref [::default :app.worker/executor])}

   :app.loggers.audit/collector
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref [::worker :app.worker/executor])}

   :app.loggers.audit/archive-task
   {:uri         (cf/get :audit-log-archive-uri)
    :tokens      (ig/ref :app.tokens/tokens)
    :pool        (ig/ref :app.db/pool)
    :http-client (ig/ref :app.http/client)}

   :app.loggers.audit/gc-task
   {:max-age  (cf/get :audit-log-gc-max-age cf/deletion-delay)
    :pool     (ig/ref :app.db/pool)}

   :app.loggers.loki/reporter
   {:uri         (cf/get :loggers-loki-uri)
    :receiver    (ig/ref :app.loggers.zmq/receiver)
    :http-client (ig/ref :app.http/client)}

   :app.loggers.mattermost/reporter
   {:uri         (cf/get :error-report-webhook)
    :receiver    (ig/ref :app.loggers.zmq/receiver)
    :http-client (ig/ref :app.http/client)}

   :app.loggers.database/reporter
   {:receiver (ig/ref :app.loggers.zmq/receiver)
    :pool     (ig/ref :app.db/pool)
    :executor (ig/ref [::worker :app.worker/executor])}

   :app.storage/storage
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref [::default :app.worker/executor])

    :backends
    {:assets-s3 (ig/ref [::assets :app.storage.s3/backend])
     :assets-db (ig/ref [::assets :app.storage.db/backend])
     :assets-fs (ig/ref [::assets :app.storage.fs/backend])

     :tmp       (ig/ref [::tmp  :app.storage.fs/backend])
     :fdata-s3  (ig/ref [::fdata :app.storage.s3/backend])

     ;; keep this for backward compatibility
     :s3        (ig/ref [::assets :app.storage.s3/backend])
     :fs        (ig/ref [::assets :app.storage.fs/backend])}}

   [::fdata :app.storage.s3/backend]
   {:region   (cf/get :storage-fdata-s3-region)
    :bucket   (cf/get :storage-fdata-s3-bucket)
    :endpoint (cf/get :storage-fdata-s3-endpoint)
    :prefix   (cf/get :storage-fdata-s3-prefix)
    :executor (ig/ref [::default :app.worker/executor])}

   [::assets :app.storage.s3/backend]
   {:region   (cf/get :storage-assets-s3-region)
    :endpoint (cf/get :storage-assets-s3-endpoint)
    :bucket   (cf/get :storage-assets-s3-bucket)
    :executor (ig/ref [::default :app.worker/executor])}

   [::assets :app.storage.fs/backend]
   {:directory (cf/get :storage-assets-fs-directory)}

   [::tmp :app.storage.fs/backend]
   {:directory "/tmp/penpot"}

   [::assets :app.storage.db/backend]
   {:pool (ig/ref :app.db/pool)}})

(def system nil)

(defn start
  []
  (ig/load-namespaces system-config)
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             (-> system-config
                                 (ig/prep)
                                 (ig/init))))
  (l/info :msg "welcome to penpot"
          :version (:full cf/version)))

(defn stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil)))

(defn -main
  [& _args]
  (start))
