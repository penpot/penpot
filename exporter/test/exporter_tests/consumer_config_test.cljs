;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.consumer-config-test
  "The worker side of the config: the roles a process serves and how
  many pollers a worker owns."
  (:require
   [app.consumer.config :as ccfg]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

(t/deftest roles-parse-the-env-list
  (t/testing "the default keeps the http surfaces only"
    (t/is (= #{:http} (ccfg/roles "http"))))
  (t/is (= #{:http :worker} (ccfg/roles "http,worker")))
  (t/is (= #{:http :worker} (ccfg/roles "http, worker")))
  (t/testing "the parser does not invent roles from noise"
    (t/is (= #{} (ccfg/roles "")))
    (t/is (= #{:worker} (ccfg/roles ",worker")))))

(t/deftest worker-enabled-reads-the-roles
  (t/is (true? (ccfg/worker-enabled? #{:http :worker})))
  (t/is (true? (ccfg/worker-enabled? #{:worker})))
  (t/is (false? (ccfg/worker-enabled? #{:http}))))

(t/deftest concurrency-is-at-least-one
  (t/testing "the configured value is taken as given"
    (t/is (= 2 (ccfg/concurrency 2)))
    (t/is (= 4 (ccfg/concurrency 4))))
  (t/testing "zero or less is a misconfiguration, floored to one"
    (t/is (= 1 (ccfg/concurrency 0)))
    (t/is (= 1 (ccfg/concurrency -3))))
  (t/testing "an unset value falls back to the default K"
    (t/is (= 2 (ccfg/concurrency nil)))))

(t/deftest queue-key-names-tenant-and-queue
  (t/is (= "penpot.worker.queue:main:exporter"
           (ccfg/queue-key "main"))))
