;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-audit-consumers-test
  "The consumers of the audit log buffer: their cursors, the snapshot
  cut that keeps an open transaction from being skipped, the transports
  and the GC of the buffer."
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
   [app.loggers.audit.consumers :as consumer]
   [app.loggers.audit.consumers.admin-console :as consumer.admin-console]
   [app.loggers.audit.consumers.nexus :as consumer.nexus]
   [app.loggers.audit.gc :as gc]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [app.setup :as-alias setup]
   [app.tasks.team-storage :as team-storage]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]])
  (:import
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.Gauge
   io.prometheus.client.Gauge$Child))

(t/use-fixtures :once th/state-init)

(def ^:private archive-uri "http://nexus.example/archive")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FIXTURES AND HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-cursors
  "Every consumer starts at zero: the test database reset deletes the
  cursor rows, and the drain would otherwise create them at the end of
  the buffer (the 'a new consumer sees no history' rule)."
  [next]
  (doseq [consumer-id [consumer/consumer-nexus
                       consumer/consumer-admin-console
                       consumer/consumer-team-storage]]
    (th/db-exec! ["INSERT INTO audit_log_consumer_cursor (consumer, xid, seq)
                   VALUES (?::smallint, '0'::xid8, 0)"
                  consumer-id]))
  (next))

(t/use-fixtures :each th/database-reset seed-cursors)

(defn- cursor-of
  [consumer-id]
  (th/db-exec-one! ["SELECT xid::text AS xid, seq
                       FROM audit_log_consumer_cursor
                      WHERE consumer = ?::smallint"
                    consumer-id]))

(defn- unread-count
  "Rows after the cursor of a consumer, whatever their visibility."
  [consumer-id]
  (let [{:keys [xid seq]} (cursor-of consumer-id)]
    (:cnt (th/db-exec-one!
           ["SELECT count(*) AS cnt
               FROM audit_log a
              WHERE (a.xid, a.seq) > (?::xid8, ?::bigint)"
            xid seq]))))

(defn- insert-audit-row
  ([profile-id name]
   (insert-audit-row profile-id name {:team-id (uuid/next)}))
  ([profile-id name props]
   (insert-audit-row profile-id name props "backend"))
  ([profile-id name props source]
   (th/db-insert! :audit-log
                  {:id         (uuid/next)
                   :name       name
                   :type       "action"
                   :source     source
                   :profile-id profile-id
                   :ip-addr    (db/inet "127.0.0.1")
                   :props      (db/tjson props)
                   :context    (db/tjson {})
                   :tracked-at (ct/now)
                   :created-at (ct/now)})))

(defn- run-nexus
  [params]
  (th/run-task! :audit-log-consumer-nexus params))

(defn- run-admin-console
  []
  (th/run-task! :audit-log-consumer-admin-console {}))

(defn- run-gc
  []
  (th/run-task! :audit-log-gc {}))

(defn- admin-console-event
  [admin-console-mock]
  (let [[_ _ params] (:call-args @admin-console-mock)]
    (first (:events params))))

(defn- admin-console-event-props
  [admin-console-mock]
  (:props (admin-console-event admin-console-mock)))

(defn- insert-rows-in-one-transaction
  "Insert `n` rows from a raw connection, in a transaction the caller
  commits or rolls back. Returns the connection."
  [conn prefix n]
  (db/exec! conn ["BEGIN"])
  (db/exec! conn ["INSERT INTO audit_log (name, type, profile_id, created_at, tracked_at,
                                          source, ip_addr, props, context)
                   SELECT ? || g, 'action', ?, now(), now(),
                          'backend', '127.0.0.1', ?, ?
                     FROM generate_series(1, ?) AS g"
                  prefix uuid/zero (db/tjson {}) (db/tjson {}) n])
  conn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NEXUS: THE WHOLE STREAM FROM ITS CURSOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest consumer-nexus-drains-from-its-cursor
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "create-file")
        (t/is (= 2 (unread-count consumer/consumer-nexus)))
        (run-nexus {:uri archive-uri})
        (t/is (:called? @http-mock))
        (t/is (= 1 (:call-count @http-mock)))
        (let [events (-> @http-mock :call-args second :body transit/decode :events)]
          (t/is (= ["create-project" "create-file"] (mapv :name events))))
        ;; The cursor advanced; the rows stay until the GC removes them.
        (t/is (= 0 (unread-count consumer/consumer-nexus)))
        (t/is (= 2 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
        ;; Nobody else moved: each consumer has its own position.
        (t/is (= 2 (unread-count consumer/consumer-admin-console)))
        (t/is (= 2 (unread-count consumer/consumer-team-storage)))))))

