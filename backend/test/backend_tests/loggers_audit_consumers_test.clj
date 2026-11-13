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
   [app.rpc :as-alias rpc]
   [app.rpc.commands.audit :as cmd-audit]
   [app.setup :as-alias setup]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]]
   [promesa.exec :as px])
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
  "Both cursors exist at (0, 0) before every test: the database reset
  deletes them, so seeding keeps the starting state deterministic, lets
  the GC delete without waiting for a first pass, and leaves the
  'no cursor' case to the tests that create it on purpose."
  [next]
  (doseq [consumer-id [consumer/nexus
                       consumer/admin-console]]
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

(defn- nexus-cfg
  ([uri] (nexus-cfg uri (:app.metrics/metrics th/*system*)))
  ([uri metrics]
   (cond-> {::db/pool           (:app.db/pool th/*system*)
            ::setup/shared-keys {:admin-console "ac-key" :nexus "nexus-key"}
            ::http/client       (:app.http.client/client th/*system*)
            ::mtx/metrics       metrics}
     uri (assoc ::consumer.nexus/uri uri))))

(defn- run-nexus
  ([] (run-nexus {}))
  ([params]
   (consumer.nexus/run-pass
    (nexus-cfg (:uri params)))))

(defn- admin-console-cfg
  [metrics]
  {::db/pool           (:app.db/pool th/*system*)
   :app.nitrate/client (:app.nitrate/client th/*system*)
   ::consumer.admin-console/uri-host "admin-console.example"
   ::mtx/metrics       metrics})

(defn- run-admin-console
  []
  (consumer.admin-console/run-pass
   (admin-console-cfg (:app.metrics/metrics th/*system*))))

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

(defn- xid-pair-across-a-decimal-boundary
  "Two xid values below the snapshot cut whose numeric order is the
  opposite of their text order (1000 sorts before 999 as text), so a
  text sort cannot produce the right answer by accident."
  []
  (let [xmin (Long/parseLong
              (:xmin (th/db-exec-one!
                      ["SELECT pg_snapshot_xmin(pg_current_snapshot())::text AS xmin"])))
        p    (long (Math/pow 10 (Math/floor (Math/log10 (double xmin)))))]
    (if (< p xmin)
      [(dec p) p]
      [(dec (quot p 10)) (quot p 10)])))

(defn- insert-audit-row-at
  "Insert one audit row at an explicit `(xid, seq)` position: the tests
  that pin the numeric order of the cursor place rows across a decimal
  boundary, as a real stream does when the transaction counter grows."
  [xid seq]
  (let [id (uuid/next)]
    (th/db-exec! ["INSERT INTO audit_log (id, name, type, source, profile_id,
                                          ip_addr, props, context, tracked_at,
                                          created_at, xid, seq)
                   VALUES (?, 'create-project', 'action', 'backend', ?,
                           '127.0.0.1', ?, ?, now(), now(), ?::xid8, ?::bigint)"
                  id uuid/zero (db/tjson {}) (db/tjson {}) (str xid) seq])
    id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NEXUS: THE WHOLE STREAM FROM ITS CURSOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest consumer-nexus-drains-from-its-cursor
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "create-file")
        (t/is (= 2 (unread-count consumer/nexus)))
        (run-nexus {:uri archive-uri})
        (t/is (:called? @http-mock))
        (t/is (= 1 (:call-count @http-mock)))
        (let [events (-> @http-mock :call-args second :body transit/decode :events)]
          (t/is (= ["create-project" "create-file"] (mapv :name events))))
        ;; The cursor advanced; the rows stay until the GC removes them.
        (t/is (= 0 (unread-count consumer/nexus)))
        (t/is (= 2 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
        ;; Nobody else moved: each consumer has its own position.
        (t/is (= 2 (unread-count consumer/admin-console)))))))

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
          (t/is (= 0 (unread-count consumer/nexus))))))))

(t/deftest consumer-nexus-chunks-by-the-configured-chunk-size
  ;; `chunk-size` is a var so a test can shrink it: the loop must honour
  ;; it and still send every event, in order, however many chunks it takes.
  (let [prof  (th/create-profile* 4 {:is-active true})
        names (mapv #(str "small-" %) (range 1 8))]
    (with-redefs [cf/flags #{:nexus}
                  consumer/chunk-size 3]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (doseq [name names]
          (insert-audit-row (:id prof) name))
        (run-nexus {:uri archive-uri})
        (let [bodies (map #(transit/decode (:body (second %))) (:call-args-list @http-mock))
              events (mapcat :events bodies)]
          (t/is (= [3 3 1] (mapv #(count (:events %)) bodies)))
          (t/is (= names (mapv :name events))))
        (t/is (= 0 (unread-count consumer/nexus)))))))

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

(t/deftest consumer-nexus-component-requires-uri-when-flag-set
  (with-redefs [cf/flags #{:nexus}
                cf/config (dissoc cf/config :nexus-uri)]
    (let [data (ex-data (try
                          (ig/init-key :app.loggers.audit.consumers.nexus/consumer
                                       (nexus-cfg nil))
                          (catch Throwable cause
                            cause)))]
      (t/is (= :task-not-configured (:code data))))))

(t/deftest consumer-nexus-component-does-not-start-with-the-flag-off
  (with-redefs [cf/flags #{}]
    (t/is (nil? (ig/init-key :app.loggers.audit.consumers.nexus/consumer
                             (nexus-cfg archive-uri))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ADMIN CONSOLE: ITS ALLOWLIST, ITS OWN FILTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest consumer-admin-console-component-requires-uri-when-flag-set
  ;; Without a destination the consumer can never advance its cursor, and
  ;; the GC waits for it: fail at boot instead of growing the buffer.
  (with-redefs [cf/flags #{:admin-console}
                cf/config (dissoc cf/config :admin-console-uri)]
    (let [data (ex-data (try
                          (ig/init-key :app.loggers.audit.consumers.admin-console/consumer
                                       (admin-console-cfg nil))
                          (catch Throwable cause
                            cause)))]
      (t/is (= :task-not-configured (:code data))))))

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
        (t/is (= 0 (unread-count consumer/admin-console)))))))

(t/deftest consumer-admin-console-forwards-organization-attribute-events
  ;; The allowlist dropped these two names during the rebase (upstream
  ;; #12861 and #12932 added them): an event outside it counts as skipped
  ;; and the GC deletes the row, so the admin console never sees the
  ;; attribute change.
  (let [prof (th/create-profile* 13 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id prof) "create-organization-attribute")
        (insert-audit-row (:id prof) "delete-organization-attribute")
        (insert-audit-row (:id prof) "update-organization-attribute")
        (insert-audit-row (:id prof) "unrelated-event")
        (run-admin-console)
        (t/is (:called? @admin-console-mock))
        (let [[_ _ params] (:call-args @admin-console-mock)]
          (t/is (= ["create-organization-attribute"
                    "delete-organization-attribute"
                    "update-organization-attribute"]
                   (mapv :name (:events params)))))
        (t/is (= 0 (unread-count consumer/admin-console)))))))

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
        (t/is (= 0 (unread-count consumer/admin-console)))))))

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
        (t/is (= 0 (unread-count consumer/nexus)))
        (t/is (= 1 (unread-count consumer/admin-console)))))))

(t/deftest consumer-admin-console-component-does-not-start-with-the-flag-off
  (with-redefs [cf/flags #{}]
    (t/is (nil? (ig/init-key :app.loggers.audit.consumers.admin-console/consumer
                             (admin-console-cfg (:app.metrics/metrics th/*system*)))))))

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
            consumer-cfg   {::db/pool           (:app.db/pool th/*system*)
                            ::mtx/metrics       (th/metrics-stub)
                            :app.nitrate/client client}]
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return
                                (fn [_ req & _]
                                  (when (str/includes? (str (:uri req)) "api/audit-log")
                                    (swap! admin-console-bodies conj (:body req)))
                                  {:status 204})}]
          (let [team-id (uuid/next)
                row     (insert-audit-row (:id prof) "create-project"
                                          {:team-id team-id})]
            (consumer.admin-console/run-pass consumer-cfg)
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
            (t/is (= 0 (unread-count consumer/admin-console)))))))))

(t/deftest consumer-admin-console-accepts-a-2xx-response
  ;; The ingest endpoint answers 204 by contract, but a proxy may normalize
  ;; it to another 2xx. Any 2xx is success: the chunk commits and the cursor
  ;; advances instead of freezing on a :malli.core/invalid-schema.
  (let [prof (th/create-profile* 75 {:is-active true})]
    (with-redefs [cf/flags  #{:admin-console}
                  cf/config (assoc cf/config
                                   :admin-console-uri "http://ac.example/admin-console/"
                                   :admin-console-shared-key "ac-key"
                                   :nexus-shared-key "nexus-key")]
      (let [shared-keys  {:admin-console "ac-key" :nexus "nexus-key"}
            client       (ig/init-key :app.nitrate/client
                                      {::http/client       (Object.)
                                       ::setup/shared-keys shared-keys})
            consumer-cfg {::db/pool           (:app.db/pool th/*system*)
                          ::mtx/metrics       (th/metrics-stub)
                          :app.nitrate/client client}]
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return {:status 200 :body "{}"}}]
          (insert-audit-row (:id prof) "create-project")
          (consumer.admin-console/run-pass consumer-cfg)
          (t/is (= 1 (:call-count @http-mock)))
          (t/is (= 0 (unread-count consumer/admin-console))))))))

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
                  (let [enriched (first (#'consumer.admin-console/enrich-events
                                         cfg [event]))]
                    (t/is (= "preset@example.com" (:profile-email enriched)))
                    (t/is (= "Preset Name" (:profile-name enriched))))))))

(t/deftest consumer-admin-console-fills-missing-actor-profile-field-independently
  ;; Each top-level key fills on its own; guards an or→and regression in
  ;; collect-actor-ids-needing-profile.
  (let [actor (th/create-profile* 73 {:is-active true})]
    (db/tx-run! th/*system*
                (fn [cfg]
                  (let [email-only (first (#'consumer.admin-console/enrich-events
                                           cfg [{:profile-id    (:id actor)
                                                 :profile-email "preset@example.com"
                                                 :props         {}}]))
                        name-only  (first (#'consumer.admin-console/enrich-events
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
          (t/is (= 0 (:seq (cursor-of consumer/nexus))))
          (db/exec! conn ["COMMIT"]))
        ;; A is committed: both rows are visible, in xid order.
        (run-nexus {:uri archive-uri})
        (let [events (-> @http-mock :call-args second :body transit/decode :events)]
          (t/is (= ["tx-A" "tx-B"] (mapv :name events))))
        (t/is (= 0 (unread-count consumer/nexus)))))))

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
          (t/is (= 0 (:seq (cursor-of consumer/nexus))))
          (db/exec! conn ["COMMIT"]))
        ;; After the commit the 300 rows are read in 128-row chunks,
        ;; and the seq within the transaction keeps their order.
        (run-nexus {:uri archive-uri})
        (let [bodies (map #(transit/decode (:body (second %)))
                          (:call-args-list @http-mock))
              events (mapcat :events bodies)]
          (t/is (= [128 128 44] (mapv #(count (:events %)) bodies)))
          (t/is (= names (mapv :name events))))
        (t/is (= 0 (unread-count consumer/nexus)))))))

(t/deftest cursor-lock-serializes-two-runs-of-the-same-consumer
  (let [prof (th/create-profile* 32 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (with-open [conn (db/open th/*system*)]
          (db/exec! conn ["BEGIN"])
          (db/exec! conn ["SELECT consumer FROM audit_log_consumer_cursor
                            WHERE consumer = ?::smallint FOR UPDATE"
                          consumer/nexus])
          (let [system th/*system*
                job    (future (binding [th/*system* system]
                                 (run-nexus {:uri archive-uri})))]
            (Thread/sleep 500)
            ;; The run is blocked on the cursor: it never sent.
            (t/is (false? (:called? @http-mock)))
            (db/exec! conn ["COMMIT"])
            (t/is (not= :timeout (deref job 10000 :timeout))))
          (t/is (:called? @http-mock))
          (t/is (= 0 (unread-count consumer/nexus))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE TICK: ONE PASS ACROSS INSTANCES, WITH A FRONTIER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sent-event-names
  "The event names of every batch the http mock received, in order."
  [http-mock]
  (->> (:call-args-list @http-mock)
       (mapcat (comp :events transit/decode :body second))
       (mapv :name)))

(defn- hold-tick
  "Take the tick lock of a consumer on a connection, as another instance
  would, answering the [classid objid] pair the caller releases with."
  [conn consumer-id]
  (let [[classid objid] (#'consumer/tick-lock-key consumer-id)]
    (assert (true? (db/session-try-lock conn classid objid)))
    [classid objid]))

(defn- release-tick
  [conn [classid objid]]
  (db/session-unlock conn classid objid))

(t/deftest consumer-tick-skips-when-another-instance-holds-it
  (let [prof (th/create-profile* 33 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (with-open [conn (db/open th/*system*)]
          (let [tick (hold-tick conn consumer/nexus)]
            ;; Another instance is draining: this pass does nothing.
            (t/is (= ::consumer/busy (run-nexus {:uri archive-uri})))
            (t/is (false? (:called? @http-mock)))
            (t/is (= 0 (:seq (cursor-of consumer/nexus))))
            (t/is (true? (release-tick conn tick)))))
        ;; With the tick free, the same pass drains.
        (run-nexus {:uri archive-uri})
        (t/is (:called? @http-mock))
        (t/is (= 0 (unread-count consumer/nexus)))))))

(t/deftest consumer-tick-busy-records-only-the-pass-counter
  (let [prof     (th/create-profile* 34 {:is-active true})
        recorded (atom [])]
    (with-redefs [cf/flags #{:nexus}
                  consumer/record-count (fn [_ id labels amount]
                                          (swap! recorded conj [id labels amount]))]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (with-open [conn (db/open th/*system*)]
          (let [tick (hold-tick conn consumer/nexus)]
            (t/is (= ::consumer/busy (run-nexus {:uri archive-uri})))
            (release-tick conn tick)))
        (t/is (= [[:audit-consumer-passes ["nexus" "busy"] 1]] @recorded))
        (t/is (false? (:called? @http-mock)))))))

(t/deftest consumer-tick-never-collides-with-a-file-lock
  ;; `update-file` takes a one-key advisory lock from the hash of the file
  ;; id; the consumers take a two-key one, a different lock space, so a
  ;; file lock with the same numbers cannot block a pass.
  (let [prof (th/create-profile* 36 {:is-active true})
        [classid objid] (#'consumer/tick-lock-key consumer/nexus)
        bigint-key (+ (* (long classid) 4294967296) objid)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (with-open [conn (db/open th/*system*)]
          (t/is (true? (:lock (db/exec-one! conn
                                            ["select pg_try_advisory_lock(?::bigint) as lock"
                                             bigint-key]))))
          (t/is (not= ::consumer/busy (run-nexus {:uri archive-uri})))
          (t/is (:called? @http-mock))
          (db/exec-one! conn ["select pg_advisory_unlock(?::bigint)" bigint-key]))))))

(defn- make-pool
  "A dedicated pool, to pin how many connections a pass needs."
  ([max-size] (make-pool max-size {}))
  ([max-size extra]
   (ig/init-key :app.db/pool
                (merge {::db/uri      (:database-uri th/config)
                        ::db/username (:database-username th/config)
                        ::db/password (:database-password th/config)
                        ::db/max-size max-size
                        ::db/name     :test-consumers
                        ::mtx/metrics (ig/init-key :app.metrics/metrics {:default {}})}
                       extra))))

(defn- run-nexus-on
  "Run one nexus pass merging `overrides` over the test cfg."
  [overrides]
  (consumer.nexus/run-pass
   (merge (nexus-cfg archive-uri) overrides)))

(t/deftest consumer-pass-needs-one-connection
  ;; The frontier, every chunk transaction and the lag gauge run on the
  ;; connection that holds the tick: a pool of one is enough.
  (let [prof (th/create-profile* 37 {:is-active true})
        pool (make-pool 1)]
    (try
      (with-redefs [cf/flags #{:nexus}]
        (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
          (with-open [conn (db/open th/*system*)]
            (insert-rows-in-one-transaction conn "lote-" 300)
            (db/exec! conn ["COMMIT"]))
          (run-nexus-on {::db/pool pool})
          (let [bodies (map #(transit/decode (:body (second %)))
                            (:call-args-list @http-mock))]
            (t/is (= [128 128 44] (mapv #(count (:events %)) bodies))))
          (t/is (= 0 (unread-count consumer/nexus)))))
      (finally
        (ig/halt-key! :app.db/pool pool)))))

(t/deftest consumer-pass-clears-a-stale-tick-on-the-borrowed-connection
  ;; A pass that could not release the tick leaves it on its pooled
  ;; connection; the next pass borrows that connection, clears whatever it
  ;; carries and drains.
  (let [prof (th/create-profile* 38 {:is-active true})
        pool (make-pool 1)]
    (try
      (with-redefs [cf/flags #{:nexus}]
        (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
          (insert-audit-row (:id prof) "create-project")
          (with-open [conn (db/open pool)]
            (let [[classid objid] (#'consumer/tick-lock-key consumer/nexus)]
              (t/is (true? (db/session-try-lock conn classid objid)))))
          (run-nexus-on {::db/pool pool})
          (t/is (:called? @http-mock))
          (t/is (= 0 (unread-count consumer/nexus)))))
      (finally
        (ig/halt-key! :app.db/pool pool)))))

(t/deftest consumer-without-a-cursor-starts-at-the-beginning
  ;; The migration does not seed cursors: a consumer with no row creates
  ;; one at (0, 0), so its first pass processes the buffer that is already
  ;; there instead of skipping the history.
  (let [prof (th/create-profile* 45 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (insert-audit-row (:id prof) "create-file")
        (th/db-exec! ["DELETE FROM audit_log_consumer_cursor
                        WHERE consumer = ?::smallint"
                      consumer/nexus])
        (run-nexus {:uri archive-uri})
        (t/is (:called? @http-mock))
        (t/is (some? (cursor-of consumer/nexus)))
        (t/is (= 0 (unread-count consumer/nexus)))))))

(t/deftest consumer-pass-with-its-flag-off-sends-nothing
  ;; `run-pass` is public and its docstring invites direct calls. The loop
  ;; is gated at boot, so the pass checks the flag too: with it off nothing
  ;; is sent and the cursor is neither created nor advanced.
  (let [prof (th/create-profile* 46 {:is-active true})]
    (with-redefs [cf/flags #{}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (th/db-exec! ["DELETE FROM audit_log_consumer_cursor
                        WHERE consumer = ?::smallint"
                      consumer/nexus])
        (t/is (= ::consumer/disabled (run-nexus {:uri archive-uri})))
        (t/is (false? (:called? @http-mock)))
        (t/is (nil? (cursor-of consumer/nexus)))))))

(t/deftest drain-consumer-rejects-an-unknown-id
  ;; The flag lookup answers false for an id outside the registry, so the
  ;; unknown-consumer raise must happen first: a typo must fail, not silently
  ;; disable the pass.
  (let [data (try
               (consumer/drain-consumer (nexus-cfg nil) 999 (fn [_ _] nil))
               nil
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (t/is (= :internal (:type data)))
    (t/is (= :unknown-audit-consumer (:code data)))
    (t/is (= 999 (:consumer data)))))

(t/deftest admin-console-pass-with-its-flag-off-sends-nothing
  ;; `nitrate/call` returns nil when `:admin-console` is off, so without the
  ;; gate the pass would count the nil as success and advance the cursor
  ;; over events that never reached the destination.
  (let [prof (th/create-profile* 47 {:is-active true})]
    (with-redefs [cf/flags #{:audit-log}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (th/db-exec! ["DELETE FROM audit_log_consumer_cursor
                        WHERE consumer = ?::smallint"
                      consumer/admin-console])
        (t/is (= ::consumer/disabled (run-admin-console)))
        (t/is (false? (:called? @http-mock)))
        (t/is (nil? (cursor-of consumer/admin-console)))))))

(t/deftest consumer-pass-stops-at-the-watermark
  ;; A pass drains up to the newest row that existed when it started: a
  ;; row committed while the pass runs is left for the next one, so the
  ;; pass ends instead of chasing a tail of tiny chunks for ever.
  (let [prof    (th/create-profile* 35 {:is-active true})
        arrived (atom 0)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ _ & _]
                                        (when (= 1 (swap! arrived inc))
                                          (insert-audit-row (:id prof) "mid-pass"))
                                        {:status 204})}]
        (insert-audit-row (:id prof) "before-1")
        (insert-audit-row (:id prof) "before-2")
        (run-nexus {:uri archive-uri})
        (t/is (= 1 (:call-count @http-mock)))
        (t/is (= ["before-1" "before-2"] (sent-event-names http-mock)))
        ;; The row of the pass in course waits for the next one.
        (t/is (= 1 (unread-count consumer/nexus)))
        (run-nexus {:uri archive-uri})
        (t/is (= 2 (:call-count @http-mock)))
        (t/is (= ["before-1" "before-2" "mid-pass"] (sent-event-names http-mock)))
        (t/is (= 0 (unread-count consumer/nexus)))))))

(t/deftest consumer-loop-runs-passes-until-interrupted
  (let [passes (atom 0)]
    (with-redefs-fn {#'consumer/sleep-ms 10}
      (fn []
        (let [thread (consumer/start-loop
                      {}
                      consumer/nexus
                      (fn [_] (swap! passes inc)))]
          (try
            (loop [i 0]
              (when (and (< @passes 3) (< i 300))
                (Thread/sleep 5)
                (recur (inc i))))
            (t/is (<= 3 @passes))
            (finally
              (px/interrupt! thread)))
          (.join ^Thread thread 5000)
          (t/is (false? (.isAlive ^Thread thread))))))))

(t/deftest consumer-loop-survives-a-failing-pass
  (let [passes (atom 0)]
    (with-redefs-fn {#'consumer/sleep-ms 10}
      (fn []
        (let [thread (consumer/start-loop
                      {}
                      consumer/nexus
                      (fn [_]
                        (swap! passes inc)
                        (throw (ex-info "boom" {}))))]
          (try
            (loop [i 0]
              (when (and (< @passes 3) (< i 300))
                (Thread/sleep 5)
                (recur (inc i))))
            (t/is (<= 3 @passes))
            (finally
              (px/interrupt! thread)))
          (.join ^Thread thread 5000)
          (t/is (false? (.isAlive ^Thread thread))))))))

(t/deftest consumer-component-starts-a-loop-that-halt-stops
  ;; The component is the only door to the loop: it starts a pass, keeps
  ;; running it and `halt-component` stops the thread. The pass is a fake
  ;; on purpose: a real drain here would keep the shared database busy in
  ;; the background while the rest of the namespace runs, which is what
  ;; made this suite alternate. The drain itself is covered by the
  ;; `run-pass` tests.
  (let [passes (atom 0)
        cfg    {::db/pool (:app.db/pool th/*system*)}]
    (with-redefs [cf/flags          #{:nexus}
                  consumer/sleep-ms 10]
      (let [thread (consumer/init-component cfg consumer/nexus identity
                                            (fn [_] (swap! passes inc)))]
        (try
          (t/is (th/wait-for #(<= 3 @passes)))
          (finally
            (consumer/halt-component thread)))
        (t/is (false? (.isAlive ^Thread thread)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; METRICS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The pass cfg of these tests carries an isolated metrics registry
;; (pattern from `jobs-metrics-test`), so assertions read exact values
;; instead of the shared test-system registry. `th/run-task!` would use
;; the latter and its counts would leak across tests.
(def ^:private consumer-metric-ids
  [:audit-consumer-events :audit-consumer-discarded :audit-consumer-lag
   :audit-consumer-passes])

(defn- make-consumer-metrics
  []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics consumer-metric-ids)}))

(defn- invoke-nexus
  "Run one nexus pass with an isolated metrics registry, so assertions
  read exact values instead of the shared test-system registry."
  [metrics params]
  (consumer.nexus/run-pass
   (nexus-cfg (:uri params) metrics)))

(defn- invoke-admin-console
  "Run one admin-console pass with an isolated metrics registry."
  [metrics]
  (consumer.admin-console/run-pass
   (admin-console-cfg metrics)))

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

(t/deftest consumer-admin-console-rejects-a-3xx-response
  ;; A redirect is a configuration error, not a success: the chunk rolls
  ;; back, the cursor does not advance and the pass counts failed. The test
  ;; pins the typed error: without the 3xx branch the pass also fails, but
  ;; with :malli.core/invalid-schema.
  (let [prof    (th/create-profile* 76 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags  #{:admin-console}
                  cf/config (assoc cf/config
                                   :admin-console-uri "http://ac.example/admin-console/"
                                   :admin-console-shared-key "ac-key"
                                   :nexus-shared-key "nexus-key")]
      (let [shared-keys  {:admin-console "ac-key" :nexus "nexus-key"}
            client       (ig/init-key :app.nitrate/client
                                      {::http/client       (Object.)
                                       ::setup/shared-keys shared-keys})
            consumer-cfg {::db/pool           (:app.db/pool th/*system*)
                          ::mtx/metrics       metrics
                          :app.nitrate/client client}]
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return {:status 302
                                         :headers {"location" "http://ac.example/admin-console/api/audit-log"}
                                         :body ""}}]
          (insert-audit-row (:id prof) "create-project")
          (let [err (try
                      (consumer.admin-console/run-pass consumer-cfg)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
            (t/is (= :admin-console-send-failed (:code (ex-data err))))
            (t/is (= :nitrate-http-error (:type (ex-data (ex-cause err)))))
            (t/is (= 302 (:status (ex-data (ex-cause err))))))
          (t/is (= 1 (unread-count consumer/admin-console)))
          (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["admin-console" "failed"]))))))))

(t/deftest consumer-empty-pass-records-lag-zero
  (let [metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        ;; No rows: the pass finds no frontier and sends nothing.
        (invoke-nexus metrics {:uri archive-uri})
        (t/is (false? (:called? @http-mock)))
        (t/is (zero? (gauge-value metrics :audit-consumer-lag ["nexus"])))))))

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
        (t/is (= 0 (unread-count consumer/nexus)))
        (t/is (= 0 (unread-count consumer/admin-console)))))))

(t/deftest consumer-records-the-lag-gauge
  (let [prof    (th/create-profile* 41 {:is-active true})
        metrics (make-consumer-metrics)]
    (insert-audit-row (:id prof) "create-project")
    (insert-audit-row (:id prof) "create-file")
    ;; The cursor is still at zero: two confirmed events behind.
    (with-open [conn (db/open th/*system*)]
      (#'consumer/record-lag conn metrics {:id consumer/nexus}))
    (t/is (= 2.0 (gauge-value metrics :audit-consumer-lag ["nexus"])))))

(t/deftest consumer-pass-counter-records-ok-and-busy
  (let [prof    (th/create-profile* 39 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (invoke-nexus metrics {:uri archive-uri})
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "ok"])))

        (insert-audit-row (:id prof) "create-file")
        (with-open [conn (db/open th/*system*)]
          (let [tick (hold-tick conn consumer/nexus)]
            (t/is (= ::consumer/busy (invoke-nexus metrics {:uri archive-uri})))
            (release-tick conn tick)))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "busy"])))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "ok"])))))))

(t/deftest consumer-pass-counter-records-a-failure-and-releases-the-tick
  (let [prof    (th/create-profile* 40 {:is-active true})
        metrics (make-consumer-metrics)
        calls   (atom 0)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ _ & _]
                                        (if (= 1 (swap! calls inc))
                                          (throw (ex-info "timeout" {}))
                                          {:status 204}))}]
        (insert-audit-row (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (invoke-nexus metrics {:uri archive-uri})))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "failed"])))
        ;; The tick was released: the next pass drains and counts ok.
        (invoke-nexus metrics {:uri archive-uri})
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "ok"])))
        (t/is (= 0 (unread-count consumer/nexus)))))))

(t/deftest consumer-pass-counter-counts-a-frontier-failure-as-failed
  ;; The counter covers the whole pass, not only the chunk send: a read
  ;; that fails before the first chunk is a failed pass too.
  (let [metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}
                  consumer/sql:last-visible "SELECT 1/0 AS xid_text, 0::bigint AS seq"]
      (t/is (thrown? Exception (invoke-nexus metrics {:uri archive-uri})))
      (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "failed"])))
      (t/is (zero? (counter-value metrics :audit-consumer-passes ["nexus" "ok"]))))))

(t/deftest consumer-pass-survives-an-unlock-failure
  ;; A release that fails is logged and swallowed: it must not replace the
  ;; result of the pass. The real unlock still runs, so the test leaves no
  ;; session lock behind on a pooled connection.
  (let [prof        (th/create-profile* 46 {:is-active true})
        metrics     (make-consumer-metrics)
        real-unlock db/session-unlock]
    (with-redefs [cf/flags           #{:nexus}
                  db/session-unlock (fn [& args]
                                      (apply real-unlock args)
                                      (throw (ex-info "gone" {})))]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (t/is (nil? (invoke-nexus metrics {:uri archive-uri})))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "ok"])))
        (t/is (= 0 (unread-count consumer/nexus)))))
    ;; No advisory lock of the tick stays in the database: the mock released
    ;; it for real before throwing. Read pg_locks (all sessions), because a
    ;; session that already holds a lock can re-acquire it and answer true.
    (let [[classid objid] (#'consumer/tick-lock-key consumer/nexus)]
      (t/is (zero? (:cnt (th/db-exec-one!
                          ["SELECT count(*) AS cnt
                              FROM pg_locks
                             WHERE locktype = 'advisory'
                               AND classid = ?::int
                               AND objid = ?::int
                               AND objsubid = 2"
                           classid objid])))))))

(t/deftest consumer-pass-reports-the-chunks-that-committed-before-a-failure
  ;; A refusal on the second chunk must not hide the 128 events of the
  ;; first: its transaction committed and the cursor moved past it.
  (let [prof    (th/create-profile* 43 {:is-active true})
        metrics (make-consumer-metrics)
        calls   (atom 0)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ _ & _]
                                        (if (= 2 (swap! calls inc))
                                          (throw (ex-info "timeout" {}))
                                          {:status 204}))}]
        (with-open [conn (db/open th/*system*)]
          (insert-rows-in-one-transaction conn "lote-" 300)
          (db/exec! conn ["COMMIT"]))
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (invoke-nexus metrics {:uri archive-uri})))
        ;; The first chunk committed and the cursor moved past it.
        (t/is (= 172 (unread-count consumer/nexus)))
        (t/is (= 128.0 (counter-value metrics :audit-consumer-events ["nexus" "processed"])))
        (t/is (= 128.0 (counter-value metrics :audit-consumer-events ["nexus" "failed"])))
        (t/is (= 1.0 (counter-value metrics :audit-consumer-passes ["nexus" "failed"])))))))

(t/deftest consumer-busy-does-not-sample-the-lag
  (let [prof    (th/create-profile* 41 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row (:id prof) "create-project")
        (invoke-nexus metrics {:uri archive-uri})
        (t/is (zero? (gauge-value metrics :audit-consumer-lag ["nexus"])))

        (insert-audit-row (:id prof) "create-file")
        (with-open [conn (db/open th/*system*)]
          (let [tick (hold-tick conn consumer/nexus)]
            (t/is (= ::consumer/busy (invoke-nexus metrics {:uri archive-uri})))
            (release-tick conn tick)))
        ;; Still the sample of the first pass, not the row behind.
        (t/is (zero? (gauge-value metrics :audit-consumer-lag ["nexus"])))))))

(t/deftest consumer-pass-sends-full-chunks-under-continuous-traffic
  (let [prof    (th/create-profile* 42 {:is-active true})
        arrived (atom 0)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return (fn [_ _ & _]
                                        (swap! arrived inc)
                                        (insert-audit-row (:id prof) (str "during-" @arrived))
                                        {:status 204})}]
        (with-open [conn (db/open th/*system*)]
          (insert-rows-in-one-transaction conn "lote-" 300)
          (db/exec! conn ["COMMIT"]))
        (run-nexus {:uri archive-uri})
        (let [bodies (map #(transit/decode (:body (second %)))
                          (:call-args-list @http-mock))]
          (t/is (= [128 128 44] (mapv #(count (:events %)) bodies))))
        ;; The rows that arrived during the pass wait for the next one.
        (t/is (= 3 (unread-count consumer/nexus)))))))

(t/deftest consumer-component-does-not-start-on-a-read-only-database
  (let [pool (make-pool 1 {::db/read-only true})]
    (try
      (with-redefs [cf/flags #{:nexus}]
        (t/is (nil? (ig/init-key :app.loggers.audit.consumers.nexus/consumer
                                 (merge (nexus-cfg archive-uri) {::db/pool pool})))))
      (finally
        (ig/halt-key! :app.db/pool pool)))))

(t/deftest consumer-lag-gauge-is-capped
  ;; The gauge counts behind the cursor once per pass; past 10000 it
  ;; saturates, so a long backlog does not turn every pass into an
  ;; O(backlog) count. Remove the LIMIT and this test fails.
  (let [prof    (th/create-profile* 44 {:is-active true})
        metrics (make-consumer-metrics)]
    (with-open [conn (db/open th/*system*)]
      (insert-rows-in-one-transaction conn "lote-" 10001)
      (db/exec! conn ["COMMIT"]))
    (with-open [conn (db/open th/*system*)]
      (#'consumer/record-lag conn metrics {:id consumer/nexus}))
    (t/is (= 10000.0 (gauge-value metrics :audit-consumer-lag ["nexus"])))))

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
        (t/is (= 1 (unread-count consumer/nexus)))))))

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
        (t/is (= 1 (unread-count consumer/nexus)))))))

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
      ;; The producer never fills the pending queue: the row exists, the
      ;; queue stays empty (or absent, on an installation that predates it).
      (let [{:keys [pending]} (th/db-exec-one!
                               ["SELECT to_regclass('public.audit_log_pending') AS pending"])]
        (t/is (or (nil? pending)
                  (zero? (:cnt (th/db-exec-one!
                                ["SELECT count(*) AS cnt FROM audit_log_pending"]))))))
      ;; The cursors did not move: the row waits for the next drain.
      (t/is (= 1 (unread-count consumer/nexus)))
      (t/is (= 1 (unread-count consumer/admin-console))))))

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
        ;; The admin console is off: its frozen cursor does not hold the row.
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
        (with-redefs-fn {#'gc/batch-size 2}
          (fn []
            (t/is (= 5 (run-gc)))))
        (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"]))))))))

(t/deftest gc-waits-for-a-missing-cursor
  ;; A consumer enabled for the first time has no cursor: the GC treats
  ;; it as position 0 and deletes nothing until the consumer runs and
  ;; creates it at (0, 0).
  (let [prof (th/create-profile* 65 {:is-active true})]
    (th/db-exec! ["DELETE FROM audit_log_consumer_cursor
                    WHERE consumer = ?::smallint"
                  consumer/admin-console])
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [http-mock          {:target 'app.http.client/req :return {:status 204}}
                   admin-console-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row (:id prof) "create-project")
        (run-nexus {:uri archive-uri})
        ;; The row exists but the admin console has no position yet.
        (t/is (= 0 (run-gc)))
        (t/is (= 1 (count (th/db-exec! ["SELECT * FROM audit_log"]))))
        ;; Its first drain creates the cursor at (0, 0), processes the
        ;; history and reaches the row, so the next GC can delete.
        (run-admin-console)
        (t/is (:called? @admin-console-mock))
        (t/is (some? (cursor-of consumer/admin-console)))
        (t/is (= 1 (run-gc)))
        (t/is (= 0 (count (th/db-exec! ["SELECT * FROM audit_log"]))))))))

(t/deftest gc-min-cursor-orders-xid8-numerically
  ;; The oldest cursor is chosen by the xid8 value, not by its text form:
  ;; '1000' sorts before '999' as text, and the GC would take the consumer
  ;; that is ahead and delete rows it has not read.
  (let [[low high] (xid-pair-across-a-decimal-boundary)]
    (th/db-exec! ["UPDATE audit_log_consumer_cursor
                      SET xid = ?::xid8, seq = 7 WHERE consumer = ?::smallint"
                  (str low) consumer/nexus])
    (th/db-exec! ["UPDATE audit_log_consumer_cursor
                      SET xid = ?::xid8, seq = 3 WHERE consumer = ?::smallint"
                  (str high) consumer/admin-console])
    (let [cursor (@#'gc/get-min-cursor {::db/pool th/*pool*}
                                       [consumer/nexus consumer/admin-console])]
      (t/is (= (str low) (:xid-text cursor)))
      (t/is (= 7 (:seq cursor))))))

(t/deftest gc-does-not-delete-past-an-unread-cursor
  ;; Two enabled consumers across a decimal boundary: the GC deletes up to
  ;; the oldest one. Sorting by text would pick the consumer that is ahead
  ;; and remove a row it has not read.
  (let [[low high] (xid-pair-across-a-decimal-boundary)]
    (th/db-exec! ["UPDATE audit_log_consumer_cursor
                      SET xid = ?::xid8, seq = 10 WHERE consumer = ?::smallint"
                  (str low) consumer/nexus])
    (th/db-exec! ["UPDATE audit_log_consumer_cursor
                      SET xid = ?::xid8, seq = 10 WHERE consumer = ?::smallint"
                  (str high) consumer/admin-console])
    (insert-audit-row-at high 1)
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (t/is (= 0 (run-gc)))
      (t/is (= 1 (count (th/db-exec! ["SELECT * FROM audit_log"])))))))

(t/deftest gc-deletes-nothing-with-no-consumer-enabled
  ;; With no consumer enabled the GC does not drain the buffer: the
  ;; `:audit-log-gc` switch keeps it off until the consumers exist. A
  ;; drain here would delete events a later consumer could still read.
  (with-redefs [cf/flags #{}]
    (insert-audit-row uuid/zero "create-project")
    (insert-audit-row uuid/zero "create-file")
    (insert-audit-row uuid/zero "create-project" {} "telemetry:backend")
    (t/is (= 0 (run-gc)))
    (t/is (= 3 (count (th/db-exec! ["SELECT * FROM audit_log"]))))))

(t/deftest consumer-frontier-uses-the-numeric-max
  ;; The frontier is the newest visible row, compared as xid8 and not as
  ;; its text form: '999' sorts above '1000' as text, so a text ORDER BY
  ;; stops the pass one row early and leaves the newest row invisible.
  (let [[low high] (xid-pair-across-a-decimal-boundary)]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row-at low 1)
        (insert-audit-row-at high 1)
        (run-nexus {:uri archive-uri})
        (t/is (= 2 (count (sent-event-names http-mock))))
        (t/is (= (str high) (:xid (cursor-of consumer/nexus))))))))
