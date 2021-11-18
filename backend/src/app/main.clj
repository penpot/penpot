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
   [integrant.core :as ig]))

(def system-config
  {:app.db/pool
   {:uri        (cf/get :database-uri)
    :username   (cf/get :database-username)
    :password   (cf/get :database-password)
    :metrics    (ig/ref :app.metrics/metrics)
    :migrations (ig/ref :app.migrations/all)
    :name :main
    :min-pool-size 0
    :max-pool-size 30}

   :app.metrics/metrics
   {:definitions
    {:profile-register
     {:name "actions_profile_register_count"
      :help "A global counter of user registrations."
      :type :counter}

     :profile-activation
     {:name "actions_profile_activation_count"
      :help "A global counter of profile activations"
      :type :counter}

     :update-file-changes
     {:name "rpc_update_file_changes_total"
      :help "A total number of changes submitted to update-file."
      :type :counter}

     :update-file-bytes-processed
     {:name "rpc_update_file_bytes_processed_total"
      :help "A total number of bytes processed by update-file."
      :type :counter}}}

   :app.migrations/all
   {:main (ig/ref :app.migrations/migrations)}

   :app.migrations/migrations
   {}

   :app.msgbus/msgbus
   {:backend   (cf/get :msgbus-backend :redis)
    :redis-uri (cf/get :redis-uri)}

   :app.tokens/tokens
   {:keys (ig/ref :app.setup/keys)}

   :app.storage/gc-deleted-task
   {:pool     (ig/ref :app.db/pool)
    :storage  (ig/ref :app.storage/storage)
    :min-age  (dt/duration {:hours 2})}

   :app.storage/gc-touched-task
   {:pool     (ig/ref :app.db/pool)}

   :app.storage/recheck-task
   {:pool     (ig/ref :app.db/pool)
    :storage  (ig/ref :app.storage/storage)}

   :app.http.session/session
   {:pool   (ig/ref :app.db/pool)
    :tokens (ig/ref :app.tokens/tokens)}

   :app.http.session/gc-task
   {:pool        (ig/ref :app.db/pool)
    :max-age     (cf/get :http-session-idle-max-age)}

   :app.http.session/updater
   {:pool           (ig/ref :app.db/pool)
    :metrics        (ig/ref :app.metrics/metrics)
    :executor       (ig/ref :app.worker/executor)
    :session        (ig/ref :app.http.session/session)
    :max-batch-age  (cf/get :http-session-updater-batch-max-age)
    :max-batch-size (cf/get :http-session-updater-batch-max-size)}

   :app.http.awsns/handler
   {:tokens  (ig/ref :app.tokens/tokens)
    :pool    (ig/ref :app.db/pool)}

   :app.http/server
   {:port    (cf/get :http-server-port)
    :router  (ig/ref :app.http/router)
    :metrics (ig/ref :app.metrics/metrics)
    :ws      {"/ws/notifications" (ig/ref :app.notifications/handler)}}

   :app.http/router
   {:rpc         (ig/ref :app.rpc/rpc)
    :session     (ig/ref :app.http.session/session)
    :tokens      (ig/ref :app.tokens/tokens)
    :public-uri  (cf/get :public-uri)
    :metrics     (ig/ref :app.metrics/metrics)
    :oauth       (ig/ref :app.http.oauth/handler)
    :assets      (ig/ref :app.http.assets/handlers)
    :storage     (ig/ref :app.storage/storage)
    :sns-webhook (ig/ref :app.http.awsns/handler)
    :feedback    (ig/ref :app.http.feedback/handler)
    :audit-http-handler   (ig/ref :app.loggers.audit/http-handler)
    :error-report-handler (ig/ref :app.loggers.database/handler)}

   :app.http.assets/handlers
   {:metrics           (ig/ref :app.metrics/metrics)
    :assets-path       (cf/get :assets-path)
    :storage           (ig/ref :app.storage/storage)
    :cache-max-age     (dt/duration {:hours 24})
    :signature-max-age (dt/duration {:hours 24 :minutes 5})}

   :app.http.feedback/handler
   {:pool (ig/ref :app.db/pool)}

   :app.http.oauth/handler
   {:rpc           (ig/ref :app.rpc/rpc)
    :session       (ig/ref :app.http.session/session)
    :pool          (ig/ref :app.db/pool)
    :tokens        (ig/ref :app.tokens/tokens)
    :audit         (ig/ref :app.loggers.audit/collector)
    :public-uri    (cf/get :public-uri)}

   :app.rpc/rpc
   {:pool       (ig/ref :app.db/pool)
    :session    (ig/ref :app.http.session/session)
    :tokens     (ig/ref :app.tokens/tokens)
    :metrics    (ig/ref :app.metrics/metrics)
    :storage    (ig/ref :app.storage/storage)
    :msgbus     (ig/ref :app.msgbus/msgbus)
    :public-uri (cf/get :public-uri)
    :audit      (ig/ref :app.loggers.audit/collector)}

   :app.notifications/handler
   {:msgbus   (ig/ref :app.msgbus/msgbus)
    :pool     (ig/ref :app.db/pool)
    :session  (ig/ref :app.http.session/session)
    :metrics  (ig/ref :app.metrics/metrics)
    :executor (ig/ref :app.worker/executor)}

   :app.worker/executor
   {:min-threads 0
    :max-threads 256
    :idle-timeout 60000
    :name :worker}

   :app.worker/worker
   {:executor (ig/ref :app.worker/executor)
    :tasks    (ig/ref :app.worker/registry)
    :metrics  (ig/ref :app.metrics/metrics)
    :pool     (ig/ref :app.db/pool)}

   :app.worker/scheduler
   {:executor   (ig/ref :app.worker/executor)
    :tasks      (ig/ref :app.worker/registry)
    :pool       (ig/ref :app.db/pool)
    :schedule
    [{:cron #app/cron "0 0 0 * * ?" ;; daily
      :task :file-media-gc}

     {:cron #app/cron "0 0 * * * ?"  ;; hourly
      :task :file-xlog-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :storage-deleted-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :storage-touched-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :session-gc}

     {:cron #app/cron "0 0 * * * ?"  ;; hourly
      :task :storage-recheck}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :objects-gc}

     {:cron #app/cron "0 0 0 * * ?"  ;; daily
      :task :tasks-gc}

     (when (cf/get :fdata-storage-backed)
       {:cron #app/cron "0 0 * * * ?"  ;; hourly
        :task :file-offload})

     (when (contains? cf/flags :audit-log-archive)
       {:cron #app/cron "0 */3 * * * ?" ;; every 3m
        :task :audit-log-archive})

     (when (contains? cf/flags :audit-log-gc)
       {:cron #app/cron "0 0 0 * * ?" ;; daily
        :task :audit-log-gc})

     (when (or (contains? cf/flags :telemetry)
               (cf/get :telemetry-enabled))
       {:cron #app/cron "0 0 */6 * * ?" ;; every 6h
        :task :telemetry})]}

   :app.worker/registry
   {:metrics (ig/ref :app.metrics/metrics)
    :tasks
    {:sendmail           (ig/ref :app.emails/sendmail-handler)
     :objects-gc         (ig/ref :app.tasks.objects-gc/handler)
     :file-media-gc      (ig/ref :app.tasks.file-media-gc/handler)
     :file-xlog-gc       (ig/ref :app.tasks.file-xlog-gc/handler)
     :storage-deleted-gc (ig/ref :app.storage/gc-deleted-task)
     :storage-touched-gc (ig/ref :app.storage/gc-touched-task)
     :storage-recheck    (ig/ref :app.storage/recheck-task)
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

   :app.tasks.file-media-gc/handler
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
    :sprops      (ig/ref :app.setup/props)}

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
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.audit/collector
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.audit/archive-task
   {:uri      (cf/get :audit-log-archive-uri)
    :tokens   (ig/ref :app.tokens/tokens)
    :pool     (ig/ref :app.db/pool)}

   :app.loggers.audit/gc-task
   {:max-age  (cf/get :audit-log-gc-max-age cf/deletion-delay)
    :pool     (ig/ref :app.db/pool)}

   :app.loggers.loki/reporter
   {:uri      (cf/get :loggers-loki-uri)
    :receiver (ig/ref :app.loggers.zmq/receiver)
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.mattermost/reporter
   {:uri      (cf/get :error-report-webhook)
    :receiver (ig/ref :app.loggers.zmq/receiver)
    :pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.database/reporter
   {:receiver (ig/ref :app.loggers.zmq/receiver)
    :pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.database/handler
   {:pool (ig/ref :app.db/pool)}

   :app.loggers.sentry/reporter
   {:dsn                (cf/get :sentry-dsn)
    :trace-sample-rate  (cf/get :sentry-trace-sample-rate 1.0)
    :attach-stack-trace (cf/get :sentry-attach-stack-trace false)
    :debug              (cf/get :sentry-debug false)
    :receiver (ig/ref :app.loggers.zmq/receiver)
    :pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.storage/storage
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)

    :backends {
               :assets-s3 (ig/ref [::assets :app.storage.s3/backend])
               :assets-db (ig/ref [::assets :app.storage.db/backend])
               :assets-fs (ig/ref [::assets :app.storage.fs/backend])
               :tmp       (ig/ref [::tmp  :app.storage.fs/backend])
               :fdata-s3  (ig/ref [::fdata :app.storage.s3/backend])

               ;; keep this for backward compatibility
               :s3        (ig/ref [::assets :app.storage.s3/backend])
               :fs        (ig/ref [::assets :app.storage.fs/backend])}}

   [::fdata :app.storage.s3/backend]
   {:region (cf/get :storage-fdata-s3-region)
    :bucket (cf/get :storage-fdata-s3-bucket)
    :prefix (cf/get :storage-fdata-s3-prefix)}

   [::assets :app.storage.s3/backend]
   {:region (cf/get :storage-assets-s3-region)
    :bucket (cf/get :storage-assets-s3-bucket)}

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
