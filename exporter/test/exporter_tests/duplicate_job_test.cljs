;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.duplicate-job-test
  "Duplicate export job prevention on the shared export queue.

  Requirements under test:
    R1  Each export job has a unique ID.
    R2  A new job can be added to the queue successfully.
    R3  Adding a job with an existing ID does not create a duplicate.
    R4  Different jobs can be added normally.
    R5  The queue holds only one entry per job ID.
    R6  Invalid or null job IDs are rejected.

  Runs against a real redis, only when `PENPOT_REDIS_URI` is set; each test
  is skipped otherwise. Every test starts from an empty queue."
  (:require
   [app.common.uuid :as uuid]
   [app.instance :as instance]
   [app.jobs :as jobs]
   [app.jobs.queue :as queue]
   [app.redis :as redis]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(def ^:private redis-uri
  (unchecked-get (.-env js/process) "PENPOT_REDIS_URI"))

(t/use-fixtures :once
  {:before (fn [] (when redis-uri (redis/init)))
   :after  (fn [] (when redis-uri (redis/stop)))})

;; --- Helpers

(defn- reset-keys!
  []
  (p/do
    (redis/del! (queue/queue-key))
    (redis/del! (queue/processing-key instance/id))
    (redis/del! (instance/registry-key))))

(defn- run
  "Runs the promise-returning `f` against a clean queue, then calls `done`.
  A rejection fails the test instead of hanging it."
  [done f]
  (if-not redis-uri
    (do (println "    skipped: PENPOT_REDIS_URI not set")
        (done))
    (p/handle (p/do (reset-keys!) (f))
              (fn [_ cause]
                (when cause
                  (t/is (nil? cause) (str "unexpected rejection: " (ex-message cause))))
                (done)
                nil))))

(defn- attempt
  "Resolves to `{:ok value}` or `{:error cause}`, so a test can assert on
  either outcome. Never rejects, also when `f` throws synchronously."
  [f]
  (let [result (try (f) (catch :default cause (p/rejected cause)))]
    (->> (p/promise result)
         (p/fmap (fn [value] {:ok value}))
         (p/merr (fn [cause] (p/resolved {:error cause}))))))

(defn- job-attrs
  []
  {:profile-id (uuid/next)
   :cmd :export-shapes
   :backend "browser"
   :total 1
   :name "test"
   :resource-id (uuid/next)})

(def ^:private payload
  {:cmd :export-shapes
   :params {:file-id (uuid/next) :type :png}
   :auth-token "session-token"})

(defn- new-job!
  "A job recorded the way an `api` process records it, not yet queued."
  []
  (jobs/create-queued! (job-attrs)))

(defn- queued-ids
  []
  (redis/lrange (queue/queue-key)))

(defn- occurrences
  [ids job-id]
  (count (filter #(= (str job-id) %) ids)))

;; --- R1: unique ids

(t/deftest each-new-job-gets-its-own-unique-id
  (t/testing "R1: two jobs created back to back have distinct UUID ids"
    (t/async done
      (run done
           (fn []
             (p/let [a (new-job!)
                     b (new-job!)]
               (t/is (uuid? (:id a)))
               (t/is (uuid? (:id b)))
               (t/is (not= (:id a) (:id b)))))))))

;; --- R2: a valid new job is added

(t/deftest a-valid-new-job-is-added-to-the-queue
  (t/testing "R2: enqueuing a new job puts its id on the queue once and stores its payload"
    (t/async done
      (run done
           (fn []
             (p/let [job    (new-job!)
                     result (attempt #(queue/enqueue! job payload))
                     ids    (queued-ids)
                     depth  (queue/depth)
                     stored (queue/payload (:id job))]
               (t/is (nil? (:error result)) "a new job must not be rejected")
               (t/is (= [(str (:id job))] ids))
               (t/is (= 1 depth))
               (t/is (= :export-shapes (:cmd stored)))))))))

;; --- R3: the same id twice is not duplicated

(t/deftest enqueuing-the-same-job-twice-keeps-a-single-entry
  (t/testing "R3: a second enqueue of the same job leaves one entry, whether it resolves or rejects"
    (t/async done
      (run done
           (fn []
             (p/let [job   (new-job!)
                     _     (queue/enqueue! job payload)
                     _     (attempt #(queue/enqueue! job payload))
                     ids   (queued-ids)
                     depth (queue/depth)]
               (t/is (= 1 (occurrences ids (:id job))))
               (t/is (= 1 depth))))))))

(t/deftest a-separate-request-reusing-an-existing-id-is-not-queued-again
  (t/testing "R3 edge: a different job map that carries an id already queued is not added"
    (t/async done
      (run done
           (fn []
             (p/let [job     (new-job!)
                     _       (queue/enqueue! job payload)
                     _       (attempt #(queue/enqueue! {:id (:id job)}
                                                       (assoc-in payload [:params :type] :pdf)))
                     ids     (queued-ids)
                     stored  (queue/payload (:id job))]
               (t/is (= [(str (:id job))] ids))
               (t/is (some? stored) "the queued job must still have a payload to run with")))))))

(t/deftest concurrent-enqueues-of-the-same-job-keep-a-single-entry
  (t/testing "R3 edge: five enqueues of one job sent at the same time leave one entry"
    (t/async done
      (run done
           (fn []
             (p/let [job (new-job!)
                     _   (p/all (repeatedly 5 #(attempt (fn [] (queue/enqueue! job payload)))))
                     ids (queued-ids)]
               (t/is (= 1 (occurrences ids (:id job))))
               (t/is (= 1 (count ids)))))))))

(t/deftest a-job-already-claimed-by-a-worker-is-not-queued-again
  (t/testing "R3 edge: once a worker claimed a job, enqueuing it again must not hand it to a second worker"
    (t/async done
      (run done
           (fn []
             (p/let [job     (new-job!)
                     _       (queue/enqueue! job payload)
                     claimed (queue/claim! 1)
                     _       (attempt #(queue/enqueue! job payload))
                     ids     (queued-ids)
                     held    (redis/lrange (queue/processing-key instance/id))]
               (t/is (= (str (:id job)) claimed))
               (t/is (= 0 (occurrences ids (:id job)))
                     "the claimed job must not be waiting on the queue a second time")
               (t/is (= [(str (:id job))] held))))))))

;; --- R4: different jobs are accepted

(t/deftest different-jobs-are-all-accepted-in-order
  (t/testing "R4: three distinct jobs are all queued, first in first out"
    (t/async done
      (run done
           (fn []
             (p/let [a  (new-job!)
                     b  (new-job!)
                     c  (new-job!)
                     ra (attempt #(queue/enqueue! a payload))
                     rb (attempt #(queue/enqueue! b payload))
                     rc (attempt #(queue/enqueue! c payload))
                     ids (queued-ids)]
               (t/is (every? (comp nil? :error) [ra rb rc]))
               (t/is (= [(str (:id a)) (str (:id b)) (str (:id c))] ids))))))))

;; --- R5: one entry per id

(t/deftest queue-holds-one-entry-per-id-after-mixed-adds
  (t/testing "R5: adding a, b, a, c, b leaves exactly a, b, c in arrival order"
    (t/async done
      (run done
           (fn []
             (p/let [a   (new-job!)
                     b   (new-job!)
                     c   (new-job!)
                     _   (p/loop [[job & more] [a b a c b]]
                           (when job
                             (p/let [_ (attempt #(queue/enqueue! job payload))]
                               (p/recur more))))
                     ids (queued-ids)]
               (t/is (= [(str (:id a)) (str (:id b)) (str (:id c))] ids))
               (t/is (every? #(= 1 %) (vals (frequencies ids))))
               (t/is (= 3 (count ids)))))))))

;; --- R6: invalid ids are rejected

(defn- assert-rejected-and-queue-untouched
  [label job]
  (p/let [result (attempt #(queue/enqueue! job payload))
          ids    (queued-ids)]
    (t/is (some? (:error result)) (str label " must be rejected"))
    (t/is (= [] ids) (str label " must not leave anything on the queue"))))

(t/deftest a-null-job-id-is-rejected
  (t/testing "R6: a job whose :id is nil is rejected and nothing is queued"
    (t/async done
      (run done
           (fn [] (assert-rejected-and-queue-untouched "nil id" {:id nil}))))))

(t/deftest a-job-without-an-id-is-rejected
  (t/testing "R6: a job map with no :id key is rejected and nothing is queued"
    (t/async done
      (run done
           (fn [] (assert-rejected-and-queue-untouched "missing id" {}))))))

(t/deftest a-blank-job-id-is-rejected
  (t/testing "R6: an empty-string id is rejected and nothing is queued"
    (t/async done
      (run done
           (fn [] (assert-rejected-and-queue-untouched "blank id" {:id ""}))))))

(t/deftest a-non-uuid-job-id-is-rejected
  (t/testing "R6: an id that is not a UUID is rejected and nothing is queued"
    (t/async done
      (run done
           (fn [] (assert-rejected-and-queue-untouched "non-uuid id" {:id "not-a-uuid"}))))))

(t/deftest a-rejected-id-leaves-existing-jobs-in-place
  (t/testing "R6 edge: rejecting an invalid id does not disturb a job already queued"
    (t/async done
      (run done
           (fn []
             (p/let [job    (new-job!)
                     _      (queue/enqueue! job payload)
                     result (attempt #(queue/enqueue! {:id nil} payload))
                     ids    (queued-ids)]
               (t/is (some? (:error result)))
               (t/is (= [(str (:id job))] ids))))))))
