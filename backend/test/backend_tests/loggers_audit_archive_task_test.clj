;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.loggers-audit-archive-task-test
  (:require
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as-alias http]
   [app.setup :as-alias setup]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private archive-uri "http://nexus.example/archive")

(defn- insert-audit-row!
  [profile-id name]
  (th/db-insert! :audit-log
                 {:id         (uuid/next)
                  :name       name
                  :type       "action"
                  :source     "backend"
                  :profile-id profile-id
                  :ip-addr    (db/inet "127.0.0.1")
                  :props      (db/tjson {:team-id (uuid/next)})
                  :context    (db/tjson {})
                  :tracked-at (ct/now)
                  :created-at (ct/now)}))

(t/deftest archive-events-sends-to-nitrate-then-nexus
  ;; Create profile before enabling :admin-console — system nitrate
  ;; client is nil in tests, and team bootstrap would call it.
  (let [prof  (th/create-profile* 1 {:is-active true})
        order (atom [])]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call
                                 :return (fn [& _]
                                           (swap! order conj :nitrate)
                                           nil)}
                   http-mock    {:target 'app.http.client/req
                                 :return (fn [& _]
                                           (swap! order conj :nexus)
                                           {:status 204})}]
        (insert-audit-row! (:id prof) "create-project")
        (insert-audit-row! (:id prof) "create-file")
        (th/run-task! :audit-log-archive {:uri archive-uri})
        (t/is (:called? @nitrate-mock))
        (t/is (:called? @http-mock))
        (t/is (= 1 (:call-count @nitrate-mock)))
        (t/is (= 1 (:call-count @http-mock)))
        (t/is (= [:nitrate :nexus] @order))
        (let [[_ method params] (:call-args @nitrate-mock)]
          (t/is (= :ingest-audit-log method))
          (t/is (= 2 (count (:events params))))
          (t/is (= #{"create-project" "create-file"}
                   (into #{} (map :name) (:events params))))
          (t/is (every? uuid? (map :id (:events params)))))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
          (t/is (= 2 (count rows))))))))

(t/deftest archive-events-filters-nitrate-event-names
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (insert-audit-row! (:id prof) "unrelated-event")
        (th/run-task! :audit-log-archive {:uri archive-uri})
        (t/is (:called? @nitrate-mock))
        (t/is (:called? @http-mock))
        (let [[_ _ params] (:call-args @nitrate-mock)]
          (t/is (= ["create-project"] (mapv :name (:events params)))))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
          (t/is (= 2 (count rows))))))))

(t/deftest archive-events-skips-nitrate-when-no-matching-names
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "unrelated-event")
        (th/run-task! :audit-log-archive {:uri archive-uri})
        (t/is (false? (:called? @nitrate-mock)))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-admin-console-only-marks-without-uri
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-archive {})
        (t/is (:called? @nitrate-mock))
        (t/is (false? (:called? @http-mock)))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-admin-console-only-marks-non-allowlisted
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "unrelated-event")
        (th/run-task! :audit-log-archive {})
        (t/is (false? (:called? @nitrate-mock)))
        (t/is (false? (:called? @http-mock)))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-propagates-nitrate-error
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call
                                 :throw (ex-info "nitrate down" {})}
                   http-mock    {:target 'app.http.client/req :return {:status 204}}]
        (insert-audit-row! (:id prof) "create-project")
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (th/run-task! :audit-log-archive {:uri archive-uri})))
        (t/is (:called? @nitrate-mock))
        (t/is (false? (:called? @http-mock)))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-uses-nexus-without-admin-console
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock    {:target 'app.http.client/req :return {:status 204}}
                   nitrate-mock {:target 'app.nitrate/call :return nil}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-archive {:uri archive-uri})
        (t/is (false? (:called? @nitrate-mock)))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-skips-mark-when-nexus-fails
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus}]
      (with-mocks [http-mock {:target 'app.http.client/req :return {:status 500}}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-archive {:uri archive-uri})
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-both-flags-skips-mark-when-nexus-fails
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags #{:nexus :admin-console}]
      (with-mocks [nitrate-mock {:target 'app.nitrate/call :return nil}
                   http-mock    {:target 'app.http.client/req :return {:status 500}}]
        (insert-audit-row! (:id prof) "create-project")
        (th/run-task! :audit-log-archive {:uri archive-uri})
        (t/is (:called? @nitrate-mock))
        (t/is (:called? @http-mock))
        (let [rows (th/db-exec! ["select * from audit_log where archived_at is null"])]
          (t/is (= 1 (count rows))))))))

(t/deftest archive-events-encodes-db-rows-for-nitrate
  (let [prof (th/create-profile* 1 {:is-active true})]
    (with-redefs [cf/flags  #{:nexus :admin-console}
                  cf/config (assoc cf/config
                                   :admin-console-uri "http://ac.example/admin-console/"
                                   :admin-console-shared-key "ac-key"
                                   :nexus-shared-key "nexus-key")]
      (let [nitrate-bodies (atom [])
            shared-keys    {:admin-console "ac-key" :nexus "nexus-key"}
            client         (ig/init-key :app.nitrate/client
                                        {::http/client       (Object.)
                                         ::setup/shared-keys shared-keys})
            handler        (ig/init-key :app.loggers.audit.archive-task/handler
                                        {::db/pool           (:app.db/pool th/*system*)
                                         ::setup/shared-keys shared-keys
                                         ::http/client       (:app.http.client/client th/*system*)
                                         :app.nitrate/client client})]
        (with-mocks [http-mock {:target 'app.http.client/req
                                :return
                                (fn [_ req & _]
                                  (when (str/includes? (str (:uri req)) "api/audit-log")
                                    (swap! nitrate-bodies conj (:body req)))
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
            (handler {:props {:uri archive-uri}})
            (t/is (= 1 (count @nitrate-bodies)))
            (let [body  (json/decode (first @nitrate-bodies) :key-fn json/read-kebab-key)
                  event (first (:events body))]
              (t/is (= 1 (count (:events body))))
              (t/is (= (str (:id row)) (str (:id event))))
              (t/is (= "create-project" (:name event)))
              (t/is (= "127.0.0.1" (:ip-addr event)))
              (t/is (string? (:created-at event)))
              (t/is (string? (:tracked-at event)))
              (t/is (= (str team-id) (str (get-in event [:props :team-id])))))
            (let [rows (th/db-exec! ["select * from audit_log where archived_at is not null"])]
              (t/is (= 1 (count rows))))))))))

(t/deftest archive-events-requires-uri-when-nexus-flag-set
  (with-redefs [cf/flags #{:nexus}
                cf/config (dissoc cf/config :audit-log-archive-uri)]
    (let [data (ex-data (try
                          (th/run-task! :audit-log-archive {})
                          (catch Throwable cause
                            cause)))]
      (t/is (= :task-not-configured (:code data))))))