(t/deftest consumer-nexus-sends-in-insertion-order
  (let [prof  (th/create-profile* 2 {:is-active true})
        names (mapv #(str "event-" %) (range 130))]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (doseq [name names]
          (insert-audit-row (:id prof) name))
        (run-nexus {:uri archive-uri})
        (let [bodies  (map #(transit/decode (:body (second %))) (:call-args-list @http-mock))
              events  (mapcat :events bodies)]
          (t/is (= 2 (count bodies)))
          (t/is (= names (mapv :name events)))
          (t/is (= 0 (unread-count consumer/consumer-nexus))))))))

(t/deftest consumer-nexus-default-uri-appends-api-audit
  (let [prof (th/create-profile* 3 {:is-active true})
        uris (atom [])]
    (with-redefs [cf/flags  #{:nexus}
                  cf/config (assoc cf/config :nexus-uri (u/uri "http://nexus.example"))]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ params & _]
                                        (swap! uris conj (str (:uri params)))
                                        {:status 204})}]
        (insert-audit-row (:id prof) "create-project")
        (run-nexus {})
        (t/is (= ["http://nexus.example/api/audit"] @uris))))))

(t/deftest consumer-nexus-job-requires-uri-when-nexus-flag-set
  (with-redefs [cf/flags #{:nexus}
                cf/config (dissoc cf/config :nexus-uri)]
    (let [data (ex-data (try
                          (run-nexus {})
                          (catch Throwable cause
                            cause)))]
      (t/is (= :task-not-configured (:code data))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ADMIN CONSOLE: ITS ALLOWLIST, ITS OWN FILTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest consumer-admin-console-filters-allowlist-and-shadows
  (let [prof (th/create-profile* 10 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "unrelated-event")
        (insert-audit-row (:id prof) "create-file" {:team-id (uuid/next)} "telemetry:backend")
        (insert-audit-row (:id prof) "create-file" {:team-id (uuid/next)} "backend")
        (run-admin-console)
        (t/is (:called? @admin-console-mock))
        (t/is (false? (:called? @http-mock)))
        (let [[_ _ params] (:call-args @admin-console-mock)]
          (t/is (= ["create-project" "create-file"]
                   (mapv :name (:events params)))))
        ;; Every row was passed over: the skipped ones and the ones sent.
        (t/is (= 0 (unread-count consumer/consumer-admin-console)))))))

(t/deftest consumer-admin-console-advances-over-non-matching-rows
  ;; A selective consumer that finds nothing must still move: otherwise
  ;; the GC would wait for it forever and the buffer would grow.
  (let [prof (th/create-profile* 11 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (dotimes [i 200]
          (insert-audit-row (:id prof) (str "unrelated-" i)))
        (run-admin-console)
        (t/is (false? (:called? @admin-console-mock)))
        (t/is (= 0 (unread-count consumer/consumer-admin-console)))))))

(t/deftest consumer-admin-console-error-still-lets-nexus-drain
  (let [prof (th/create-profile* 12 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call
                                       :throw (ex-info "nitrate down" {})}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (run-nexus {:uri archive-uri})
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (run-admin-console)))
        (t/is (= 0 (unread-count consumer/consumer-nexus)))
        (t/is (= 1 (unread-count consumer/consumer-admin-console)))))))

(t/deftest consumer-admin-console-encodes-db-rows-for-admin-console
  (let [prof (th/create-profile* 13 {:is-active true})]
    (with-redefs [cf/flags  #{:admin-console}
                  cf/config (assoc cf/config
                                   :admin-console-uri "http://ac.example/admin-console/"
                                   :admin-console-shared-key "ac-key"
                                   :nexus-shared-key "nexus-key")]
      (let [admin-console-bodies (atom [])
            shared-keys    {:admin-console "ac-key" :nexus "nexus-key"}
            client         (ig/init-key :app.nitrate/client
                                        {::http/client       (Object.)
                                         ::setup/shared-keys shared-keys})
            admin-console-job-def (ig/init-key :app.loggers.audit.consumers.admin-console/job-def
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
                row     (insert-audit-row (:id prof) "create-project"
                                          {:team-id team-id})]
            ((::jobs/handler admin-console-job-def) nil {})
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
            (t/is (= 0 (unread-count consumer/consumer-admin-console)))))))))

