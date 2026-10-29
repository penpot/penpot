;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.media-runner-test
  (:require
   [app.config :as cf]
   [app.db :as-alias db]
   [app.jobs :as jobs]
   [app.main :as main]
   [app.media.jobs :as mj]
   [app.metrics :as-alias mtx]
   [app.msgbus :as-alias mbus]
   [app.redis :as-alias rds]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

;; The worker-config def evaluates its `cf/get` calls at namespace load, so
;; these tests read what the deployment would wire. Queues are literals and
;; parallelism has a default, so the assertions do not depend on the devenv
;; environment.

(t/use-fixtures :once th/state-init)

(defn- runner
  [id]
  (get main/worker-config [id ::wrk/runner]))

(t/deftest media-runners-declare-distinct-queues-and-their-parallelism
  (let [fast (runner ::main/media-fast)
        slow (runner ::main/media)]

    (t/is (some? fast) "the fast queue has a runner")
    (t/is (some? slow) "the slow queue has a runner")

    (t/testing "the synchronous jobs and the asynchronous ones do not share a queue"
      (t/is (= :media-local-fast (::wrk/queue fast)))
      (t/is (= :media-local (::wrk/queue slow)))
      (t/is (not= (::wrk/queue fast) (::wrk/queue slow))))

    (t/testing "both runners have their own concurrency"
      (t/is (pos? (::wrk/parallelism fast)))
      (t/is (pos? (::wrk/parallelism slow))))

    (t/testing "a job of one queue is not visible from the other"
      (let [tenant (cf/get :tenant)]
        (t/is (not= (wrk/queue-key tenant (::wrk/queue fast))
                    (wrk/queue-key tenant (::wrk/queue slow))))))))

(t/deftest media-runners-are-wired-with-everything-they-need
  (doseq [runner [(runner ::main/media-fast) (runner ::main/media)]]
    (t/is (some? (::wrk/tenant runner)))
    (t/is (some? (::jobs/defs runner)))
    (t/is (some? (::mtx/metrics runner)))
    (t/is (some? (::db/pool runner)))
    (t/is (some? (::rds/client runner)))
    (t/is (some? (::mbus/msgbus runner)))))

(t/deftest the-local-queue-pair-matches-the-media-runners
  (with-redefs [cf/flags (disj cf/flags :remote-media-processing)]
    (let [{:keys [fast slow]} (mj/queue-pair)]
      (t/is (= (::wrk/queue (runner ::main/media-fast)) fast))
      (t/is (= (::wrk/queue (runner ::main/media)) slow)))))
