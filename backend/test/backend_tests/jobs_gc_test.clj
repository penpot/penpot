;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.jobs-gc-test
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.jobs.gc :as gc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [backend-tests.storage-test :refer [configure-storage-backend]]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-storage-object!
  []
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))]
    (sto/put-object! storage {::sto/content (sto/content "content")
                              :content-type "text/plain"})))

(defn- mk-job!
  [{:keys [status profile-id resource-id expires-at modified-at]
    :or   {status "new"}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id           id
                         :name         "test-job"
                         :queue        "test:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       status
                         :profile-id   profile-id
                         :resource-id  resource-id
                         :expires-at   expires-at
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (or modified-at (ct/now))})
    id))

(t/deftest gc-deletes-expired-jobs-and-touches-their-resources
  (let [old-object   (mk-storage-object!)
        live-object  (mk-storage-object!)
        expired-id   (uuid/next)
        live-id      (uuid/next)]
    (th/db-insert! :job {:id           expired-id
                         :name         "test-job"
                         :queue        "test:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "completed"
                         :resource-id  (:id old-object)
                         :expires-at   (ct/in-past {:minutes 5})
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    (th/db-insert! :job {:id           live-id
                         :name         "test-job"
                         :queue        "test:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "running"
                         :resource-id  (:id live-object)
                         :expires-at   (ct/in-future {:minutes 5})
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})

    (th/run-task! :jobs-gc {})

    (t/is (nil? (th/db-get :job {:id expired-id} :id :status)))
    (t/is (some? (th/db-get :job {:id live-id} :id :status)))
    (t/is (some? (:touched-at (th/db-get :storage-object {:id (:id old-object)}
                                         :id :touched-at))))
    (t/is (nil? (:touched-at (th/db-get :storage-object {:id (:id live-object)}
                                        :id :touched-at)))))

  ;; a row without resource is deleted without touching anything
  (let [expired-id (uuid/next)]
    (th/db-insert! :job {:id           expired-id
                         :name         "test-job"
                         :queue        "test:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "new"
                         :expires-at   (ct/in-past {:minutes 5})
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    (th/run-task! :jobs-gc {})
    (t/is (nil? (th/db-get :job {:id expired-id} :id :status)))))

(t/deftest gc-params-accept-string-min-age
  (t/testing "duration strings validate like durations and millis"
    (t/is ((sm/validator gc/schema:jobs-gc-params) {:min-age "1h"}))
    (t/is ((sm/validator gc/schema:jobs-gc-params) {:min-age 3600000}))
    (t/is ((sm/validator gc/schema:jobs-gc-params) {:min-age (ct/duration {:hours 1})})))
  (t/testing "string min-age runs like a duration"
    (t/is (map? (th/run-task! :jobs-gc {:min-age "1h"})))))

(t/deftest gc-retention-deletes-old-internal-terminal-rows
  (let [profile (th/create-profile* 1 {})
        ;; old internal terminal row -> deleted
        old-id  (uuid/next)]
    (th/db-insert! :job {:id           old-id
                         :name         "test-job"
                         :queue        "test:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "completed"
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/in-past {:days 10})})
    ;; recent internal terminal row: inside the retention window, survives
    (let [recent-id (uuid/next)]
      (th/db-insert! :job {:id           recent-id
                           :name         "test-job"
                           :queue        "test:default"
                           :props        (db/json {})
                           :priority     100
                           :max-retries  3
                           :retry-num    0
                           :status       "completed"
                           :scheduled-at (ct/now)
                           :created-at   (ct/now)
                           :modified-at  (ct/now)})
      ;; non-terminal internal row: never swept by retention, survives
      (let [running-id (uuid/next)]
        (th/db-insert! :job {:id           running-id
                             :name         "test-job"
                             :queue        "test:default"
                             :props        (db/json {})
                             :priority     100
                             :max-retries  3
                             :retry-num    0
                             :status       "running"
                             :scheduled-at (ct/now)
                             :created-at   (ct/now)
                             :modified-at  (ct/in-past {:days 10})})
        ;; old USER terminal row (profile set): retention does not sweep
        ;; the user ledger, survives
        (let [user-id (uuid/next)]
          (th/db-insert! :job {:id           user-id
                               :name         "test-job"
                               :queue        "test:default"
                               :props        (db/json {})
                               :priority     100
                               :max-retries  3
                               :retry-num    0
                               :status       "completed"
                               :profile-id   (:id profile)
                               :scheduled-at (ct/now)
                               :created-at   (ct/now)
                               :modified-at  (ct/in-past {:days 10})})
          (th/run-task! :jobs-gc {})

          (t/is (nil? (th/db-get :job {:id old-id} :id :status)))
          (t/is (= 3 (count (th/db-query :job {:queue "test:default"}))))
          (t/is (some? (th/db-get :job {:id recent-id} :id :status)))
          (t/is (some? (th/db-get :job {:id running-id} :id :status)))
          (t/is (some? (th/db-get :job {:id user-id} :id :status))))))))

(t/deftest gc-retention-touches-resources-of-retained-rows
  (let [object  (mk-storage-object!)
        job-id  (uuid/next)]
    (th/db-insert! :job {:id           job-id
                         :name         "test-job"
                         :queue        "test:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "failed"
                         :resource-id  (:id object)
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/in-past {:days 10})})
    (th/run-task! :jobs-gc {})
    (t/is (nil? (th/db-get :job {:id job-id} :id :status)))
    (t/is (some? (:touched-at (th/db-get :storage-object {:id (:id object)}
                                         :id :touched-at))))))

(t/deftest gc-deletes-expired-jobs-in-bounded-batches
  (let [total 2500]
    (th/db-exec! [(str "INSERT INTO job (name, queue, status, expires_at) "
                       "SELECT 'test-job', 'test:default', 'completed', "
                       "now() - interval '1 hour' "
                       "FROM generate_series(1, " total ")")])
    ;; spread a few resources across batches so every batch touches
    (let [objects (repeatedly 3 mk-storage-object!)]
      (doseq [[object n] (map vector objects (range))]
        (th/db-exec! ["UPDATE job SET resource_id = ?
                        WHERE id = (SELECT id FROM job OFFSET ? LIMIT 1)"
                      (:id object) (* n 1000)]))
      (let [{:keys [deleted-expired touched-expired]} (th/run-task! :jobs-gc {})]
        (t/is (= total deleted-expired))
        (t/is (= (count objects) touched-expired))
        (t/is (= 0 (count (th/db-query :job {:queue "test:default"}))))
        (doseq [object objects]
          (t/is (some? (:touched-at (th/db-get :storage-object {:id (:id object)}
                                               :id :touched-at)))))))))

(t/deftest gc-commits-each-batch-separately
  (let [total 2500
        calls (atom 0)
        orig  @#'gc/touch-resources!]
    (th/db-exec! [(str "INSERT INTO job (name, queue, status, expires_at) "
                       "SELECT 'test-job', 'test:default', 'completed', "
                       "now() - interval '1 hour' "
                       "FROM generate_series(1, " total ")")])
    ;; blow up on the 3rd batch: earlier batches must stay committed
    (alter-var-root #'gc/touch-resources!
                    (constantly (fn [conn ids]
                                  (when (= 3 (swap! calls inc))
                                    (throw (ex-info "boom" {})))
                                  (orig conn ids))))
    (try
      (t/is (thrown? Exception (th/run-task! :jobs-gc {})))
      (t/is (= 500 (count (th/db-query :job {:queue "test:default"}))))
      (finally
        (alter-var-root #'gc/touch-resources! (constantly orig))))
    ;; the next tick completes the sweep
    (let [{:keys [deleted-expired]} (th/run-task! :jobs-gc {})]
      (t/is (= 500 deleted-expired))
      (t/is (zero? (count (th/db-query :job {:queue "test:default"})))))))

(t/deftest gc-expiration-skips-active-rows
  (let [running-id (uuid/next)
        retry-id   (uuid/next)]
    (doseq [[id status] [[running-id "running"] [retry-id "retry"]]]
      (th/db-insert! :job {:id           id
                           :name         "test-job"
                           :queue        "test:default"
                           :props        (db/json {})
                           :priority     100
                           :max-retries  3
                           :retry-num    0
                           :status       status
                           :expires-at   (ct/in-past {:minutes 5})
                           :scheduled-at (ct/now)
                           :created-at   (ct/now)
                           :modified-at  (ct/now)}))
    (th/run-task! :jobs-gc {})
    (t/testing "active rows survive expiration"
      (t/is (some? (th/db-get :job {:id running-id} :id :status)))
      (t/is (some? (th/db-get :job {:id retry-id} :id :status))))))