(t/deftest consumer-admin-console-enriches-team-name-and-emails
  (let [actor  (th/create-profile* 14 {:is-active true})
        member (th/create-profile* 15 {:is-active true})
        team   (th/create-team* 14 {:profile-id (:id actor)})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-project"
                          {:team-id   (:id team)
                           :profile-id (:id actor)
                           :user-id   (:id member)
                           :member-id (:id member)
                           ;; Producers store this as a string UUID.
                           :user-who-send-invitation (str (:id actor))})
        (run-admin-console)
        (let [event (admin-console-event admin-console-mock)
              props (:props event)]
          (t/is (= (:name team) (:team-name props)))
          (t/is (= (:email actor) (:profile-email props)))
          (t/is (= (:email member) (:user-email props)))
          (t/is (= (:email member) (:member-email props)))
          (t/is (= (:email actor) (:user-who-send-invitation-email props)))
          ;; Top-level actor fields (event :profile-id), distinct from props.
          (t/is (= (:email actor) (:profile-email event)))
          (t/is (= (:fullname actor) (:profile-name event))))))))

(t/deftest consumer-admin-console-enriches-soft-deleted-team-and-profile
  (let [actor (th/create-profile* 16 {:is-active true})
        other (th/create-profile* 17 {:is-active true})
        team  (th/create-team* 16 {:profile-id (:id actor)})]
    (th/db-update! :team {:deleted-at (ct/now)} {:id (:id team)})
    (th/db-update! :profile {:deleted-at (ct/now)} {:id (:id other)})
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-project"
                          {:team-id    (:id team)
                           :profile-id (:id other)})
        (run-admin-console)
        (let [event (admin-console-event admin-console-mock)
              props (:props event)]
          (t/is (= (:name team) (:team-name props)))
          (t/is (= (:email other) (:profile-email props)))
          (t/is (= (:email actor) (:profile-email event)))
          (t/is (= (:fullname actor) (:profile-name event))))))))

(t/deftest consumer-admin-console-enriches-soft-deleted-actor-profile
  (let [actor (th/create-profile* 70 {:is-active true})
        team  (th/create-team* 70 {:profile-id (:id actor)})]
    (th/db-update! :profile {:deleted-at (ct/now)} {:id (:id actor)})
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-project"
                          {:team-id (:id team)})
        (run-admin-console)
        (let [event (admin-console-event admin-console-mock)]
          (t/is (= (:email actor) (:profile-email event)))
          (t/is (= (:fullname actor) (:profile-name event))))))))

(t/deftest consumer-admin-console-omits-actor-profile-when-no-usable-id
  (let [actor (th/create-profile* 71 {:is-active true})
        team  (th/create-team* 71 {:profile-id (:id actor)})
        missing (uuid/next)]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row uuid/zero "create-project" {:team-id (:id team)})
        (insert-audit-row missing "create-team" {:team-id (:id team)})
        (run-admin-console)
        (t/is (:called? @admin-console-mock))
        (let [[_ _ params] (:call-args @admin-console-mock)
              events       (:events params)
              by-name      (into {} (map (juxt :name identity) events))
              zero-evt     (get by-name "create-project")
              missing-evt  (get by-name "create-team")]
          (t/is (not (contains? zero-evt :profile-email)))
          (t/is (not (contains? zero-evt :profile-name)))
          (t/is (not (contains? missing-evt :profile-email)))
          (t/is (not (contains? missing-evt :profile-name))))))))

(t/deftest consumer-admin-console-does-not-overwrite-existing-enrich-props
  (let [actor (th/create-profile* 18 {:is-active true})
        team  (th/create-team* 18 {:profile-id (:id actor)})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-project"
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
        (run-admin-console)
        (let [props (admin-console-event-props admin-console-mock)]
          (t/is (= "already-set-name" (:team-name props)))
          (t/is (= "already-set@example.com" (:profile-email props)))
          (t/is (= "already-user@example.com" (:user-email props)))
          (t/is (= "already-member@example.com" (:member-email props)))
          (t/is (= "already-inviter@example.com"
                   (:user-who-send-invitation-email props))))))))

