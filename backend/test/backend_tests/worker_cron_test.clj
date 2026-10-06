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
   [app.util.cron :as ucron]
   [app.worker.cron :as cron]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- get-job
  [cfg job-id]
  (db/get* cfg :job {:id job-id}))

(defn- count-jobs
  [cfg & {:keys [name tenant queue label]}]
  (let [conditions (concat
                    (when name ["name = ?"])
                    (when tenant ["tenant = ?"])
                    (when queue ["queue = ?"])
                    (when label ["label = ?"]))
        sql (str "SELECT count(*) AS n FROM job"
                 (when (seq conditions)
                   (str " WHERE " (str/join " AND " conditions))))
        params (concat
                (when name [name])
                (when tenant [tenant])
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
    :job :session-gc
    :cron "0 0 * * *"}
   {:id :jobs-gc-with-props
    :job :jobs-gc
    :cron "0 0 * * *"
    :props {:min-age 3600000}}])

(t/deftest cron-component-init-registers-and-schedules-entries
  (let [defs    (get th/*system* :app.jobs/defs)
        entries [{:id   :test-cron-init
                  :job :session-gc
                  :cron (ucron/cron "0 0 0 * * ?")}]
        inst    (ig/init-key :app.worker/cron
                             {:app.worker/entries entries
                              ::jobs/defs         defs
                              ::db/pool           th/*pool*})]
    (try
      (t/testing "init schedules futures"
        (t/is (pos? (count @inst))))
      (t/testing "scheduled_task row was upserted"
        (t/is (some? (th/db-get :scheduled-task {:id "test-cron-init"}))))
      (finally
        (ig/halt-key! :app.worker/cron inst)))))

(t/deftest cron-submits-job-with-empty-params-when-entry-has-no-props
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (first entries)]

    ;; Submit a cron job for an entry without :props
    (let [job-id (cron/submit-cron-job cfg entry)]
      ;; Job should be created
      (t/is (some? job-id))

      ;; Job should exist in DB
      (let [row (get-job cfg job-id)]
        (t/is (some? row))
        (t/is (= "session-gc" (:name row)))
        (t/is (= "cron" (:queue row)))
        (t/is (= (cf/get :tenant) (:tenant row)))
        (t/is (= "session-gc-no-props" (:label row)))
        (t/is (nil? (:profile-id row)))

        ;; Props should be empty map after decoding
        (let [props (db/decode-json-pgobject (:params row))]
          (t/is (= {} props)))))))

(t/deftest cron-submits-job-with-props-when-entry-declares-them
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (second entries)]

    ;; Submit a cron job for an entry with :props
    (let [job-id (cron/submit-cron-job cfg entry)]
      ;; Job should be created
      (t/is (some? job-id))

      ;; Job should exist in DB with the props
      (let [row (get-job cfg job-id)]
        (t/is (some? row))
        (t/is (= "jobs-gc" (:name row)))
        (t/is (= "jobs-gc-with-props" (:label row)))
        ;; Props should be decoded to the original map
        (let [props (db/decode-json-pgobject (:params row))]
          (t/is (= {:min-age 3600000} props)))))))

(t/deftest cron-tick-submits-only-when-no-active-instance
  (let [cfg   (make-cfg)
        ;; the entry keeps the canonical keyword task; only the id is a
        ;; string, as normalized by the cron component init. The cron
        ;; expression must be parsed: the tick reschedules itself in
        ;; `finally`
        entry {:id   "session-gc-no-overlap"
               :job :session-gc
               :cron (ucron/cron "0 0 0 * * ?")}]

    ;; the tick claims this row; without it the tick silently skips
    (th/db-insert! :scheduled-task {:id        "session-gc-no-overlap"
                                    :cron-expr "0 0 * * *"})

    ;; execute-cron-task runs on a plain thread; join waits for the tick
    (let [tick! (fn [] (.join ^Thread (#'cron/execute-cron-task cfg entry)))]
      ;; first tick submits exactly one job
      (tick!)
      (t/is (= 1 (count-jobs cfg :name "session-gc"
                             :label "session-gc-no-overlap")))

      ;; second tick with the first still active submits nothing
      (tick!)
      (t/is (= 1 (count-jobs cfg :name "session-gc"
                             :label "session-gc-no-overlap")))

      ;; once the first reaches terminal, the next tick submits again
      (th/db-update! :job {:status "completed"} {:label "session-gc-no-overlap"})
      (tick!)
      (t/is (= 2 (count-jobs cfg :name "session-gc"
                             :label "session-gc-no-overlap"))))))

(t/deftest cron-job-created-on-cron-queue-with-null-profile-id
  (let [cfg     (make-cfg)
        entries (get-cron-entries)
        entry   (first entries)]

    (let [job-id (cron/submit-cron-job cfg entry)
          row    (get-job cfg job-id)]

      ;; the queue column is bare; the tenant lives in its own column
      (t/is (= "cron" (:queue row)))
      (t/is (= (cf/get :tenant) (:tenant row)))

      ;; Verify profile_id is NULL (system job)
      (t/is (nil? (:profile-id row)))

      ;; Verify label is the entry id as string (without colon)
      (t/is (= "session-gc-no-props" (:label row))))))

(t/deftest cron-tick-is-not-blocked-by-another-tenants-active-job
  (let [cfg   (make-cfg)
        entry {:id   "session-gc-shared-db"
               :job :session-gc
               :cron (ucron/cron "0 0 0 * * ?")}]

    (th/db-insert! :scheduled-task {:id        "session-gc-shared-db"
                                    :cron-expr "0 0 * * *"})
    ;; An active job with the same name and label, owned by another
    ;; instance that shares this database. It runs in another tenant's
    ;; queue, so it is none of this instance's business.
    (th/db-insert! :job {:id           (uuid/next)
                         :name         "session-gc"
                         :tenant       "other-tenant"
                         :queue        "cron"
                         :label        "session-gc-shared-db"
                         :status       "running"
                         :params       (db/json {})
                         :scheduled-at (ct/now)
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})

    (.join ^Thread (#'cron/execute-cron-task cfg entry))

    (t/testing "this tenant still submits its own job"
      (t/is (= 1 (count-jobs cfg :name "session-gc"
                             :tenant (cf/get :tenant)
                             :label "session-gc-shared-db"))))
    (t/testing "and the other tenant's row is untouched"
      (t/is (= "running" (:status (th/db-get :job {:name "session-gc"
                                                   :tenant "other-tenant"})))))))
