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
   [app.config :as cfg]
   [app.common.data :as d]
   [app.util.time :as dt]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

;; Set value for all new threads bindings.
(alter-var-root #'*assert* (constantly (:enable-asserts cfg/config)))

(derive :app.telemetry/server :app.http/server)

;; --- Entry point

(defn build-system-config
  [config]
  (merge
   {:app.db/pool
    {:uri        (:database-uri config)
     :username   (:database-username config)
     :password   (:database-password config)
     :metrics    (ig/ref :app.metrics/metrics)
     :migrations (ig/ref :app.migrations/all)
     :name "main"
     :min-pool-size 0
     :max-pool-size 10}

    :app.metrics/metrics
    {}

    :app.migrations/all
    {:uxbox-main (ig/ref :app.migrations/migrations)
     :telemetry  (ig/ref :app.telemetry/migrations)}

    :app.migrations/migrations
    {}

    :app.telemetry/migrations
    {}

    :app.redis/redis
    {:uri (:redis-uri config)}

    :app.tokens/tokens
    {:secret-key (:secret-key config)}

    :app.media-storage/storage
    {:media-directory (:media-directory config)
     :media-uri       (:media-uri config)}

    :app.http.session/session
    {:pool        (ig/ref :app.db/pool)
     :cookie-name "auth-token"}

    :app.http/server
    {:port    (:http-server-port config)
     :handler (ig/ref :app.http/router)
     :ws      {"/ws/notifications" (ig/ref :app.notifications/handler)}}

    :app.http/router
    {:rpc         (ig/ref :app.rpc/rpc)
     :session     (ig/ref :app.http.session/session)
     :tokens      (ig/ref :app.tokens/tokens)
     :public-uri  (:public-uri config)
     :metrics     (ig/ref :app.metrics/metrics)
     :google-auth (ig/ref :app.http.auth/google)
     :gitlab-auth (ig/ref :app.http.auth/gitlab)
     :ldap-auth   (ig/ref :app.http.auth/ldap)}

    :app.rpc/rpc
    {:pool    (ig/ref :app.db/pool)
     :session (ig/ref :app.http.session/session)
     :tokens  (ig/ref :app.tokens/tokens)
     :metrics (ig/ref :app.metrics/metrics)
     :storage (ig/ref :app.media-storage/storage)
     :redis   (ig/ref :app.redis/redis)}


    :app.notifications/handler
    {:redis   (ig/ref :app.redis/redis)
     :pool    (ig/ref :app.db/pool)
     :session (ig/ref :app.http.session/session)}

    :app.http.auth/google
    {:rpc           (ig/ref :app.rpc/rpc)
     :session       (ig/ref :app.http.session/session)
     :tokens        (ig/ref :app.tokens/tokens)
     :public-uri    (:public-uri config)
     :client-id     (:google-client-id config)
     :client-secret (:google-client-secret config)}

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
     [;; TODO: pending to refactor
      ;; {:id "file-media-gc"
      ;;  :cron #app/cron "0 0 0 */1 * ? *" ;; daily
      ;;  :fn (ig/ref :app.tasks.file-media-gc/handler)}

      {:id "file-xlog-gc"
       :cron #app/cron "0 0 0 */1 * ?"  ;; daily
       :fn (ig/ref :app.tasks.file-xlog-gc/handler)}

      {:id "tasks-gc"
       :cron #app/cron "0 0 0 */1 * ?"  ;; daily
       :fn (ig/ref :app.tasks.tasks-gc/handler)}

      (when (:telemetry-enabled cfg/config)
        {:id "telemetry"
         :cron #app/cron "0 0 */3 * * ?" ;; every 3h
         :fn (ig/ref :app.tasks.telemetry/handler)})]}

    :app.tasks/all
    {"sendmail"       (ig/ref :app.tasks.sendmail/handler)
     "delete-object"  (ig/ref :app.tasks.delete-object/handler)
     "delete-profile" (ig/ref :app.tasks.delete-profile/handler)}

    :app.tasks.sendmail/handler
    {:host     (:smtp-host config)
     :port     (:smtp-port config)
     :ssl      (:smtp-ssl config)
     :tls      (:smtp-tls config)
     :enabled  (:smtp-enabled config)
     :username (:smtp-username config)
     :password (:smtp-password config)
     :metrics  (ig/ref :app.metrics/metrics)
     :default-reply-to (:smtp-default-reply-to config)
     :default-from     (:smtp-default-from config)}

    :app.tasks.tasks-gc/handler
    {:pool    (ig/ref :app.db/pool)
     :max-age (dt/duration {:hours 24})
     :metrics (ig/ref :app.metrics/metrics)}

    :app.tasks.delete-object/handler
    {:pool    (ig/ref :app.db/pool)
     :metrics (ig/ref :app.metrics/metrics)}

    :app.tasks.delete-profile/handler
    {:pool    (ig/ref :app.db/pool)
     :metrics (ig/ref :app.metrics/metrics)}

    :app.tasks.file-media-gc/handler
    {:pool    (ig/ref :app.db/pool)
     :metrics (ig/ref :app.metrics/metrics)}

    :app.tasks.file-xlog-gc/handler
    {:pool    (ig/ref :app.db/pool)
     :max-age (dt/duration {:hours 12})
     :metrics (ig/ref :app.metrics/metrics)}

    :app.tasks.telemetry/handler
    {:pool        (ig/ref :app.db/pool)
     :version     (:full cfg/version)
     :uri         (:telemetry-uri cfg/config)}

    :app.srepl/server
    {:port 6062}

    :app.error-reporter/instance
    {:uri (:error-report-webhook cfg/config)
     :executor (ig/ref :app.worker/executor)}}

   (when (:telemetry-server-enabled cfg/config)
     {:app.telemetry/handler
      {:pool     (ig/ref :app.db/pool)
       :executor (ig/ref :app.worker/executor)}

      :app.telemetry/server
      {:port    (:telemetry-server-port config 6063)
       :handler (ig/ref :app.telemetry/handler)
       :name    "telemetry"}})))


(defmethod ig/init-key :default [_ data] data)
(defmethod ig/prep-key :default [_ data] (d/without-nils data))

(defonce system {})

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

(defn -main
  [& _args]
  (start))
