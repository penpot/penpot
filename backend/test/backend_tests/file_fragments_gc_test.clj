;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-fragments-gc-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-change-refs :as refs]
   [app.features.file-changes :as fchanges]
   [app.jobs :as jobs]
   [app.tasks.file-fragments-gc :as gc]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- fixture-file
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (th/db-exec-one! ["UPDATE file SET modified_at = ? WHERE id = ?"
                      (ct/in-past "72h") (:id file)])
    file))

(defn- fragment
  [file-id]
  (let [id (uuid/next)]
    (th/db-insert! :file-data {:file-id file-id :id id
                               :type "fragment" :backend "db"
                               :data (blob/encode {})})
    id))

(defn- marked?
  [file-id fragment-id]
  (some? (:deleted-at (th/db-exec-one!
                       ["SELECT deleted_at FROM file_data WHERE file_id = ? AND id = ?"
                        file-id fragment-id]))))

(defn- get-jobs
  [name]
  (th/db-exec! ["SELECT * FROM job WHERE name = ?" name]))

(defn- save-main
  [cfg file-id fragment-id]
  (db/exec-one! cfg
                ["UPDATE file_data SET data = ? WHERE file_id = ? AND id = ?"
                 (blob/encode {:components (pmap/create fragment-id {})})
                 file-id file-id]))

(defn- insert-snapshot
  [cfg {:keys [id revn]} snapshot-id fragment-id]
  (db/exec-one! cfg ["SELECT id FROM file WHERE id = ? FOR UPDATE" id])
  (db/insert! cfg :file-change {:id snapshot-id :file-id id
                                :kind fchanges/snapshot-kind
                                :revn revn :label "snapshot"
                                :deleted-at (ct/in-future "24h")})
  (db/insert! cfg :file-data {:file-id id :id snapshot-id
                              :type "snapshot" :backend "db"
                              :data (blob/encode {:components (pmap/create fragment-id {})})}))

(defn- run-gc
  [file-id]
  (th/run-task! :file-fragments-gc {:file-id file-id}))

