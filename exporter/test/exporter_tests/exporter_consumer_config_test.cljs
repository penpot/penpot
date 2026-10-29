;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-consumer-config-test
  "The consumer knobs of the new tree: how many jobs run at once and
  which queue they come from."
  (:require
   [cljs.test :as t :include-macros true]
   [exporter.consumer.config :as ccfg]))

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
