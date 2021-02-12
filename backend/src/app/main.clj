;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.main
  (:require
   [app.common.data :as d]
   [app.config :as cfg]
   [app.util.time :as dt]
   [clojure.pprint :as pprint]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* (constantly (:asserts-enabled cfg/config)))

(derive :app.telemetry/server :app.http/server)

;; --- Entry point

(defn build-system-config
  [config]
  (d/deep-merge
   {:app.db/pool
    {:uri        (:database-uri config)
     :username   (:database-username config)
     :password   (:database-password config)
     :metrics    (ig/ref :app.metrics/metrics)
     :migrations (ig/ref :app.migrations/all)
     :name "main"
     :min-pool-size 0
     :max-pool-size 20}

    :app.metrics/metrics
    {}

    :app.migrations/all
    {:main (ig/ref :app.migrations/migrations)
     :telemetry  (ig/ref :app.telemetry/migrations)}

    :app.migrations/migrations
    {}

    :app.telemetry/migrations
    {}

    :app.redis/redis
    {:uri (:redis-uri config)}

    :app.tokens/tokens
    {:sprops (ig/ref :app.sprops/props)}

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
    {:pool        (ig/ref :app.db/pool)
     :cookie-name "auth-token"}

    :app.http.awsns/handler
    {:tokens  (ig/ref :app.tokens/tokens)
     :pool    (ig/ref :app.db/pool)}

    :app.http/server
    {:port    (:http-server-port config)
     :handler (ig/ref :app.http/router)
     :metrics (ig/ref :app.metrics/metrics)
     :ws      {"/ws/notifications" (ig/ref :app.notifications/handler)}}

    :app.http/router
    {:rpc         (ig/ref :app.rpc/rpc)
     :session     (ig/ref :app.http.session/session)
     :tokens      (ig/ref :app.tokens/tokens)
     :public-uri  (:public-uri config)
     :metrics     (ig/ref :app.metrics/metrics)
     :google-auth (ig/ref :app.http.auth/google)
     :gitlab-auth (ig/ref :app.http.auth/gitlab)
     :github-auth (ig/ref :app.http.auth/github)
     :ldap-auth   (ig/ref :app.http.auth/ldap)
     :assets      (ig/ref :app.http.assets/handlers)
     :svgparse    (ig/ref :app.svgparse/handler)
     :storage     (ig/ref :app.storage/storage)
     :sns-webhook (ig/ref :app.http.awsns/handler)
     :error-report-handler (ig/ref :app.error-reporter/handler)}

    :app.http.assets/handlers
    {:metrics           (ig/ref :app.metrics/metrics)
     :assets-path       (:assets-path config)
     :storage           (ig/ref :app.storage/storage)
     :cache-max-age     (dt/duration {:hours 24})
     :signature-max-age (dt/duration {:hours 24 :minutes 5})}

    :app.http.auth/google
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :client-id     (:google-client-id config)
     :client-secret (:google-client-secret config)}

    :app.http.auth/github
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :client-id     (:github-client-id config)
     :client-secret (:github-client-secret config)}

    :app.http.auth/gitlab
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :base-uri      (:gitlab-base-uri config)
     :client-id     (:gitlab-client-id config)
     :client-secret (:gitlab-client-secret config)}

    :app.http.auth/ldap
    {:host               (:ldap-auth-host config)
     :port               (:ldap-auth-port config)
     :ssl                (:ldap-auth-ssl config)
     :starttls           (:ldap-auth-starttls config)
     :user-query         (:ldap-auth-user-query config)
     :username-attribute (:ldap-auth-username-attribute config)
     :email-attribute    (:ldap-auth-email-attribute config)
     :fullname-attribute (:ldap-auth-fullname-attribute config)
     :avatar-attribute   (:ldap-auth-avatar-attribute config)
     :base-dn            (:ldap-auth-base-dn config)
     :session            (ig/ref :app.http.session/session)
     :rpc                (ig/ref :app.rpc/rpc)}

    :app.svgparse/svgc
    {:metrics (ig/ref :app.metrics/metrics)}

    ;; HTTP Handler for SVG parsing
    :app.svgparse/handler
    {:metrics (ig/ref :app.metrics/metrics)
     :svgc    (ig/ref :app.svgparse/svgc)}

    ;; RLimit definition for password hashing
    :app.rlimits/password
    (:rlimits-password config)

    ;; RLimit definition for image processing
    :app.rlimits/image
    (:rlimits-image config)

    ;; A collection of rlimits as hash-map.
    :app.rlimits/all
    {:password (ig/ref :app.rlimits/password)
     :image    (ig/ref :app.rlimits/image)}

    :app.rpc/rpc
    {:pool    (ig/ref :app.db/pool)
     :session (ig/ref :app.http.session/session)
     :tokens  (ig/ref :app.tokens/tokens)
     :metrics (ig/ref :app.metrics/metrics)
     :storage (ig/ref :app.storage/storage)
     :redis   (ig/ref :app.redis/redis)
     :rlimits (ig/ref :app.rlimits/all)
     :svgc    (ig/ref :app.svgparse/svgc)}

    :app.notifications/handler
    {:redis   (ig/ref :app.redis/redis)
     :pool    (ig/ref :app.db/pool)
     :session (ig/ref :app.http.session/session)
     :metrics (ig/ref :app.metrics/metrics)}

    :app.worker/executor
    {:name "worker"}

    :app.worker/worker
    {:executor   (ig/ref :app.worker/executor)
     :pool       (ig/ref :app.db/pool)
     :tasks      (ig/ref :app.tasks/all)}

    :app.worker/scheduler
    {:executor   (ig/ref :app.worker/executor)
     :pool       (ig/ref :app.db/pool)
     :schedule
     [{:id "file-media-gc"
       :cron #app/cron "0 0 0 */1 * ? *" ;; daily
       :fn (ig/ref :app.tasks.file-media-gc/handler)}

      {:id "file-xlog-gc"
       :cron #app/cron "0 0 */1 * * ?"  ;; hourly
       :fn (ig/ref :app.tasks.file-xlog-gc/handler)}

      {:id "storage-deleted-gc"
       :cron #app/cron "0 0 1 */1 * ?"  ;; daily (1 hour shift)
       :fn (ig/ref :app.storage/gc-deleted-task)}

      {:id "storage-touched-gc"
       :cron #app/cron "0 0 2 */1 * ?"  ;; daily (2 hour shift)
       :fn (ig/ref :app.storage/gc-touched-task)}

      {:id "storage-recheck"
       :cron #app/cron "0 0 */1 * * ?"  ;; hourly
       :fn (ig/ref :app.storage/recheck-task)}

      {:id "tasks-gc"
       :cron #app/cron "0 0 0 */1 * ?"  ;; daily
       :fn (ig/ref :app.tasks.tasks-gc/handler)}

      (when (:telemetry-enabled config)
        {:id   "telemetry"
         :cron #app/cron "0 0 */6 * * ?" ;; every 6h
         :uri  (:telemetry-uri config)
         :fn   (ig/ref :app.tasks.telemetry/handler)})]}

    :app.tasks/all
    {"sendmail"       (ig/ref :app.tasks.sendmail/handler)
     "delete-object"  (ig/ref :app.tasks.delete-object/handler)
     "delete-profile" (ig/ref :app.tasks.delete-profile/handler)}

    :app.tasks.sendmail/handler
    {:host             (:smtp-host config)
     :port             (:smtp-port config)
     :ssl              (:smtp-ssl config)
     :tls              (:smtp-tls config)
     :enabled          (:smtp-enabled config)
     :username         (:smtp-username config)
     :password         (:smtp-password config)
     :metrics          (ig/ref :app.metrics/metrics)
     :default-reply-to (:smtp-default-reply-to config)
     :default-from     (:smtp-default-from config)}

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
     :version     (:full cfg/version)
     :uri         (:telemetry-uri config)
     :sprops      (ig/ref :app.sprops/props)}

    :app.srepl/server
    {:port (:srepl-port config)
     :host (:srepl-host config)}

    :app.sprops/props
    {:pool (ig/ref :app.db/pool)}

    :app.error-reporter/reporter
    {:uri      (:error-report-webhook config)
     :pool     (ig/ref :app.db/pool)
     :executor (ig/ref :app.worker/executor)}

    :app.error-reporter/handler
    {:pool (ig/ref :app.db/pool)}

    :app.storage/storage
    {:pool     (ig/ref :app.db/pool)
     :executor (ig/ref :app.worker/executor)
     :backend  (:storage-backend config :fs)
     :backends {:s3  (ig/ref [::main :app.storage.s3/backend])
                :db  (ig/ref [::main :app.storage.db/backend])
                :fs  (ig/ref [::main :app.storage.fs/backend])
                :tmp (ig/ref [::tmp  :app.storage.fs/backend])}}

    [::main :app.storage.s3/backend]
    {:region (:storage-s3-region config)
     :bucket (:storage-s3-bucket config)}

    [::main :app.storage.fs/backend]
    {:directory (:storage-fs-directory config)}

    [::tmp :app.storage.fs/backend]
    {:directory "/tmp/penpot"}

    [::main :app.storage.db/backend]
    {:pool (ig/ref :app.db/pool)}}

   (when (:telemetry-server-enabled config)
     {:app.telemetry/handler
      {:pool     (ig/ref :app.db/pool)
       :executor (ig/ref :app.worker/executor)}

      :app.telemetry/server
      {:port    (:telemetry-server-port config 6063)
       :handler (ig/ref :app.telemetry/handler)
       :name    "telemetry"}})))

(defmethod ig/init-key :default [_ data] data)
(defmethod ig/prep-key :default
  [_ data]
  (if (map? data)
    (d/without-nils data)
    data))

(def system nil)

(defn start
  []
  (let [system-config (build-system-config cfg/config)]
    (ig/load-namespaces system-config)
    (alter-var-root #'system (fn [sys]
                               (when sys (ig/halt! sys))
                               (-> system-config
                                   (ig/prep)
                                   (ig/init))))
    (log/infof "Welcome to penpot! Version: '%s'."
               (:full cfg/version))))

(defn stop
  []
  (alter-var-root #'system (fn [sys]
                             (when sys (ig/halt! sys))
                             nil)))

(prefer-method print-method
               clojure.lang.IRecord
               clojure.lang.IDeref)

(prefer-method pprint/simple-dispatch
               clojure.lang.IPersistentMap
               clojure.lang.IDeref)

(defn -main
  [& _args]
  (start))
