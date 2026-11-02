;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.worker-test
  (:require
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.main :as main]
   [app.metrics :as-alias mtx]
   [app.msgbus :as mbus]
   [app.worker :as wrk]
   [app.worker.cron :as wcron]
   [app.worker.dispatcher :as wdisp]
   [app.worker.runner :as wrkr]
   [clojure.test :as t]
   [integrant.core :as ig]))

;; The dispatcher's push and the runner's pop agree on the Redis list
;; only through this helper, so the test helpers use it too. That
;; keeps producer and consumer in sync by construction, but it also
;; means no other test would catch a malformed shape. This test pins
;; the physical format instead.

(t/deftest queue-key-pins-the-physical-redis-shape
  (t/testing "tenant and queue compose the documented key"
    (t/is (= "penpot.worker.queue:acme:default"
             (wrk/queue-key "acme" :default))))
  (t/testing "a string queue gives the same key as a keyword one"
    (t/is (= "penpot.worker.queue:acme:default"
             (wrk/queue-key "acme" "default"))))
  (t/testing "hyphens travel through untouched"
    (t/is (= "penpot.worker.queue:my-tenant:daily-cleanup"
             (wrk/queue-key "my-tenant" :daily-cleanup)))))

;; Booting the backend asserts these integrant keys, and assert-key
;; compiles the schema first: a ref with no registered schema crashes
;; the boot with :malli.core/invalid-schema instead of asserting. The
;; component schemas lean on the shared registry (db pool, metrics,
;; redis client, msgbus, job defs), so the registry dependencies must
;; be loaded for them to compile, like the full system does.
(t/deftest worker-component-schemas-resolve-every-ref
  (t/testing "cron, dispatcher and runner schemas compile"
    (t/is (sm/schema? (sm/schema ::mbus/msgbus)))
    (doseq [s [@#'wcron/schema:params
               @#'wdisp/schema:dispatcher
               @#'wrkr/schema:params]]
      (t/is (sm/schema? (sm/schema s))))))

(t/deftest every-worker-consumer-component-is-known-to-the-test-system
  ;; The test system drops the consumer loops by key, so a new one must be
  ;; added there too or it drains audit_log in the background. Deriving the
  ;; list from the config (every worker key named `/consumer`) makes a
  ;; third consumer fail here, not as a flaky test elsewhere.
  (let [keys (->> (keys main/worker-config)
                  (filter #(and (keyword? %) (= "consumer" (name %))))
                  (into #{}))]
    (t/is (= #{:app.loggers.audit.consumers.nexus/consumer
               :app.loggers.audit.consumers.admin-console/consumer}
             keys)
          (str "update this set and helpers/init-system's dissoc: " keys))))

(t/deftest audit-log-gc-cron-is-gated-by-its-own-flag
  (t/testing "the GC entry is present only with :audit-log-gc"
    (with-redefs [cf/flags #{:audit-log-gc}]
      (t/is (some #(= :audit-log-gc (:job %)) (main/worker-cron-entries))))
    (with-redefs [cf/flags #{:audit-log}]
      (t/is (not (some #(= :audit-log-gc (:job %))
                       (main/worker-cron-entries)))))
    (with-redefs [cf/flags #{}]
      (t/is (not (some #(= :audit-log-gc (:job %))
                       (main/worker-cron-entries)))))))

(t/deftest the-binfile-runner-is-wired
  (let [cfg (get main/worker-config [:app.main/binfile ::wrk/runner])]

    (t/is (some? cfg))

    (t/testing "the heavy work of a user job does not share the default runner"
      (t/is (= :binfile (::wrk/queue cfg)))
      (t/is (= (cf/get :worker-binfile-parallelism 1) (::wrk/parallelism cfg))))

    (t/testing "a job of a profile cannot store its events without a msgbus"
      (t/is (contains? cfg ::mbus/msgbus)))

    (t/testing "and the runner resolves jobs, metrics and the pool"
      (t/is (contains? cfg ::jobs/defs))
      (t/is (contains? cfg ::mtx/metrics))
      (t/is (contains? cfg ::db/pool)))))

(t/deftest the-audit-consumers-are-wired
  ;; Each consumer is a loop in a thread: the test system drops the two
  ;; components by name, so a rename that leaves `helpers.clj` behind would
  ;; start real loops in the background. The components live in the worker
  ;; config only (a web instance does not drain the audit log) and their
  ;; `ig/init-key`/`ig/halt-key!` methods must be registered.
  (doseq [[k deps] [[:app.loggers.audit.consumers.nexus/consumer
                     [::db/pool :app.http.client/client
                      :app.setup/shared-keys ::mtx/metrics]]
                    [:app.loggers.audit.consumers.admin-console/consumer
                     [::db/pool :app.nitrate/client ::mtx/metrics]]]]
    (t/testing (str k)
      (let [cfg (get main/worker-config k)]
        (t/is (some? cfg))
        (t/is (nil? (get main/system-config k)))
        (t/is (some? (get-method ig/init-key k)))
        (t/is (some? (get-method ig/halt-key! k)))
        (t/testing "the loop resolves its dependencies"
          (doseq [dep deps]
            (t/is (contains? cfg dep) (str k " misses " dep))))))))
