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
    {:main      (ig/ref :app.migrations/migrations)
     :telemetry (ig/ref :app.telemetry/migrations)}

    :app.migrations/migrations
    {}

    :app.telemetry/migrations
    {}

    :app.msgbus/msgbus
    {:uri (:redis-uri config)}

    :app.tokens/tokens
    {:sprops (ig/ref :app.setup/props)}

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
     :cookie-name (:http-session-cookie-name config)}

    :app.http.session/gc-task
    {:pool        (ig/ref :app.db/pool)
     :max-age     (:http-session-idle-max-age config)}

    :app.http.session/updater
    {:pool           (ig/ref :app.db/pool)
     :metrics        (ig/ref :app.metrics/metrics)
     :executor       (ig/ref :app.worker/executor)
     :session        (ig/ref :app.http.session/session)
     :max-batch-age  (:http-session-updater-batch-max-age config)
     :max-batch-size (:http-session-updater-batch-max-size config)}

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
     :oauth       (ig/ref :app.http.oauth/all)
     :assets      (ig/ref :app.http.assets/handlers)
     :svgparse    (ig/ref :app.svgparse/handler)
     :storage     (ig/ref :app.storage/storage)
     :sns-webhook (ig/ref :app.http.awsns/handler)
     :error-report-handler (ig/ref :app.loggers.mattermost/handler)}

    :app.http.assets/handlers
    {:metrics           (ig/ref :app.metrics/metrics)
     :assets-path       (:assets-path config)
     :storage           (ig/ref :app.storage/storage)
     :cache-max-age     (dt/duration {:hours 24})
     :signature-max-age (dt/duration {:hours 24 :minutes 5})}

    :app.http.oauth/all
    {:google (ig/ref :app.http.oauth/google)
     :gitlab (ig/ref :app.http.oauth/gitlab)
     :github (ig/ref :app.http.oauth/github)}

    :app.http.oauth/google
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :client-id     (:google-client-id config)
     :client-secret (:google-client-secret config)}

    :app.http.oauth/github
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :client-id     (:github-client-id config)
     :client-secret (:github-client-secret config)}

    :app.http.oauth/gitlab
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :base-uri      (:gitlab-base-uri config)
     :client-id     (:gitlab-client-id config)
     :client-secret (:gitlab-client-secret config)}

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
     :msgbus  (ig/ref :app.msgbus/msgbus)
     :rlimits (ig/ref :app.rlimits/all)
     :svgc    (ig/ref :app.svgparse/svgc)}

    :app.notifications/handler
    {:msgbus   (ig/ref :app.msgbus/msgbus)
     :pool     (ig/ref :app.db/pool)
     :session  (ig/ref :app.http.session/session)
     :metrics  (ig/ref :app.metrics/metrics)
     :executor (ig/ref :app.worker/executor)}

    :app.worker/executor
    {:name "worker"}

    :app.worker/worker
    {:executor   (ig/ref :app.worker/executor)
     :pool       (ig/ref :app.db/pool)
     :tasks      (ig/ref :app.tasks/registry)}

    :app.worker/scheduler
    {:executor   (ig/ref :app.worker/executor)
     :pool       (ig/ref :app.db/pool)
     :tasks      (ig/ref :app.tasks/registry)
     :schedule
     [{:id "file-media-gc"
       :cron #app/cron "0 0 0 */1 * ? *" ;; daily
       :task :file-media-gc}

      {:id "file-xlog-gc"
       :cron #app/cron "0 0 */1 * * ?"  ;; hourly
       :task :file-xlog-gc}

      {:id "storage-deleted-gc"
       :cron #app/cron "0 0 1 */1 * ?"  ;; daily (1 hour shift)
       :task :storage-deleted-gc}

      {:id "storage-touched-gc"
       :cron #app/cron "0 0 2 */1 * ?"  ;; daily (2 hour shift)
       :task :storage-touched-gc}

      {:id "session-gc"
       :cron #app/cron "0 0 3 */1 * ?"  ;; daily (3 hour shift)
       :task :session-gc}

      {:id "storage-recheck"
       :cron #app/cron "0 0 */1 * * ?"  ;; hourly
       :task :storage-recheck}

      {:id "tasks-gc"
       :cron #app/cron "0 0 0 */1 * ?"  ;; daily
       :task :tasks-gc}

      (when (:telemetry-enabled config)
        {:id   "telemetry"
         :cron #app/cron "0 0 */6 * * ?" ;; every 6h
         :uri  (:telemetry-uri config)
         :task :telemetry})]}

    :app.tasks/registry
    {:metrics (ig/ref :app.metrics/metrics)
     :tasks
     {:sendmail           (ig/ref :app.tasks.sendmail/handler)
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
     :sprops      (ig/ref :app.setup/props)}

    :app.srepl/server
    {:port (:srepl-port config)
     :host (:srepl-host config)}

    :app.setup/props
    {:pool (ig/ref :app.db/pool)}

    :app.loggers.zmq/receiver
    {:endpoint (:loggers-zmq-uri config)}

    :app.loggers.loki/reporter
    {:uri      (:loggers-loki-uri config)
     :receiver (ig/ref :app.loggers.zmq/receiver)
     :executor (ig/ref :app.worker/executor)}

    :app.loggers.mattermost/reporter
    {:uri      (:error-report-webhook config)
     :receiver (ig/ref :app.loggers.zmq/receiver)
     :pool     (ig/ref :app.db/pool)
     :executor (ig/ref :app.worker/executor)}

    :app.loggers.mattermost/handler
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
    (log/infof "welcome to penpot (version: '%s')"
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
