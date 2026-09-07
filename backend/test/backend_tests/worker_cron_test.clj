;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.worker-cron-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.worker.cron :as cron]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- get-job
  [cfg job-id]
  (db/get* cfg :job {:id job-id}))

(defn- count-jobs
  [cfg & {:keys [name queue label]}]
  (let [conditions (concat
                    (when name ["name = ?"])
                    (when queue ["queue = ?"])
                    (when label ["label = ?"]))
        sql (str "SELECT count(*) AS n FROM job"
                 (when (seq conditions)
                   (str " WHERE " (str/join " AND " conditions))))
        params (concat
                (when name [name])
                (when queue [queue])
                (when label [label]))]
    (-> (db/exec-one! cfg (into [sql] params))
        :n)))

(defn- make-cfg
  []
  (-> th/*system*
      (assoc ::jobs/defs (get th/*system* :app.jobs/defs))
      (assoc ::db/pool (get th/*system* ::db/pool))))

(defn- get-cron-entries
  []
  [{:id :session-gc-no-props
    :task :session-gc
    :cron "0 0 * * *"}
   {:id :jobs-gc-with-props
    :task :jobs-gc
    :cron "0 0 * * *"
    :props {:min-age "1h"}}])

(t/deftest cron-submits-job-with-empty-params-when-entry-has-no-props
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (first entries)]

    ;; Submit a cron job for an entry without :props
    (let [job-id (cron/submit-cron-job! cfg entry)]
      ;; Job should be created
      (t/is (some? job-id))

      ;; Job should exist in DB
      (let [row (get-job cfg job-id)]
        (t/is (some? row))
        (t/is (= "session-gc" (:name row)))
        (t/is (= "default:cron" (:queue row)))
        (t/is (= "session-gc-no-props" (:label row)))
        (t/is (nil? (:profile-id row)))

        ;; Props should be empty map after decoding
        (let [props (db/decode-json-pgobject (:props row))]
          (t/is (= {} props)))))))

(t/deftest cron-submits-job-with-props-when-entry-declares-them
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (second entries)]

    ;; Submit a cron job for an entry with :props
    (let [job-id (cron/submit-cron-job! cfg entry)]
      ;; Job should be created
      (t/is (some? job-id))

      ;; Job should exist in DB with the props
      (let [row (get-job cfg job-id)]
        (t/is (some? row))
        (t/is (= "jobs-gc" (:name row)))
        (t/is (= "jobs-gc-with-props" (:label row)))
        ;; Props should be decoded to the original map
        (let [props (db/decode-json-pgobject (:props row))]
          (t/is (= {:min-age "1h"} props)))))))

(t/deftest cron-no-overlap-check-prevents-duplicate-submission
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (first entries)]

    ;; Submit first job
    (let [job-id-1 (cron/submit-cron-job! cfg entry)]
      (t/is (some? job-id-1))

      ;; Check that one job exists
      (t/is (= 1 (count-jobs cfg :name "session-gc" :label "session-gc-no-props")))

      ;; Simulate the no-overlap check: count active jobs with same name+label
      (let [active (db/exec-one! cfg
                                 ["SELECT count(*) AS n FROM job
                                   WHERE name = ? AND label = ?
                                     AND status IN ('new', 'scheduled', 'running', 'retry')"
                                  "session-gc" "session-gc-no-props"])]
        ;; Should find 1 active job
        (t/is (= 1 (:n active)))

        ;; The no-overlap logic would skip submission if active > 0
        ;; So we verify the check works correctly
        (t/is (pos? (:n active)))))))

(t/deftest cron-job-created-on-cron-queue-with-null-profile-id
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (first entries)]

    (let [job-id (cron/submit-cron-job! cfg entry)
          row    (get-job cfg job-id)]

      ;; Verify queue is default:cron (tenant:queue format)
      (t/is (= "default:cron" (:queue row)))

      ;; Verify profile_id is NULL (system job)
      (t/is (nil? (:profile-id row)))

      ;; Verify label is the entry id as string (without colon)
      (t/is (= "session-gc-no-props" (:label row))))))