(t/deftest consumer-admin-console-does-not-overwrite-existing-actor-profile-fields
  ;; Top-level :profile-email / :profile-name are not stored on the row;
  ;; exercise fill-only via the enrich helper directly.
  (let [actor (th/create-profile* 72 {:is-active true})
        event {:profile-id    (:id actor)
               :profile-email "preset@example.com"
               :profile-name  "Preset Name"
               :props         {}}]
    (db/tx-run! th/*system*
                (fn [cfg]
                  (let [enriched (first (#'consumer.admin-console/enrich-events-for-admin-console
                                         cfg [event]))]
                    (t/is (= "preset@example.com" (:profile-email enriched)))
                    (t/is (= "Preset Name" (:profile-name enriched))))))))

(t/deftest consumer-admin-console-fills-missing-actor-profile-field-independently
  ;; Each top-level key fills on its own; guards an or→and regression in
  ;; collect-actor-ids-needing-profile.
  (let [actor (th/create-profile* 73 {:is-active true})]
    (db/tx-run! th/*system*
                (fn [cfg]
                  (let [email-only (first (#'consumer.admin-console/enrich-events-for-admin-console
                                           cfg [{:profile-id    (:id actor)
                                                 :profile-email "preset@example.com"
                                                 :props         {}}]))
                        name-only  (first (#'consumer.admin-console/enrich-events-for-admin-console
                                           cfg [{:profile-id   (:id actor)
                                                 :profile-name "Preset Name"
                                                 :props        {}}]))]
                    (t/is (= "preset@example.com" (:profile-email email-only)))
                    (t/is (= (:fullname actor) (:profile-name email-only)))
                    (t/is (= (:email actor) (:profile-email name-only)))
                    (t/is (= "Preset Name" (:profile-name name-only))))))))

(t/deftest consumer-admin-console-omits-empty-actor-profile-name
  ;; profile.fullname is NOT NULL DEFAULT ''; empty must not become
  ;; :profile-name "".
  (let [actor (th/create-profile* 74 {:is-active true})
        team  (th/create-team* 74 {:profile-id (:id actor)})]
    (th/db-update! :profile {:fullname ""} {:id (:id actor)})
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-project" {:team-id (:id team)})
        (run-admin-console)
        (let [event (admin-console-event admin-console-mock)]
          (t/is (= (:email actor) (:profile-email event)))
          (t/is (not (contains? event :profile-name))))))))

(t/deftest consumer-admin-console-enrich-skips-missing-and-non-uuid-ids
  (let [actor (th/create-profile* 19 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-project"
                          {:team-id    (uuid/next)
                           :profile-id (uuid/next)
                           :user-id    "not-a-uuid"
                           :member-id  42})
        (run-admin-console)
        (let [props (admin-console-event-props admin-console-mock)]
          (t/is (nil? (:team-name props)))
          (t/is (nil? (:profile-email props)))
          (t/is (nil? (:user-email props)))
          (t/is (nil? (:member-email props)))
          (t/is (= "not-a-uuid" (:user-id props)))
          (t/is (= 42 (:member-id props))))))))

(t/deftest consumer-admin-console-forwards-export-job-not-legacy-export
  (let [actor (th/create-profile* 20 {:is-active true})
        team  (th/create-team* 20 {:profile-id (:id actor)})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id actor) "create-export-binfile-job"
                          {:team-id (:id team)
                           :job-id (uuid/next)
                           :files 1
                           :export-type "detach-libraries"})
        (insert-audit-row (:id actor) "export-binfile"
                          {:file-id (uuid/next)})
        (run-admin-console)
        (let [[_ _ params] (:call-args @admin-console-mock)
              events       (:events params)
              props        (:props (first events))]
          (t/is (= ["create-export-binfile-job"] (mapv :name events)))
          (t/is (= (:id team) (:team-id props)))
          (t/is (= (:name team) (:team-name props))))))))

(t/deftest consumer-params-accept-empty-cron-props
  ;; Cron always submits (or props {}) — system deps live on the
  ;; job-def cfg, not in params. Requiring them here blocks every tick.
  ;; The schemas are closed: a stale manual invocation must fail
  ;; validation loudly instead of skipping silently.
  (let [nexus-valid?          (sm/validator consumer.nexus/schema:audit-log-consumer-nexus-params)
        admin-console-valid?  (sm/validator consumer.admin-console/schema:audit-log-consumer-admin-console-params)
        team-storage-valid?   (sm/validator team-storage/schema:audit-log-consumer-team-storage-params)]
    (t/is (true? (nexus-valid? {})))
    (t/is (false? (nexus-valid? {:enabled true})))
    (t/is (true? (nexus-valid? {:uri (u/uri archive-uri)})))
    (t/is (true? (admin-console-valid? {})))
    (t/is (false? (admin-console-valid? {:enabled true})))
    (t/is (false? (admin-console-valid? {:uri (u/uri archive-uri)})))
    (t/is (true? (team-storage-valid? {})))
    (t/is (false? (team-storage-valid? {:enabled true})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE SNAPSHOT CUT: A ROW OF AN OPEN TRANSACTION IS NEVER SKIPPED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest cursor-does-not-pass-an-open-transaction
  (let [prof (th/create-profile* 30 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (with-open [conn (db/open th/*system*)]
          ;; A inserts and keeps the transaction open.
          (db/exec! conn ["BEGIN"])
          (db/exec! conn ["INSERT INTO audit_log (name, type, profile_id, created_at, tracked_at,
                                                  source, ip_addr, props, context)
                           VALUES ('tx-A', 'action', ?, now(), now(),
                                   'backend', '127.0.0.1', ?, ?)"
                          (:id prof) (db/tjson {}) (db/tjson {})])
          ;; B inserts and commits after A, with a higher xid.
          (insert-audit-row (:id prof) "tx-B")
          (run-nexus {:uri archive-uri})
          ;; A is the oldest live transaction: the cut leaves out B too.
          (t/is (false? (:called? @http-mock)))
          (t/is (= 0 (:seq (cursor-of consumer/consumer-nexus))))
          (db/exec! conn ["COMMIT"]))
        ;; A is committed: both rows are visible, in xid order.
        (run-nexus {:uri archive-uri})
        (let [events (-> @http-mock :call-args second :body transit/decode :events)]
          (t/is (= ["tx-A" "tx-B"] (mapv :name events))))
        (t/is (= 0 (unread-count consumer/consumer-nexus)))))))

(t/deftest cursor-processes-a-large-transaction-in-chunks-without-loss
  (let [prof  (th/create-profile* 31 {:is-active true})
        names (mapv #(str "lote-" %) (range 1 301))]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (with-open [conn (db/open th/*system*)]
          (insert-rows-in-one-transaction conn "lote-" 300)
          ;; While the transaction is open nothing is visible.
          (run-nexus {:uri archive-uri})
          (t/is (false? (:called? @http-mock)))
          (t/is (= 0 (:seq (cursor-of consumer/consumer-nexus))))
          (db/exec! conn ["COMMIT"]))
        ;; After the commit the 300 rows are read in 128-row chunks,
        ;; and the seq within the transaction keeps their order.
        (run-nexus {:uri archive-uri})
        (let [bodies (map #(transit/decode (:body (second %)))
                          (:call-args-list @http-mock))
              events (mapcat :events bodies)]
          (t/is (= [128 128 44] (mapv #(count (:events %)) bodies)))
          (t/is (= names (mapv :name events))))
        (t/is (= 0 (unread-count consumer/consumer-nexus)))))))

(t/deftest cursor-lock-serializes-two-runs-of-the-same-consumer
  (let [prof (th/create-profile* 32 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (with-open [conn (db/open th/*system*)]
          (db/exec! conn ["BEGIN"])
          (db/exec! conn ["SELECT consumer FROM audit_log_consumer_cursor
                            WHERE consumer = ?::smallint FOR UPDATE"
                          consumer/consumer-nexus])
          (let [system th/*system*
                job    (future (binding [th/*system* system]
                                 (run-nexus {:uri archive-uri})))]
            (Thread/sleep 500)
            ;; The run is blocked on the cursor: it never sent.
            (t/is (false? (:called? @http-mock)))
            (db/exec! conn ["COMMIT"])
            (t/is (not= :timeout (deref job 10000 :timeout))))
          (t/is (:called? @http-mock))
          (t/is (= 0 (unread-count consumer/consumer-nexus))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; METRICS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The job-def cfg of these tests carries an isolated metrics registry
;; (pattern from `jobs-metrics-test`), so assertions read exact values
;; instead of the shared test-system registry. `th/run-task!` would use
;; the latter and its counts would leak across tests.
(def ^:private consumer-metric-ids
  [:audit-consumer-events :audit-consumer-discarded :audit-consumer-lag])

(defn- make-consumer-metrics
  []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics consumer-metric-ids)}))

(defn- invoke-nexus
  "Invoke the nexus consumer handler in-process with an isolated
  metrics registry. The handler takes a nil context because there is
  no job row to describe (same as `th/run-task!`)."
  [metrics params]
  (let [job-def (ig/init-key :app.loggers.audit.consumers.nexus/job-def
                             {::db/pool           (:app.db/pool th/*system*)
                              ::setup/shared-keys {:admin-console "ac-key" :nexus "nexus-key"}
                              ::http/client       (:app.http.client/client th/*system*)
                              ::mtx/metrics       metrics})]
    ((::jobs/handler job-def) nil params)))

(defn- invoke-admin-console
  "Invoke the admin-console consumer handler in-process with an
  isolated metrics registry."
  [metrics]
  (let [job-def (ig/init-key :app.loggers.audit.consumers.admin-console/job-def
                             {::db/pool           (:app.db/pool th/*system*)
                              :app.nitrate/client (:app.nitrate/client th/*system*)
                              ::mtx/metrics       metrics})]
    ((::jobs/handler job-def) nil {})))

(defn- invoke-both
  "Invoke both consumer handlers sharing one isolated metrics
  registry, Nexus first (the historical single-run order)."
  [metrics params]
  (invoke-nexus metrics params)
  (invoke-admin-console metrics))

(defn- counter-value
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(defn- gauge-value
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Gauge instance (into-array String labels))]
    (.get ^Gauge$Child child)))

(t/deftest consumer-records-metrics-on-success
  (let [prof    (th/create-profile* 40 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "unrelated-event")
        (invoke-both metrics {:uri archive-uri})
        (t/is (= 2.0 (counter-value metrics :audit-consumer-events ["nexus" "processed"])))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["admin-console" "processed"])))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["admin-console" "skipped"])))
        (t/is (= 0 (unread-count consumer/consumer-nexus)))
        (t/is (= 0 (unread-count consumer/consumer-admin-console)))))))

(t/deftest consumer-records-the-lag-gauge
  (let [prof    (th/create-profile* 41 {:is-active true})
        metrics (make-consumer-metrics)
        cfg     {::db/pool (:app.db/pool th/*system*) ::mtx/metrics metrics}]
    (insert-audit-row (:id prof) "create-project")
    (insert-audit-row (:id prof) "create-file")
    ;; The cursor is still at zero: two confirmed events behind.
    (#'consumer/record-lag cfg {:id consumer/consumer-nexus})
    (t/is (= 2.0 (gauge-value metrics :audit-consumer-lag ["nexus"])))))

(t/deftest consumer-nexus-transport-failure-records-failed
  (let [prof    (th/create-profile* 42 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :throw (ex-info "timeout" {})}]
        (insert-audit-row (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (invoke-nexus metrics {:uri archive-uri})))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["nexus" "failed"])))
        ;; The chunk rolled back: the cursor did not move.
        (t/is (= 1 (unread-count consumer/consumer-nexus)))))))

(t/deftest consumer-nexus-refused-chunk-keeps-the-cursor
  (let [prof    (th/create-profile* 43 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 500}}]
        (insert-audit-row (:id prof) "create-project")
        (let [err (try
                    (invoke-nexus metrics {:uri archive-uri})
                    nil
                    (catch clojure.lang.ExceptionInfo cause cause))]
          (t/is (= :consumer-chunk-refused (:code (ex-data err)))))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-events ["nexus" "failed"])))
        (t/is (= 1 (unread-count consumer/consumer-nexus)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRODUCER: ONE ROW, NO QUEUE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- full-event
  "A complete event for submit*, which (unlike submit) assumes every
  required field is filled."
  [profile-id]
  {:name "create-file" :type "action" :source "backend"
   :profile-id profile-id :ip-addr "127.0.0.1"
   :props {} :context {}
   :created-at (ct/now) :tracked-at (ct/now)})

(t/deftest producer-inserts-one-row-and-no-queue
  (let [prof (th/create-profile* 50 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :nexus :admin-console}]
      (audit/submit* {::db/pool (:app.db/pool th/*system*)}
                     (full-event (:id prof)))
      (t/is (= 1 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
      ;; The pending queue is gone: the migration dropped it and the
      ;; producer has no queue to fill.
      (t/is (nil? (th/db-exec-one!
                   ["SELECT table_name FROM information_schema.tables
                      WHERE table_schema = 'public' AND table_name = 'audit_log_pending'"])))
      ;; The cursors did not move: the row waits for the next drain.
      (t/is (= 1 (unread-count consumer/consumer-nexus)))
      (t/is (= 1 (unread-count consumer/consumer-admin-console))))))

(t/deftest producer-rollback-leaves-no-row
  (let [prof (th/create-profile* 51 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log :nexus :admin-console}]
      (db/tx-run! {::db/pool    (:app.db/pool th/*system*)
                   ::db/rollback true}
                  (fn [cfg]
                    (audit/submit* cfg (full-event (:id prof)))
                    (t/is (= 1 (count (db/exec! cfg ["SELECT * FROM audit_log"]))))))
      (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GC: DELETE BELOW THE OLDEST ENABLED CURSOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest gc-deletes-only-what-every-enabled-consumer-passed
  (let [prof (th/create-profile* 60 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [http-mock    {:target 'app.http.client/req :return {:status 204}}
                   admin-mock   {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id prof) "create-project")
        (run-nexus {:uri archive-uri})
        ;; Nexus passed it, the admin console has not: nothing to delete.
        (t/is (= 0 (run-gc)))
        (t/is (= 1 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
        (run-admin-console)
        (t/is (= 1 (run-gc)))
        (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
        (t/is (:called? @http-mock))
        (t/is (:called? @admin-mock))))))

(t/deftest gc-ignores-a-disabled-consumer
  (let [prof (th/create-profile* 61 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (run-nexus {:uri archive-uri})
        ;; Team storage is off: its frozen cursor does not hold the row.
        (t/is (= 1 (run-gc)))
        (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"]))))))))

(t/deftest gc-keeps-the-telemetry-shadows
  (let [prof (th/create-profile* 62 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "create-project" {} "telemetry:backend")
        (run-nexus {:uri archive-uri})
        ;; The telemetry task owns the shadows: the GC leaves them.
        (t/is (= 1 (run-gc)))
        (let [rows (th/db-exec! ["SELECT source FROM audit_log"])]
          (t/is (= ["telemetry:backend"] (mapv :source rows))))))))

(t/deftest gc-records-the-discarded-metric
  (let [prof    (th/create-profile* 63 {:is-active true})
        metrics (make-consumer-metrics)
        job-def (ig/init-key :app.loggers.audit.gc/job-def
                             {::db/pool     (:app.db/pool th/*system*)
                              ::mtx/metrics metrics})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "create-file")
        (run-nexus {:uri archive-uri})
        (t/is (= 2 ((::jobs/handler job-def) nil {})))
        (t/is (= 2.0 (counter-value metrics :audit-consumer-discarded ["gc"])))))))

(t/deftest gc-deletes-in-batches
  (let [prof (th/create-profile* 64 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (dotimes [i 5]
          (insert-audit-row (:id prof) (str "event-" i)))
        (run-nexus {:uri archive-uri})
        (with-redefs-fn {#'gc/gc-batch-size 2}
          (fn []
            (t/is (= 5 (run-gc)))))
        (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"]))))))))

(t/deftest gc-waits-for-a-missing-cursor
  ;; A consumer enabled for the first time has no cursor: the GC treats
  ;; it as position 0 and deletes nothing until the consumer runs and
  ;; creates it at the end of the buffer.
  (let [prof (th/create-profile* 65 {:is-active true})]
    (th/db-exec! ["DELETE FROM audit_log_consumer_cursor
                    WHERE consumer = ?::smallint"
                  consumer/consumer-team-storage])
    (with-redefs [cf/flags #{:nexus :storage-usage}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (run-nexus {:uri archive-uri})
        ;; The row exists but team storage has no position yet.
        (t/is (= 0 (run-gc)))
        (t/is (= 1 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
        ;; Its first drain creates the cursor at the end (it does not
        ;; process the history), and the next GC can delete.
        (th/run-task! :audit-log-consumer-team-storage {})
        (t/is (some? (cursor-of consumer/consumer-team-storage)))
        (t/is (= 1 (run-gc)))
        (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"]))))))))
