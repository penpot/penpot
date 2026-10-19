;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-audit-consumer-task-test
  (:require
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as transit]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as-alias http]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumer-task :as consumer]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [app.setup :as-alias setup]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]])
  (:import
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private archive-uri "http://nexus.example/archive")

(defn- insert-pendings!
  "Queue the row with the real producer helper: pendings for the
  enabled consumers, like append-audit-entry does."
  [row-id]
  (consumer/queue-pendings (:app.db/pool th/*system*) [row-id]))

(defn- pending-count
  []
  (:pending (first (th/db-exec! ["SELECT count(*) AS pending FROM audit_log_pending"]))))

(defn- insert-audit-row!
  ([profile-id name]
   (insert-audit-row! profile-id name {:team-id (uuid/next)}))
  ([profile-id name props]
   (let [row (th/db-insert! :audit-log
                            {:id         (uuid/next)
                             :name       name
                             :type       "action"
                             :source     "backend"
                             :profile-id profile-id
                             :ip-addr    (db/inet "127.0.0.1")
                             :props      (db/tjson props)
                             :context    (db/tjson {})
                             :tracked-at (ct/now)
                             :created-at (ct/now)})]
     (insert-pendings! (:id row))
     row)))

(defn- admin-console-event-props
  [admin-console-mock]
  (let [[_ _ params] (:call-args @admin-console-mock)]
    (:props (first (:events params)))))

(defn- run-both!
  "Run both consumer jobs, Nexus first (the historical single-run
  order). The admin-console job takes no params (closed empty schema)."
  [params]
  (th/run-task! :audit-log-consumer-nexus params)
  (th/run-task! :audit-log-consumer-admin-console {}))

(t/deftest consumer-jobs-drain-their-own-queues
  ;; Two separate jobs, one queue each: the nexus job sends without
  ;; touching the admin console, and only the admin-console job
  ;; deletes the last pending and the row. Create profile before
  ;; enabling :admin-console — the system admin-console client is nil
  ;; in tests, and team bootstrap would call it.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (insert-audit-row! (:id prof) "create-file")
        (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (:called? @http-mock))
        (t/is (= 1 (:call-count @http-mock)))
        ;; Nexus drained its own queue; both rows wait on the admin console.
        (t/is (= 2 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 2 (pending-count)))
        (th/run-task! :audit-log-consumer-admin-console {})
        (t/is (= 1 (:call-count @admin-console-mock)))
        (let [[_ method params] (:call-args @admin-console-mock)]
          (t/is (= :ingest-audit-log method))
          (t/is (= 2 (count (:events params))))
          (t/is (= #{"create-project" "create-file"}
                   (into #{} (map :name) (:events params))))
          (t/is (every? uuid? (map :id (:events params)))))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-filters-admin-console-event-names
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (insert-audit-row! (:id prof) "create-organization-attribute")
        (insert-audit-row! (:id prof) "unrelated-event")
        (run-both! {:uri archive-uri})
        (t/is (:called? @admin-console-mock))
        (t/is (:called? @http-mock))
        (let [[_ _ params] (:call-args @admin-console-mock)]
          (t/is (= #{"create-project" "create-organization-attribute"}
                   (into #{} (map :name) (:events params)))))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-skips-admin-console-when-no-matching-names
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "unrelated-event")
        (run-both! {:uri archive-uri})
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-admin-console-only-marks-without-uri
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-consumer-admin-console {})
        (t/is (:called? @admin-console-mock))
        (t/is (false? (:called? @http-mock)))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-admin-console-only-marks-non-allowlisted
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "unrelated-event")
        (th/run-task! :audit-log-consumer-admin-console {})
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (false? (:called? @http-mock)))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-admin-console-error-still-drains-nexus
  ;; Mirror of the nexus-throw test: an admin-console failure aborts
  ;; only the admin-console job; Nexus already drained its own queue
  ;; in its job. The row stays on the admin-console pending.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call
                                       :throw (ex-info "nitrate down" {})}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (th/run-task! :audit-log-consumer-admin-console {})))
        ;; Nexus already drained its queue; the admin-console failure no
        ;; longer blocks it, but the row stays on the admin-console pending.
        (t/is (:called? @admin-console-mock))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 1 (count rows))))
        (t/is (= 1 (pending-count)))))))

(t/deftest consumer-events-uses-nexus-without-admin-console
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock    {:target 'app.http.client/req :return {:status 204}}
                   admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-skips-mark-when-nexus-fails
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 500}}]
        (insert-audit-row! (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 1 (count rows))))))))

(t/deftest consumer-events-both-flags-skips-mark-when-nexus-fails
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 500}}]
        (insert-audit-row! (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})))
        (th/run-task! :audit-log-consumer-admin-console {})
        (t/is (:called? @admin-console-mock))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 1 (count rows))))))))

(t/deftest consumer-events-encodes-db-rows-for-admin-console
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags  #{:nexus :admin-console}
                  cf/config (assoc cf/config
                                   :admin-console-uri "http://ac.example/admin-console/"
                                   :admin-console-shared-key "ac-key"
                                   :nexus-shared-key "nexus-key")]
      (let [admin-console-bodies (atom [])
            shared-keys    {:admin-console "ac-key" :nexus "nexus-key"}
            client         (ig/init-key :app.nitrate/client
                                        {::http/client       (Object.)
                                         ::setup/shared-keys shared-keys})
            ;; Each consumer job is a job-def: the handler closes over the
            ;; cfg it was given at init and takes a nil context, because
            ;; an in-process invocation has no job row to describe.
            nexus-job-def    (ig/init-key :app.loggers.audit.consumer-task/nexus-job-def
                                          {::db/pool           (:app.db/pool th/*system*)
                                           ::setup/shared-keys shared-keys
                                           ::http/client       (:app.http.client/client th/*system*)
                                           ::mtx/metrics       (th/metrics-stub)
                                           :app.nitrate/client client})
            admin-console-job-def  (ig/init-key :app.loggers.audit.consumer-task/admin-console-job-def
                                                {::db/pool           (:app.db/pool th/*system*)
                                                 ::mtx/metrics       (th/metrics-stub)
                                                 :app.nitrate/client client})]
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return
                                (fn [_ req & _]
                                  (when (str/includes? (str (:uri req)) "api/audit-log")
                                    (swap! admin-console-bodies conj (:body req)))
                                  {:status 204})}]
          (let [team-id (uuid/next)
                row     (th/db-insert! :audit-log
                                       {:id         (uuid/next)
                                        :name       "create-project"
                                        :type       "action"
                                        :source     "backend"
                                        :profile-id (:id prof)
                                        :ip-addr    (db/inet "127.0.0.1")
                                        :props      (db/tjson {:team-id team-id})
                                        :context    (db/tjson {})
                                        :tracked-at (ct/now)
                                        :created-at (ct/now)})]
            (insert-pendings! (:id row))
            ;; Admin console first so its body is captured before Nexus
            ;; deletes the last pending and the row.
            ((::jobs/handler admin-console-job-def) nil {})
            ((::jobs/handler nexus-job-def) nil {:uri archive-uri})
            (t/is (= 1 (count @admin-console-bodies)))
            (let [body  (json/decode (first @admin-console-bodies) :key-fn json/read-kebab-key)
                  event (first (:events body))]
              (t/is (= 1 (count (:events body))))
              (t/is (= (str (:id row)) (str (:id event))))
              (t/is (= "create-project" (:name event)))
              (t/is (= "127.0.0.1" (:ip-addr event)))
              (t/is (string? (:created-at event)))
              (t/is (string? (:tracked-at event)))
              (t/is (= (str team-id) (str (get-in event [:props :team-id])))))
            (let [rows (th/db-exec! ["select * from audit_log"])]
              (t/is (= 0 (count rows))))))))))

(t/deftest consumer-nexus-default-uri-appends-api-audit
  ;; Without an explicit :uri param, the job posts to the Nexus base
  ;; URI plus the ingest path, mirroring generate-nitrate-uri.
  (let [prof (th/create-profile* 1 {:is-active true})
        uris (atom [])]
    (with-redefs [cf/flags  #{:nexus}
                  cf/config (assoc cf/config :nexus-uri (u/uri "http://nexus.example"))]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ params & _]
                                        (swap! uris conj (str (:uri params)))
                                        {:status 204})}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-consumer-nexus {})
        (t/is (= ["http://nexus.example/api/audit"] @uris))
        (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))))))

(t/deftest consumer-nexus-job-requires-uri-when-nexus-flag-set
  (with-redefs [cf/flags #{:nexus}
                cf/config (dissoc cf/config :nexus-uri)]
    (let [data (ex-data (try
                          (th/run-task! :audit-log-consumer-nexus {})
                          (catch Throwable cause
                            cause)))]
      (t/is (= :task-not-configured (:code data))))))

(t/deftest consumer-events-enriches-team-name-and-emails
  (let [actor  (th/create-profile* 1 {:is-active true})
        member (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id actor)})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id actor) "create-project"
                           {:team-id   (:id team)
                            :profile-id (:id actor)
                            :user-id   (:id member)
                            :member-id (:id member)
                            ;; Producers store this as a string UUID.
                            :user-who-send-invitation (str (:id actor))})
        (th/run-task! :audit-log-consumer-admin-console {})
        (t/is (:called? @admin-console-mock))
        (let [props (admin-console-event-props admin-console-mock)]
          (t/is (= (:name team) (:team-name props)))
          (t/is (= (:email actor) (:profile-email props)))
          (t/is (= (:email member) (:user-email props)))
          (t/is (= (:email member) (:member-email props)))
          (t/is (= (:email actor) (:user-who-send-invitation-email props))))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-enriches-soft-deleted-team-and-profile
  (let [actor (th/create-profile* 1 {:is-active true})
        other (th/create-profile* 2 {:is-active true})
        team  (th/create-team* 1 {:profile-id (:id actor)})]
    (th/db-update! :team {:deleted-at (ct/now)} {:id (:id team)})
    (th/db-update! :profile {:deleted-at (ct/now)} {:id (:id other)})
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id actor) "create-project"
                           {:team-id    (:id team)
                            :profile-id (:id other)})
        (th/run-task! :audit-log-consumer-admin-console {})
        (let [props (admin-console-event-props admin-console-mock)]
          (t/is (= (:name team) (:team-name props)))
          (t/is (= (:email other) (:profile-email props))))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-does-not-overwrite-existing-enrich-props
  (let [actor (th/create-profile* 1 {:is-active true})
        team  (th/create-team* 1 {:profile-id (:id actor)})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id actor) "create-project"
                           {:team-id       (:id team)
                            :team-name     "already-set-name"
                            :profile-id    (:id actor)
                            :profile-email "already-set@example.com"
                            :user-id       (:id actor)
                            :user-email    "already-user@example.com"
                            :member-id     (:id actor)
                            :member-email  "already-member@example.com"
                            :user-who-send-invitation (str (:id actor))
                            :user-who-send-invitation-email "already-inviter@example.com"})
        (th/run-task! :audit-log-consumer-admin-console {})
        (let [props (admin-console-event-props admin-console-mock)]
          (t/is (= "already-set-name" (:team-name props)))
          (t/is (= "already-set@example.com" (:profile-email props)))
          (t/is (= "already-user@example.com" (:user-email props)))
          (t/is (= "already-member@example.com" (:member-email props)))
          (t/is (= "already-inviter@example.com"
                   (:user-who-send-invitation-email props))))))))

(t/deftest consumer-events-enrich-skips-missing-and-non-uuid-ids
  (let [actor (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id actor) "create-project"
                           {:team-id    (uuid/next)
                            :profile-id (uuid/next)
                            :user-id    "not-a-uuid"
                            :member-id  42})
        (th/run-task! :audit-log-consumer-admin-console {})
        (t/is (:called? @admin-console-mock))
        (let [props (admin-console-event-props admin-console-mock)]
          (t/is (nil? (:team-name props)))
          (t/is (nil? (:profile-email props)))
          (t/is (nil? (:user-email props)))
          (t/is (nil? (:member-email props)))
          (t/is (= "not-a-uuid" (:user-id props)))
          (t/is (= 42 (:member-id props))))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-events-forwards-export-job-not-legacy-export
  (let [actor (th/create-profile* 1 {:is-active true})
        team  (th/create-team* 1 {:profile-id (:id actor)})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id actor) "create-export-binfile-job"
                           {:team-id (:id team)
                            :job-id (uuid/next)
                            :files 1
                            :export-type "detach-libraries"})
        (insert-audit-row! (:id actor) "export-binfile"
                           {:file-id (uuid/next)})
        (th/run-task! :audit-log-consumer-admin-console {})
        (let [[_ _ params] (:call-args @admin-console-mock)
              events       (:events params)
              props        (:props (first events))]
          (t/is (= ["create-export-binfile-job"] (mapv :name events)))
          (t/is (= (:id team) (:team-id props)))
          (t/is (= (:name team) (:team-name props))))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

(t/deftest consumer-params-accept-empty-cron-props
  ;; Cron always submits (or props {}) — system deps live on the
  ;; job-def cfg, not in params. Requiring them here blocks every tick.
  ;; Both schemas are closed: a stale manual invocation must fail
  ;; validation loudly instead of skipping silently.
  (let [nexus-valid?   (sm/validator consumer/schema:audit-log-consumer-nexus-params)
        admin-console-valid? (sm/validator consumer/schema:audit-log-consumer-admin-console-params)]
    (t/is (true? (nexus-valid? {})))
    (t/is (false? (nexus-valid? {:enabled true})))
    (t/is (true? (nexus-valid? {:uri (u/uri archive-uri)})))
    (t/is (true? (admin-console-valid? {})))
    (t/is (false? (admin-console-valid? {:enabled true})))
    (t/is (false? (admin-console-valid? {:uri (u/uri archive-uri)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; METRICS SMOKE TEST
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The job-def cfg of these tests carries an isolated metrics registry
;; (pattern from `jobs-metrics-test`), so assertions read exact values
;; instead of the shared test-system registry. `th/run-task!` would use
;; the latter and its counts would leak across tests.
(def ^:private consumer-metric-ids
  [:audit-consumer-events :audit-consumer-discarded])

(defn- make-consumer-metrics
  []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics consumer-metric-ids)}))

(defn- invoke-nexus!
  "Invoke the nexus consumer handler in-process with an isolated
  metrics registry. The handler takes a nil context because there is
  no job row to describe (same as `th/run-task!`)."
  [metrics params]
  (let [job-def (ig/init-key :app.loggers.audit.consumer-task/nexus-job-def
                             {::db/pool           (:app.db/pool th/*system*)
                              ::setup/shared-keys {:admin-console "ac-key" :nexus "nexus-key"}
                              ::http/client       (:app.http.client/client th/*system*)
                              ::mtx/metrics       metrics})]
    ((::jobs/handler job-def) nil params)))

(defn- invoke-admin-console!
  "Invoke the admin-console consumer handler in-process with an
  isolated metrics registry. The admin-console client is the (nil in
  tests) system one: tests mock `app.nitrate/call`, so it is never
  dereferenced."
  [metrics]
  (let [job-def (ig/init-key :app.loggers.audit.consumer-task/admin-console-job-def
                             {::db/pool           (:app.db/pool th/*system*)
                              :app.nitrate/client (:app.nitrate/client th/*system*)
                              ::mtx/metrics       metrics})]
    ((::jobs/handler job-def) nil {})))

(defn- invoke-both!
  "Invoke both consumer handlers sharing one isolated metrics
  registry, Nexus first (the historical single-run order)."
  [metrics params]
  (invoke-nexus! metrics params)
  (invoke-admin-console! metrics))

(defn- counter-value
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(t/deftest consumer-records-metrics-on-success
  ;; Smoke test: the run records its domain metrics into the wired
  ;; registry (isolated per test, pattern from `jobs-metrics-test`).
  ;; Logging itself is not asserted: `dbg` summaries run below the
  ;; test log level and failure `warn`s only repeat the outcome the
  ;; metrics already prove.
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (insert-audit-row! (:id prof) "unrelated-event")
        (invoke-both! metrics {:uri archive-uri})
        (t/is (= 2.0 (counter-value metrics :audit-consumer-events ["nexus" "archived"])))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["admin-console" "archived"])))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["admin-console" "skipped"])))
        (t/is (= 0 (pending-count)))
        (let [rows (th/db-exec! ["select * from audit_log"])]
          (t/is (= 0 (count rows))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PENDING-QUEUE TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- full-event
  "A complete event for submit*, which (unlike submit) assumes every
  required field is filled."
  [profile-id]
  {:name "create-file" :type "action" :source "backend"
   :profile-id profile-id :ip-addr "127.0.0.1"
   :props {} :context {}
   :created-at (ct/now) :tracked-at (ct/now)})

(t/deftest consumer-submit-creates-pendings-for-enabled-consumers
  ;; The producer hook: a row is born pending for the enabled
  ;; consumers, in the same transaction as the row itself. Goes
  ;; through submit* because the suite stubs submit itself (see
  ;; helpers/init-config).
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :nexus :admin-console}]
      (audit/submit* {::db/pool (:app.db/pool th/*system*)}
                     (full-event (:id prof)))
      (t/is (= 1 (count (th/db-exec! ["select * from audit_log"]))))
      (t/is (= 2 (pending-count))))))

(t/deftest consumer-submit-single-flag-creates-one-pending
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :nexus}]
      (audit/submit* {::db/pool (:app.db/pool th/*system*)}
                     (full-event (:id prof)))
      (t/is (= 1 (count (th/db-exec! ["select * from audit_log"]))))
      (t/is (= 1 (pending-count))))))

(t/deftest consumer-submit-rollback-keeps-no-pendings
  ;; A rollback takes the row and its queue together: a row never
  ;; outlives its pendings, and no phantom pending pins nothing.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :nexus :admin-console}]
      (db/tx-run! {::db/pool    (:app.db/pool th/*system*)
                   ::db/rollback true}
                  (fn [cfg]
                    (audit/submit* cfg (full-event (:id prof)))
                    (t/is (= 1 (count (db/exec! cfg ["select * from audit_log"]))))
                    (t/is (= 2 (count (db/exec! cfg ["select * from audit_log_pending"]))))))
      (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))
      (t/is (= 0 (pending-count))))))

(t/deftest consumer-keeps-row-until-last-consumer-done
  ;; Nexus refuses the chunk: the nexus job aborts (failed count
  ;; recorded, isolated registry) while the admin-console job still
  ;; drains its own queue. The row and the nexus pending stay; once
  ;; nexus accepts, everything is gone.
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (insert-audit-row! (:id prof) "create-project")
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 500}}]
        (let [err (try
                    (invoke-nexus! metrics {:uri archive-uri})
                    nil
                    (catch clojure.lang.ExceptionInfo cause cause))]
          (t/is (= :consumer-chunk-refused (:code (ex-data err)))))
        (invoke-admin-console! metrics)
        (t/is (:called? @admin-console-mock))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["nexus" "failed"])))
        (t/is (= 1 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 1 (pending-count))))
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (run-both! {:uri archive-uri})
        (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 0 (pending-count)))))))

(t/deftest consumer-sweeps-orphan-rows
  ;; A row with no pendings (old archived leftover, or born while no
  ;; consumer was on) is deleted without any send.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        ;; Raw insert, no pendings on purpose.
        (th/db-insert! :audit-log
                       {:id         (uuid/next)
                        :name       "create-project"
                        :type       "action"
                        :source     "backend"
                        :profile-id (:id prof)
                        :ip-addr    (db/inet "127.0.0.1")
                        :props      (db/tjson {})
                        :context    (db/tjson {})
                        :tracked-at (ct/now)
                        :created-at (ct/now)})
        (run-both! {:uri archive-uri})
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (false? (:called? @http-mock)))
        (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))))))

(t/deftest consumer-nexus-transport-failure-records-failed
  ;; A transport exception (timeout, DNS, refused) counts the events
  ;; as failed and aborts the nexus job: the chunk rolls back and
  ;; the rows stay for the next tick.
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :throw (ex-info "timeout" {})}]
        (insert-audit-row! (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (invoke-nexus! metrics {:uri archive-uri})))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["nexus" "failed"])))
        (t/is (= 1 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 1 (pending-count)))))))

(t/deftest consumer-sends-chunks-in-chronological-order
  ;; Nexus numbers by arrival for order-sensitive consumers, so
  ;; multi-chunk drains must arrive oldest-first no matter how the
  ;; queue is scanned.
  (let [prof   (th/create-profile* 1 {:is-active true})
        base   (ct/now)
        bodies (atom [])]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ params & _]
                                        (swap! bodies conj (:body params))
                                        {:status 204})}]
        (doseq [i (range 130)]
          (let [row (insert-audit-row! (:id prof) (str "event-" i))]
            (th/db-update! :audit-log
                           {:created-at (.plusSeconds ^java.time.Instant base (long i))}
                           {:id (:id row)})))
        (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})
        (let [received (mapcat #(:events (transit/decode %)) @bodies)
              times    (map :created-at received)]
          (t/is (= 130 (count received)))
          (t/is (= (sort times) times))
          (t/is (= 0 (count (th/db-exec! ["select * from audit_log"])))))))))

(t/deftest consumer-reaps-disabled-consumer-pendings
  ;; R3 reap by its real path: an admin-console pending born while
  ;; admin-console was on is dropped once it is off; nexus still
  ;; archives its queue and the row is gone with nothing stuck. The
  ;; dropped pending counts as discarded (isolated registry).
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock    {:target 'app.http.client/req :return {:status 204}}
                   admin-console-mock {:target 'app.nitrate/call :return nil}]
        (let [row (insert-audit-row! (:id prof) "create-project")]
          ;; Admin console was on at insert time: both pendings on purpose.
          (th/db-exec! ["INSERT INTO audit_log_pending (audit_log_id, consumer)
                         VALUES (?::uuid, 2)
                              ON CONFLICT DO NOTHING"
                        (:id row)])
          (t/is (= 2 (pending-count)))
          (invoke-nexus! metrics {:uri archive-uri})
          (t/is (:called? @http-mock))
          (t/is (false? (:called? @admin-console-mock)))
          (t/is (= 1.0 (counter-value metrics :audit-consumer-discarded ["reaped"])))
          (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))
          (t/is (= 0 (pending-count))))))))

(t/deftest consumer-sweeps-rows-born-with-no-consumer-on
  ;; Accepted R3 consequence by its real path (not a hand-made
  ;; orphan): ingested with no send flag on, the row is swept
  ;; unarchived once the task runs, with zero sends.
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:audit-log}]
      (audit/submit* {::db/pool (:app.db/pool th/*system*)}
                     (full-event (:id prof)))
      (t/is (= 1 (count (th/db-exec! ["select * from audit_log"]))))
      (t/is (= 0 (pending-count))))
    (with-redefs [cf/flags #{:audit-log :nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (invoke-nexus! metrics {:uri archive-uri})
        (t/is (false? (:called? @http-mock)))
        (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 0 (pending-count)))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-discarded ["swept"])))
        (t/is (= 0.0 (counter-value metrics :audit-consumer-events ["nexus" "archived"])))))))

(t/deftest consumer-nexus-throw-still-drains-admin-console
  ;; Consumer independence, now structural: a Nexus transport throw
  ;; aborts only the nexus job, and the admin-console job still drains
  ;; its own queue. Nexus keeps its pending (so the row stays); the
  ;; admin console completes its own queue.
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req
                                 :throw (ex-info "timeout" {})}]
        (insert-audit-row! (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (invoke-nexus! metrics {:uri archive-uri})))
        (invoke-admin-console! metrics)
        (t/is (:called? @admin-console-mock))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["nexus" "failed"])))
        (t/is (= 1 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 1 (pending-count)))))))

(t/deftest consumer-reap-then-sweeps-without-sends
  ;; End to end of the reap path: a row born with only an
  ;; admin-console pending is reaped once :admin-console goes off,
  ;; then swept with zero sends on either destination.
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (insert-audit-row! (:id prof) "create-project")
      (t/is (= 1 (pending-count))))
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock    {:target 'app.http.client/req :return {:status 204}}
                   admin-console-mock {:target 'app.nitrate/call :return nil}]
        (th/run-task! :audit-log-consumer-nexus {:uri archive-uri})
        (t/is (false? (:called? @http-mock)))
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 0 (pending-count)))))))

(t/deftest consumer-fully-filtered-admin-console-chunk-counts-skipped
  ;; An admin-console chunk with every event outside the allowlist
  ;; never calls ingest, but still counts its rows as skipped and
  ;; completes its queue.
  (let [prof    (th/create-profile* 1 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "unrelated-event")
        (invoke-both! metrics {:uri archive-uri})
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["admin-console" "skipped"])))
        (t/is (= 0 (count (th/db-exec! ["select * from audit_log"]))))
        (t/is (= 0 (pending-count)))))))
