;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.util.logging :as l]
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
    :max-pool-size 20}

   :app.metrics/metrics
   {:definitions
    {:profile-register
     {:name "actions_profile_register_count"
      :help "A global counter of user registrations."
      :type :counter}
     :profile-activation
     {:name "actions_profile_activation_count"
      :help "A global counter of profile activations"
      :type :counter}}}

   :app.migrations/all
   {:main (ig/ref :app.migrations/migrations)}

   :app.migrations/migrations
   {}

   :app.msgbus/msgbus
   {:backend   (cf/get :msgbus-backend :redis)
    :redis-uri (cf/get :redis-uri)}

   :app.tokens/tokens
   {:props (ig/ref :app.setup/props)}

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
    :oauth       (ig/ref :app.http.oauth/handlers)
    :assets      (ig/ref :app.http.assets/handlers)
    :storage     (ig/ref :app.storage/storage)
    :sns-webhook (ig/ref :app.http.awsns/handler)
    :feedback    (ig/ref :app.http.feedback/handler)
    :error-report-handler (ig/ref :app.loggers.mattermost/handler)}

   :app.http.assets/handlers
   {:metrics           (ig/ref :app.metrics/metrics)
    :assets-path       (cf/get :assets-path)
    :storage           (ig/ref :app.storage/storage)
    :cache-max-age     (dt/duration {:hours 24})
    :signature-max-age (dt/duration {:hours 24 :minutes 5})}

   :app.http.feedback/handler
   {:pool (ig/ref :app.db/pool)}

   :app.http.oauth/handlers
   {:rpc           (ig/ref :app.rpc/rpc)
    :session       (ig/ref :app.http.session/session)
    :tokens        (ig/ref :app.tokens/tokens)
    :public-uri    (cf/get :public-uri)}

   ;; RLimit definition for password hashing
   :app.rlimits/password
   (cf/get :rlimits-password)

   ;; RLimit definition for image processing
   :app.rlimits/image
   (cf/get :rlimits-image)

   ;; RLimit definition for font processing
   :app.rlimits/font
   (cf/get :rlimits-font 2)

   ;; A collection of rlimits as hash-map.
   :app.rlimits/all
   {:password (ig/ref :app.rlimits/password)
    :image    (ig/ref :app.rlimits/image)
    :font     (ig/ref :app.rlimits/font)}

   :app.rpc/rpc
   {:pool       (ig/ref :app.db/pool)
    :session    (ig/ref :app.http.session/session)
    :tokens     (ig/ref :app.tokens/tokens)
    :metrics    (ig/ref :app.metrics/metrics)
    :storage    (ig/ref :app.storage/storage)
    :msgbus     (ig/ref :app.msgbus/msgbus)
    :rlimits    (ig/ref :app.rlimits/all)
    :public-uri (cf/get :public-uri)}

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
    [{:cron #app/cron "0 0 0 */1 * ? *" ;; daily
      :task :file-media-gc}

     {:cron #app/cron "0 0 */1 * * ?"  ;; hourly
      :task :file-xlog-gc}

     {:cron #app/cron "0 0 1 */1 * ?"  ;; daily (1 hour shift)
      :task :storage-deleted-gc}

     {:cron #app/cron "0 0 2 */1 * ?"  ;; daily (2 hour shift)
      :task :storage-touched-gc}

     {:cron #app/cron "0 0 3 */1 * ?"  ;; daily (3 hour shift)
      :task :session-gc}

     {:cron #app/cron "0 0 */1 * * ?"  ;; hourly
      :task :storage-recheck}

     {:cron #app/cron "0 0 0 */1 * ?"  ;; daily
      :task :tasks-gc}

     (when (cf/get :telemetry-enabled)
       {:cron #app/cron "0 0 */6 * * ?" ;; every 6h
        :task :telemetry})]}

   :app.worker/registry
   {:metrics (ig/ref :app.metrics/metrics)
    :tasks
    {:sendmail           (ig/ref :app.emails/sendmail-handler)
     :delete-object      (ig/ref :app.tasks.delete-object/handler)
     :delete-profile     (ig/ref :app.tasks.delete-profile/handler)
     :file-media-gc      (ig/ref :app.tasks.file-media-gc/handler)
     :file-xlog-gc       (ig/ref :app.tasks.file-xlog-gc/handler)
     :storage-deleted-gc (ig/ref :app.storage/gc-deleted-task)
     :storage-touched-gc (ig/ref :app.storage/gc-touched-task)
     :storage-recheck    (ig/ref :app.storage/recheck-task)
     :tasks-gc           (ig/ref :app.tasks.tasks-gc/handler)
     :telemetry          (ig/ref :app.tasks.telemetry/handler)
     :session-gc         (ig/ref :app.http.session/gc-task)}}

   :app.emails/sendmail-handler
   {:host             (cf/get :smtp-host)
    :port             (cf/get :smtp-port)
    :ssl              (cf/get :smtp-ssl)
    :tls              (cf/get :smtp-tls)
    :enabled          (cf/get :smtp-enabled)
    :username         (cf/get :smtp-username)
    :password         (cf/get :smtp-password)
    :metrics          (ig/ref :app.metrics/metrics)
    :default-reply-to (cf/get :smtp-default-reply-to)
    :default-from     (cf/get :smtp-default-from)}

   :app.tasks.tasks-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :max-age (dt/duration {:hours 24})
    :metrics (ig/ref :app.metrics/metrics)}

   :app.tasks.delete-object/handler
   {:pool    (ig/ref :app.db/pool)
    :metrics (ig/ref :app.metrics/metrics)}

   :app.tasks.delete-storage-object/handler
   {:pool    (ig/ref :app.db/pool)
    :storage (ig/ref :app.storage/storage)
    :metrics (ig/ref :app.metrics/metrics)}

   :app.tasks.delete-profile/handler
   {:pool    (ig/ref :app.db/pool)
    :metrics (ig/ref :app.metrics/metrics)}

   :app.tasks.file-media-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :metrics (ig/ref :app.metrics/metrics)
    :max-age (dt/duration {:hours 48})}

   :app.tasks.file-xlog-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :metrics (ig/ref :app.metrics/metrics)
    :max-age (dt/duration {:hours 48})}

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

   :app.loggers.zmq/receiver
   {:endpoint (cf/get :loggers-zmq-uri)}

   :app.loggers.loki/reporter
   {:uri      (cf/get :loggers-loki-uri)
    :receiver (ig/ref :app.loggers.zmq/receiver)
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.mattermost/reporter
   {:uri      (cf/get :error-report-webhook)
    :receiver (ig/ref :app.loggers.zmq/receiver)
    :pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)}

   :app.loggers.mattermost/handler
   {:pool (ig/ref :app.db/pool)}

   :app.storage/storage
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref :app.worker/executor)
    :backend  (cf/get :storage-backend :fs)
    :backends {:s3  (ig/ref [::main :app.storage.s3/backend])
               :db  (ig/ref [::main :app.storage.db/backend])
               :fs  (ig/ref [::main :app.storage.fs/backend])
               :tmp (ig/ref [::tmp  :app.storage.fs/backend])}}

   [::main :app.storage.s3/backend]
   {:region (cf/get :storage-s3-region)
    :bucket (cf/get :storage-s3-bucket)}

   [::main :app.storage.fs/backend]
   {:directory (cf/get :storage-fs-directory)}

   [::tmp :app.storage.fs/backend]
   {:directory "/tmp/penpot"}

   [::main :app.storage.db/backend]
   {:pool (ig/ref :app.db/pool)}})

(defmethod ig/init-key :default [_ data] data)
(defmethod ig/prep-key :default
  [_ data]
  (if (map? data)
    (d/without-nils data)
    data))

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
