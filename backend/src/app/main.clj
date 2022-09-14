;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main
  (:require
   [app.auth.oidc]
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
    :name       :main
    :min-size   (cf/get :database-min-pool-size 0)
    :max-size   (cf/get :database-max-pool-size 30)}

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

   :app.storage.tmp/cleaner
   {:executor (ig/ref [::worker :app.worker/executor])
    :scheduler (ig/ref :app.worker/scheduler)}

   :app.storage/gc-deleted-task
   {:pool     (ig/ref :app.db/pool)
    :storage  (ig/ref :app.storage/storage)
    :executor (ig/ref [::worker :app.worker/executor])}

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
    :max-age     (cf/get :auth-token-cookie-max-age)}

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

   :app.auth.oidc/google-provider
   {:enabled?      (contains? cf/flags :login-with-google)
    :client-id     (cf/get :google-client-id)
    :client-secret (cf/get :google-client-secret)}

   :app.auth.oidc/github-provider
   {:enabled?      (contains? cf/flags :login-with-github)
    :http-client   (ig/ref :app.http/client)
    :client-id     (cf/get :github-client-id)
    :client-secret (cf/get :github-client-secret)}

   :app.auth.oidc/gitlab-provider
   {:enabled?      (contains? cf/flags :login-with-gitlab)
    :base-uri      (cf/get :gitlab-base-uri "https://gitlab.com")
    :client-id     (cf/get :gitlab-client-id)
    :client-secret (cf/get :gitlab-client-secret)}

   :app.auth.oidc/generic-provider
   {:enabled?      (contains? cf/flags :login-with-oidc)
    :http-client   (ig/ref :app.http/client)

    :client-id     (cf/get :oidc-client-id)
    :client-secret (cf/get :oidc-client-secret)

    :base-uri      (cf/get :oidc-base-uri)

    :token-uri     (cf/get :oidc-token-uri)
    :auth-uri      (cf/get :oidc-auth-uri)
    :user-uri      (cf/get :oidc-user-uri)

    :scopes        (cf/get :oidc-scopes)
    :roles-attr    (cf/get :oidc-roles-attr)
    :roles         (cf/get :oidc-roles)}

   :app.auth.oidc/routes
   {:providers   {:google (ig/ref :app.auth.oidc/google-provider)
                  :github (ig/ref :app.auth.oidc/github-provider)
                  :gitlab (ig/ref :app.auth.oidc/gitlab-provider)
                  :oidc   (ig/ref :app.auth.oidc/generic-provider)}
    :tokens      (ig/ref :app.tokens/tokens)
    :http-client (ig/ref :app.http/client)
    :pool        (ig/ref :app.db/pool)
    :session     (ig/ref :app.http/session)
    :public-uri  (cf/get :public-uri)
    :executor    (ig/ref [::default :app.worker/executor])}

   :app.http/router
   {:assets        (ig/ref :app.http.assets/handlers)
    :feedback      (ig/ref :app.http.feedback/handler)
    :session       (ig/ref :app.http/session)
    :awsns-handler (ig/ref :app.http.awsns/handler)
    :debug-routes  (ig/ref :app.http.debug/routes)
    :oidc-routes   (ig/ref :app.auth.oidc/routes)
    :ws            (ig/ref :app.http.websocket/handler)
    :metrics       (ig/ref :app.metrics/metrics)
    :public-uri    (cf/get :public-uri)
    :storage       (ig/ref :app.storage/storage)
    :tokens        (ig/ref :app.tokens/tokens)
    :audit-handler (ig/ref :app.loggers.audit/http-handler)
    :rpc-routes    (ig/ref :app.rpc/routes)
    :doc-routes    (ig/ref :app.rpc.doc/routes)
    :executor      (ig/ref [::default :app.worker/executor])}

   :app.http.debug/routes
   {:pool     (ig/ref :app.db/pool)
    :executor (ig/ref [::worker :app.worker/executor])
    :storage  (ig/ref :app.storage/storage)
    :session  (ig/ref :app.http/session)}

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

   :app.rpc/methods
   {:pool        (ig/ref :app.db/pool)
    :session     (ig/ref :app.http/session)
    :tokens      (ig/ref :app.tokens/tokens)
    :metrics     (ig/ref :app.metrics/metrics)
    :storage     (ig/ref :app.storage/storage)
    :msgbus      (ig/ref :app.msgbus/msgbus)
    :public-uri  (cf/get :public-uri)
    :audit       (ig/ref :app.loggers.audit/collector)
    :ldap        (ig/ref :app.auth.ldap/provider)
    :http-client (ig/ref :app.http/client)
    :executors   (ig/ref :app.worker/executors)}

   :app.rpc.doc/routes
   {:methods (ig/ref :app.rpc/methods)}

   :app.rpc/routes
   {:methods (ig/ref :app.rpc/methods)}

   :app.worker/registry
   {:metrics (ig/ref :app.metrics/metrics)
    :tasks
    {:sendmail           (ig/ref :app.emails/handler)
     :objects-gc         (ig/ref :app.tasks.objects-gc/handler)
     :file-gc            (ig/ref :app.tasks.file-gc/handler)
     :file-xlog-gc       (ig/ref :app.tasks.file-xlog-gc/handler)
     :storage-gc-deleted (ig/ref :app.storage/gc-deleted-task)
     :storage-gc-touched (ig/ref :app.storage/gc-touched-task)
     :tasks-gc           (ig/ref :app.tasks.tasks-gc/handler)
     :telemetry          (ig/ref :app.tasks.telemetry/handler)
     :session-gc         (ig/ref :app.http.session/gc-task)
     :audit-log-archive  (ig/ref :app.loggers.audit/archive-task)
     :audit-log-gc       (ig/ref :app.loggers.audit/gc-task)}}

   :app.emails/handler
   {:host             (cf/get :smtp-host)
    :port             (cf/get :smtp-port)
    :ssl              (cf/get :smtp-ssl)
    :tls              (cf/get :smtp-tls)
    :username         (cf/get :smtp-username)
    :password         (cf/get :smtp-password)
    :metrics          (ig/ref :app.metrics/metrics)
    :default-reply-to (cf/get :smtp-default-reply-to)
    :default-from     (cf/get :smtp-default-from)
    :enabled?         (contains? cf/flags :smtp)}

   :app.tasks.tasks-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :max-age cf/deletion-delay}

   :app.tasks.objects-gc/handler
   {:pool    (ig/ref :app.db/pool)
    :storage (ig/ref :app.storage/storage)}

   :app.tasks.file-gc/handler
   {:pool (ig/ref :app.db/pool)}

   :app.tasks.file-xlog-gc/handler
   {:pool (ig/ref :app.db/pool)}

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
     :assets-fs (ig/ref [::assets :app.storage.fs/backend])

     ;; keep this for backward compatibility
     :s3        (ig/ref [::assets :app.storage.s3/backend])
     :fs        (ig/ref [::assets :app.storage.fs/backend])}}

   [::assets :app.storage.s3/backend]
   {:region   (cf/get :storage-assets-s3-region)
    :endpoint (cf/get :storage-assets-s3-endpoint)
    :bucket   (cf/get :storage-assets-s3-bucket)
    :executor (ig/ref [::default :app.worker/executor])}

   [::assets :app.storage.fs/backend]
   {:directory (cf/get :storage-assets-fs-directory)}
   })


(def worker-config
  {   :app.worker/cron
   {:executor   (ig/ref [::worker :app.worker/executor])
    :scheduler  (ig/ref :app.worker/scheduler)
    :tasks      (ig/ref :app.worker/registry)
    :pool       (ig/ref :app.db/pool)
    :entries
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
       {:cron #app/cron "0 0 0 * * ?" ;; daily
        :task :audit-log-gc})]}

   :app.worker/worker
   {:executor (ig/ref [::worker :app.worker/executor])
    :tasks    (ig/ref :app.worker/registry)
    :metrics  (ig/ref :app.metrics/metrics)
    :pool     (ig/ref :app.db/pool)}})

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
  (l/info :msg "welcome to penpot"
          :worker? (contains? cf/flags :backend-worker)
          :version (:full cf/version)))

(defn stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil)))

(defn -main
  [& _args]
  (start))