(t/deftest fragment-gc-result-is-validated-and-decoded
  (let [{file-id :id} (fixture-file)
        cfg    th/*system*
        job-id (jobs/submit cfg {::jobs/name :file-fragments-gc
                                 ::jobs/params {:file-id file-id}})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    (t/testing "a result without a marked count does not complete the job"
      (let [failure (ex/try! (jobs/complete cfg :job-id job-id
                                            :result {:status :complete :file-id file-id}))]
        (t/is (= :invalid-result (:code (ex-data failure))))
        (t/is (= "running" (:status (jobs/get-job cfg job-id))))))
    (t/testing "an invalid result does not complete the job"
      (let [failure (ex/try! (jobs/complete cfg :job-id job-id
                                            :result {:status :complete :file-id file-id :marked -1}))]
        (t/is (= :invalid-result (:code (ex-data failure))))
        (t/is (= "running" (:status (jobs/get-job cfg job-id))))))
    (t/testing "a stored result keeps its keyword status and UUID on read"
      (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
        (t/is (= 1 (jobs/complete cfg :job-id job-id :result (run-gc file-id))))
        (t/is (= {:status :complete :file-id file-id :marked 0}
                 (:result (jobs/get-job cfg job-id))))))))

(t/deftest marks-only-unreferenced-fragments-when-the-index-is-complete
  (let [{:keys [id] :as file} (fixture-file)
        main-ref    (fragment id)
        history-ref (fragment id)
        orphan      (fragment id)
        snapshot-id (uuid/next)]
    (save-main th/*system* id main-ref)
    (db/tx-run! th/*system* insert-snapshot file snapshot-id history-ref)
    (db/run! th/*system* refs/index-change id snapshot-id)
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (run-gc id)
      (run-gc id))
    (t/is (marked? id orphan))
    (t/is (not (marked? id main-ref)))
    (t/is (not (marked? id history-ref)))
    (t/is (empty? (get-jobs "file-fragments-gc")))))

(t/deftest a-live-file-without-a-live-main-fails-without-marking
  (let [{:keys [id]} (fixture-file)
        candidate   (fragment id)]
    (th/db-update! :file-data {:deleted-at (ct/now)} {:file-id id :id id})
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (let [failure (ex/try! (run-gc id))]
        (t/is (= {:type :internal :code :missing-main-file-data :file-id id}
                 (select-keys (ex-data failure) [:type :code :file-id])))))
    (t/is (not (marked? id candidate)))))

(t/deftest a-live-main-without-a-payload-fails-without-marking
  (let [{:keys [id]} (fixture-file)
        candidate   (fragment id)]
    (th/db-update! :file-data {:data nil} {:file-id id :id id})
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (let [failure (ex/try! (run-gc id))]
        (t/is (= {:type :internal :code :invalid-main-payload :file-id id}
                 (select-keys (ex-data failure) [:type :code :file-id])))))
    (t/is (not (marked? id candidate)))))

(t/deftest a-job-for-a-gone-file-returns-gone-without-rescheduling
  (let [file-id (uuid/next)]
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (t/is (= {:status :gone :file-id file-id :marked 0} (run-gc file-id))))
    (t/is (empty? (get-jobs "file-fragments-gc")))))

(t/deftest a-completed-full-preparation-schedules-another-run
  (let [{:keys [id]} (fixture-file)
        fragments   [(fragment id) (fragment id) (fragment id)]]
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [gc/max-fragment-candidates-per-run 2]
        (t/is (= {:status :complete :file-id id :marked 2} (run-gc id)))))
    (t/is (= 2 (count (filter #(marked? id %) fragments))))
    (t/is (= [(str id)] (mapv :label (get-jobs "file-fragments-gc"))))))

(t/deftest missing-refs-queue-the-index-scheduler-and-retry-until-covered
  (let [{:keys [id] :as file} (fixture-file)
        orphan      (fragment id)
        history-ref (fragment id)
        snapshot-id (uuid/next)]
    (db/tx-run! th/*system* insert-snapshot file snapshot-id history-ref)
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (dotimes [_ 2]
        (let [failure (ex/try! (run-gc id))]
          (t/is (= ::wrk/retry (:type (ex-data failure))))
          (t/is (= :incomplete-file-change-refs (:code (ex-data failure))))
          (t/is (= (ct/duration "1m") (:delay (ex-data failure))))))
      (t/is (not (marked? id orphan)))
      (t/is (= 1 (count (get-jobs "index-file-change-refs-scheduler"))))
      (t/is (empty? (get-jobs "index-file-change-refs")))
      (t/is (= {:file-id id :scheduled 1}
               (th/run-task! :index-file-change-refs-scheduler {:file-id id})))
      (let [failure (ex/try! (run-gc id))]
        (t/is (= ::wrk/retry (:type (ex-data failure))))
        (t/is (= :incomplete-file-change-refs (:code (ex-data failure)))))
      (t/is (not (marked? id orphan)))
      (t/is (= 1 (count (get-jobs "index-file-change-refs"))))
      (th/run-task! :index-file-change-refs {:file-id id :change-id snapshot-id})
      (t/is (empty? (refs/get-missing-change-ids th/*system* id 1)))
      (t/is (empty? (get-jobs "file-fragments-gc")))
      (run-gc id))
    (t/is (marked? id orphan))
    (t/is (not (marked? id history-ref)))))

(t/deftest a-snapshot-committed-between-preparation-and-file-lock-invalidates-the-lot
  (let [{:keys [id] :as file} (fixture-file)
        main-ref    (fragment id)
        orphan      (fragment id)
        snapshot-id (uuid/next)
        prepare     (deref #'gc/prepare-lot)
        prepared    (promise)
        proceed     (promise)]
    (save-main th/*system* id main-ref)
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [gc/prepare-lot (fn [cfg file-id]
                                     (let [lot (prepare cfg file-id)]
                                       (deliver prepared lot)
                                       (when-not (deref proceed 10000 nil)
                                         (throw (ex-info "test did not release GC" {})))
                                       lot))]
        (let [run (atom nil)]
          (try
            (db/tx-run! th/*system*
                        (fn [cfg]
                          (insert-snapshot cfg file snapshot-id main-ref)
                          (reset! run (future (run-gc id)))
                          (t/is (contains? (set (:ids (deref prepared 10000 nil))) orphan))))
            (finally (deliver proceed true)))
          (t/is (= :stale (:status (deref @run 10000 nil)))))))
    (t/is (not (marked? id orphan)))
    (t/is (= 1 (count (get-jobs "file-fragments-gc"))))))

(t/deftest a-snapshot-committed-after-the-coverage-check-invalidates-the-lot
  (let [{:keys [id] :as file} (fixture-file)
        candidate             (fragment id)
        snapshot-id           (uuid/next)
        get-main-fragment-ids (deref #'gc/get-main-fragment-ids)]
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [gc/get-main-fragment-ids (fn [cfg main]
                                               (db/tx-run! th/*system* insert-snapshot file snapshot-id candidate)
                                               (get-main-fragment-ids cfg main))]
        (t/is (= :stale (:status (run-gc id))))))
    (t/is (not (marked? id candidate)))
    (t/is (= 1 (count (get-jobs "file-fragments-gc"))))))

(t/deftest changing-the-main-payload-without-changing-revn-invalidates-the-lot
  (let [{:keys [id]} (fixture-file)
        orphan       (fragment id)
        prepare      (deref #'gc/prepare-lot)]
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [gc/prepare-lot (fn [cfg file-id]
                                     (let [lot (prepare cfg file-id)]
                                       (db/tx-run! (db/own-transaction cfg)
                                                   (fn [cfg]
                                                     (db/exec-one! cfg ["SELECT id FROM file WHERE id = ? FOR UPDATE" id])
                                                     (save-main cfg id orphan)))
                                       lot))]
        (t/is (= :stale (:status (run-gc id))))))
    (t/is (not (marked? id orphan)))))

(t/deftest each-lot-revalidates-before-marking
  (let [{:keys [id] :as file} (fixture-file)
        main-ref (fragment id)
        snapshot (uuid/next)
        mark     (deref #'gc/mark-lot)
        lots     (atom 0)]
    (save-main th/*system* id main-ref)
    (fragment id)
    (fragment id)
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [gc/max-fragments-per-marking-transaction 1
                    gc/mark-lot (fn [cfg lot]
                                  (let [result (mark cfg lot)]
                                    (when (= 1 (swap! lots inc))
                                      (insert-snapshot cfg file snapshot main-ref))
                                    result))]
        (t/is (= {:status :stale :file-id id :marked 1} (run-gc id)))))
    (t/is (= 2 @lots))
    (t/is (= 1 (:total (th/db-exec-one!
                        ["SELECT COUNT(*) AS total FROM file_data
                          WHERE file_id = ? AND type = 'fragment' AND deleted_at IS NOT NULL" id]))))
    (t/is (= 1 (count (get-jobs "file-fragments-gc"))))))

(t/deftest a-recent-save-defers-fragment-marking
  (let [{:keys [id]} (fixture-file)
        orphan (fragment id)]
    (th/db-exec-one! ["UPDATE file SET modified_at = ? WHERE id = ?" (ct/now) id])
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (t/is (= {:status :recent :file-id id :marked 0} (run-gc id))))
    (t/is (not (marked? id orphan)))
    (t/is (ct/is-after? (:scheduled-at (first (get-jobs "file-fragments-gc")))
                        (ct/in-future "47h")))))

(t/deftest queued-fragment-gc-skips-preparation-without-pointer-maps
  (let [{:keys [id]} (fixture-file)
        orphan (fragment id)]
    (jobs/submit th/*system* {::jobs/name :file-fragments-gc
                              ::jobs/params {:file-id id}})
    (binding [cf/flags (disj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [gc/prepare-lot (fn [& _] (throw (ex-info "disabled job prepared a lot" {})))]
        (t/is (= {:status :disabled :file-id id :marked 0} (run-gc id)))))
    (t/is (not (marked? id orphan)))))

(t/deftest pointer-maps-enable-fragment-marking-with-pending-offload
  (let [{:keys [id]} (fixture-file)
        orphan (fragment id)]
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map :tiered-file-data-storage)]
      (jobs/submit th/*system* {::jobs/name :offload-file-data
                                ::jobs/params {:file-id id}})
      (t/is (= {:status :complete :file-id id :marked 1}
               (th/run-task! :file-fragments-gc {:file-id id}))))
    (t/is (marked? id orphan))))
