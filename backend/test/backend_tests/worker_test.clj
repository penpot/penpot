;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.worker-test
  (:require
   [app.common.schema :as sm]
   [app.msgbus :as mbus]
   [app.worker :as wrk]
   [app.worker.cron :as wcron]
   [app.worker.dispatcher :as wdisp]
   [app.worker.runner :as wrkr]
   [clojure.test :as t]))

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
