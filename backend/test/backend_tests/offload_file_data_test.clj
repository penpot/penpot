;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.offload-file-data-test
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.jobs :as jobs]
   [app.jobs.metrics :as metrics]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.offload-file-data :as offload]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.io :as io]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- db-rows
  [file-id]
  (th/db-query :file-data {:file-id file-id}))

(defn- create-file
  [i profile]
  (th/create-file* i {:profile-id (:id profile)
                      :project-id (:default-project-id profile)
                      :is-shared false}))

(t/deftest offloads-file-data-to-storage
  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)
        before  (db-rows (:id file))]

    (t/is (pos? (count before)))
    (t/is (every? #(= "db" (:backend %)) before))

    (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
      (t/is (= (count before) (:offloaded result)))
      (t/is (zero? (:skipped result))))

    (let [after (db-rows (:id file))]
      (t/is (= (count before) (count after)))
      (t/is (every? #(= "storage" (:backend %)) after))
      (t/is (every? #(nil? (:data %)) after)))))

(t/deftest skips-file-locked-by-another-transaction
  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)
        before  (db-rows (:id file))
        locked  (promise)
        release (promise)
        holder  (future
                  (db/tx-run! th/*system*
                              (fn [{:keys [::db/conn]}]
                                (db/exec-one! conn ["SELECT id FROM file WHERE id = ? FOR UPDATE" (:id file)])
                                (deliver locked true)
                                @release
                                :held)))]

    (t/is (= true (deref locked 10000 ::timeout)))

    (try
      (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
        (t/is (= 0 (:offloaded result)))
        (t/is (= 1 (:skipped result))))
      (finally
        (deliver release true)
        (deref holder 10000 ::timeout)))

    (let [after (db-rows (:id file))]
      (t/is (= (count before) (count after)))
      (t/is (every? #(= "db" (:backend %)) after)))))

(t/deftest skips-missing-and-deleted-files
  (let [result (th/run-task! :offload-file-data {:file-id (uuid/next)})]
    (t/is (= 0 (:offloaded result)))
    (t/is (= 1 (:skipped result))))

  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)
        _       (th/db-update! :file {:deleted-at (ct/now)} {:id (:id file)})
        before  (db-rows (:id file))
        result  (th/run-task! :offload-file-data {:file-id (:id file)})]
    (t/is (= 0 (:offloaded result)))
    (t/is (= 1 (:skipped result)))
    (let [after (db-rows (:id file))]
      (t/is (= (count before) (count after)))
      (t/is (every? #(= "db" (:backend %)) after)))))

(defn- row-bytes
  [{:keys [data]}]
  (if (bytes? data)
    (alength ^bytes data)
    0))

(t/deftest summary-reports-rows-and-bytes
  (let [profile        (th/create-profile* 1)
        file           (create-file 1 profile)
        before         (db-rows (:id file))
        expected-bytes (reduce + 0 (map row-bytes before))]

    (t/is (pos? (count before)))
    (t/is (every? some? (map :data before)))
    (t/is (pos? expected-bytes))

    (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
      (t/is (= (count before) (:offloaded result)))
      (t/is (= expected-bytes (:bytes result)))
      (t/is (zero? (:skipped result))))

    ;; A second run is a no-op: nothing left on the hot storage.
    (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
      (t/is (= 0 (:offloaded result)))
      (t/is (= 0 (:bytes result)))
      (t/is (zero? (:skipped result))))))

(t/deftest storage-failure-aborts-only-that-file
  (let [profile  (th/create-profile* 1)
        file-a   (create-file 1 profile)
        file-b   (create-file 2 profile)
        real-put @#'sto/put-object!
        failing  (fn [storage params]
                   (if (= (:file-id params) (:id file-a))
                     (throw (ex-info "storage is down" {:file-id (:id file-a)}))
                     (real-put storage params)))]

    ;; The failing file aborts as a whole: every row stays on hot storage.
    (with-redefs [sto/put-object! failing]
      (t/is (thrown? Exception
                     (th/run-task! :offload-file-data {:file-id (:id file-a)}))))

    (let [rows-a (db-rows (:id file-a))]
      (t/is (pos? (count rows-a)))
      (t/is (every? #(= "db" (:backend %)) rows-a))
      (t/is (every? some? (map :data rows-a))))

    ;; The other file is independent and offloads while storage is down for A.
    (with-redefs [sto/put-object! failing]
      (let [result (th/run-task! :offload-file-data {:file-id (:id file-b)})]
        (t/is (pos? (:offloaded result)))
        (t/is (pos? (:bytes result)))
        (t/is (zero? (:skipped result)))))

    (t/is (every? #(= "storage" (:backend %)) (db-rows (:id file-b))))

    ;; Once storage recovers, a retry converges with no leftovers on hot.
    (let [result (th/run-task! :offload-file-data {:file-id (:id file-a)})]
      (t/is (pos? (:offloaded result)))
      (t/is (pos? (:bytes result)))
      (t/is (zero? (:skipped result))))

    (let [rows-a (db-rows (:id file-a))]
      (t/is (every? #(= "storage" (:backend %)) rows-a))
      (t/is (every? #(nil? (:data %)) rows-a)))))

(defn- make-metrics
  []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics
                                      [:file-data-offload-rows
                                       :file-data-offload-bytes
                                       :file-data-offload-timing])}))

(defn- offload-cfg
  [metrics]
  (-> th/*system*
      (assoc ::mtx/metrics metrics)
      (assoc ::sto/storage (assoc (::sto/storage th/*system*)
                                  ::mtx/metrics metrics))))

(defn- counter-value
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(def ^:private known-types
  ["main" "snapshot" "fragment"])

(defn- total-counter
  [metrics id]
  (reduce + 0 (map #(counter-value metrics id [%]) known-types)))

(t/deftest metric-definitions-are-pinned
  (let [defs main/default-metrics]
    (t/is (= "penpot_file_data_offload_rows_total"
             (get-in defs [:file-data-offload-rows ::mdef/name])))
    (t/is (= ["type"]
             (get-in defs [:file-data-offload-rows ::mdef/labels])))
    (t/is (= :counter
             (get-in defs [:file-data-offload-rows ::mdef/type])))
    (t/is (= "penpot_file_data_offload_bytes_total"
             (get-in defs [:file-data-offload-bytes ::mdef/name])))
    (t/is (= ["type"]
             (get-in defs [:file-data-offload-bytes ::mdef/labels])))
    (t/is (= :counter
             (get-in defs [:file-data-offload-bytes ::mdef/type])))
    (t/is (= "penpot_file_data_offload_timing_ms"
             (get-in defs [:file-data-offload-timing ::mdef/name])))
    (t/is (= ["result"]
             (get-in defs [:file-data-offload-timing ::mdef/labels])))
    (t/is (= :histogram
             (get-in defs [:file-data-offload-timing ::mdef/type])))
    (t/is (= [1 10 50 100 500 1000 5000 10000 30000 60000 300000]
             (get-in defs [:file-data-offload-timing ::mdef/buckets])))))

(t/deftest success-emits-rows-and-bytes
  (let [metrics (make-metrics)
        profile (th/create-profile* 1)
        file    (create-file 1 profile)
        result  (offload/execute-offload-file-data (offload-cfg metrics)
                                                   {:file-id (:id file)})]
    (t/is (pos? (:offloaded result)))
    (t/is (= (double (:offloaded result))
             (total-counter metrics :file-data-offload-rows)))
    (t/is (= (double (:bytes result))
             (total-counter metrics :file-data-offload-bytes)))
    (t/is (pos? (counter-value metrics :file-data-offload-rows ["main"])))
    (t/is (pos? (total-counter metrics :file-data-offload-rows)))))

(t/deftest skip-records-skip-and-nothing-else
  (let [durations (volatile! [])
        rows      (volatile! [])
        bytes     (volatile! [])]
    (with-redefs [metrics/record-offload-duration (fn [_ result _] (vswap! durations conj result) nil)
                  metrics/record-offload-rows (fn [_ type amount] (vswap! rows conj [type amount]) nil)
                  metrics/record-offload-bytes (fn [_ type amount] (vswap! bytes conj [type amount]) nil)]
      (let [result (offload/execute-offload-file-data th/*system* {:file-id (uuid/next)})]
        (t/is (= 0 (:offloaded result)))
        (t/is (= 1 (:skipped result)))))
    (t/is (= [:skip] @durations))
    (t/is (empty? @rows))
    (t/is (empty? @bytes))))

(t/deftest error-records-error-and-rethrows
  (let [profile  (th/create-profile* 1)
        file     (create-file 1 profile)
        durations (volatile! [])
        real-put @#'sto/put-object!]
    (with-redefs [sto/put-object! (fn [_ _] (throw (ex-info "storage is down" {})))
                  metrics/record-offload-duration (fn [_ result _] (vswap! durations conj result) nil)]
      (t/is (thrown? Exception
                     (offload/execute-offload-file-data th/*system* {:file-id (:id file)}))))
    (t/is (= [:error] @durations))
    ;; Nothing was migrated: the whole file aborted.
    (t/is (every? #(= "db" (:backend %)) (db-rows (:id file))))))

(t/deftest missing-metrics-raises
  (let [params {::db/pool th/*pool*
                ::sto/storage (::sto/storage th/*system*)}]
    (t/is (thrown? AssertionError
                   (ig/assert-key :app.tasks.offload-file-data/job-def params)))
    (t/is (nil? (ig/assert-key :app.tasks.offload-file-data/job-def
                               (assoc params ::mtx/metrics (:app.metrics/metrics th/*system*)))))))

(t/deftest submit-validates-params-upfront
  (let [cfg th/*system*]
    (t/testing "valid params submit a durable job"
      (t/is (uuid? (jobs/submit cfg {::jobs/name   :offload-file-data
                                     ::jobs/params {:file-id (uuid/next)}}))))
    (t/testing "invalid params fail at submit instead of burning retries"
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name   :offload-file-data
                                       ::jobs/params {}})))
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name   :offload-file-data
                                       ::jobs/params {:file-id "not-a-uuid"}}))))))

(t/deftest file-gc-without-flag-does-not-enqueue-offload
  (t/is (not (contains? cf/flags :tiered-file-data-storage)))
  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)]
    (t/is (true? (th/run-task! :file-gc {:file-id (:id file)})))
    (th/run-pending-jobs)
    (t/testing "no offload job was enqueued"
      (t/is (empty? (th/db-query :job {:name "offload-file-data"}))))
    (t/testing "every row stays on hot storage"
      (let [rows (db-rows (:id file))]
        (t/is (pos? (count rows)))
        (t/is (every? #(= "db" (:backend %)) rows))
        (t/is (every? some? (map :data rows)))))))

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out    (th/command! params)]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- add-page-with-rect
  [profile file page-id shape-id]
  (update-file!
   :file-id (:id file)
   :profile-id (:id profile)
   :changes
   [{:type :add-page
     :name "test"
     :id page-id}
    {:type :add-obj
     :page-id page-id
     :id shape-id
     :parent-id uuid/zero
     :frame-id uuid/zero
     :components-v2 true
     :obj (cts/setup-shape
           {:id shape-id
            :name "rect"
            :frame-id uuid/zero
            :parent-id uuid/zero
            :type :rect})}]))

(defn- get-file-data
  [profile file-id]
  (let [out (th/command! {::th/type :get-file
                          ::rpc/profile-id (:id profile)
                          :id file-id})]
    (t/is (nil? (:error out)))
    (get-in out [:result :data])))

(t/deftest offloaded-file-reads-identically
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (create-file 1 profile)
        page-id  (uuid/next)
        shape-id (uuid/next)]
    (add-page-with-rect profile file page-id shape-id)

    (let [before-bfc (:data (bfc/get-file th/*system* (:id file) {:realize? true}))
          before-rpc (get-file-data profile (:id file))]

      (t/is (some? (get-in before-bfc [:pages-index page-id :objects shape-id])))
      (t/is (= before-bfc before-rpc))

      (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
        (t/is (pos? (:offloaded result)))
        (t/is (every? #(= "storage" (:backend %)) (remove :deleted-at (db-rows (:id file))))))

      (t/testing "direct read resolves cold storage transparently"
        (t/is (= before-bfc
                 (:data (bfc/get-file th/*system* (:id file) {:realize? true})))))

      (t/testing "rpc read resolves cold storage transparently"
        (t/is (= before-rpc (get-file-data profile (:id file))))))))

(t/deftest snapshot-survives-offload
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (create-file 1 profile)
        page-id  (uuid/next)
        shape-id (uuid/next)]
    (add-page-with-rect profile file page-id shape-id)

    (let [out (th/command! {::th/type :create-file-snapshot
                            ::rpc/profile-id (:id profile)
                            :file-id (:id file)
                            :label "before-offload"})]
      (t/is (nil? (:error out)))
      (t/is (uuid? (get-in out [:result :id]))))

    (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
      (t/is (pos? (:offloaded result))))

    (t/testing "live snapshot rows moved to cold storage"
      (let [snapshots (th/db-query :file-data {:file-id (:id file)
                                               :type "snapshot"
                                               :deleted-at nil})]
        (t/is (pos? (count snapshots)))
        (t/is (every? #(= "storage" (:backend %)) snapshots))
        (t/is (every? #(nil? (:data %)) snapshots))))

    (t/testing "snapshots are still listed"
      (let [out (th/command! {::th/type :get-file-snapshots
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)})]
        (t/is (nil? (:error out)))
        (t/is (some #(= "before-offload" (:label %)) (:result out)))))

    (t/testing "snapshot restores from cold storage"
      (let [snap-id (->> (:result (th/command! {::th/type :get-file-snapshots
                                                ::rpc/profile-id (:id profile)
                                                :file-id (:id file)}))
                         (filter #(= "before-offload" (:label %)))
                         first
                         :id)
            out     (th/command! {::th/type :restore-file-snapshot
                                  ::rpc/profile-id (:id profile)
                                  :file-id (:id file)
                                  :id snap-id})]
        (t/is (some? snap-id))
        (t/is (nil? (:error out)))
        (t/is (some? (get-in (get-file-data profile (:id file))
                             [:pages-index page-id :objects shape-id])))))))

(t/deftest write-after-offload-returns-to-hot-storage
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (create-file 1 profile)
        page-id  (uuid/next)
        shape-id (uuid/next)]

    (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
      (t/is (pos? (:offloaded result))))

    (let [main (th/db-get :file-data {:file-id (:id file) :type "main"})]
      (t/is (= "storage" (:backend main)))
      (t/is (nil? (:data main))))

    (add-page-with-rect profile file page-id shape-id)

    (t/testing "the new main row is back on hot storage"
      (let [main (th/db-get :file-data {:file-id (:id file) :type "main"})]
        (t/is (= "db" (:backend main)))
        (t/is (bytes? (:data main)))))

    (t/testing "the written content reads back"
      (t/is (some? (get-in (get-file-data profile (:id file))
                           [:pages-index page-id :objects shape-id]))))))

(t/deftest export-import-roundtrip-after-offload
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (create-file 1 profile)
        page-id  (uuid/next)
        shape-id (uuid/next)]
    (add-page-with-rect profile file page-id shape-id)

    (let [before (:data (bfc/get-file th/*system* (:id file) {:realize? true}))
          _      (th/run-task! :offload-file-data {:file-id (:id file)})
          output (tmp/tempfile :suffix ".zip")]

      (v3/export-files!
       (-> th/*system*
           (assoc ::bfc/ids #{(:id file)})
           (assoc ::bfc/export-type :detach-libraries))
       (io/output-stream output))

      (let [result   (-> th/*system*
                         (assoc ::bfc/project-id (:default-project-id profile))
                         (assoc ::bfc/profile-id (:id profile))
                         (assoc ::bfc/team-id (:default-team-id profile))
                         (assoc ::bfc/input output)
                         (v3/import-files!))
            imported (:data (bfc/get-file th/*system* (first (:file-ids result))
                                          {:realize? true}))]
        (t/is (= 1 (count (:file-ids result))))
        (t/is (= (set (keys (:pages-index before)))
                 (set (keys (:pages-index imported)))))
        (t/is (= (get-in before [:pages-index page-id :name])
                 (get-in imported [:pages-index page-id :name])))
        (t/is (some? (get-in imported [:pages-index page-id :objects shape-id])))))))

(t/deftest deleted-offloaded-file-is-fully-purged
  (let [storage (sto/resolve th/*system*)
        profile (th/create-profile* 1 {:is-active true})
        file    (create-file 1 profile)
        page-id (uuid/next)]

    (add-page-with-rect profile file page-id (uuid/next))

    (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
      (t/is (pos? (:offloaded result))))

    (let [ref-ids (into #{}
                        (comp (map :metadata)
                              (map fdata/decode-metadata)
                              (keep :storage-ref-id))
                        (db-rows (:id file)))]
      (t/is (pos? (count ref-ids)))
      (t/is (every? some? (map #(sto/get-object storage %) ref-ids)))

      (let [out (th/command! {::th/type :delete-file
                              ::rpc/profile-id (:id profile)
                              :id (:id file)})]
        (t/is (nil? (:error out))))
      (th/run-pending-jobs)

      (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
                  (th/run-task! :objects-gc {}))]
        (t/is (pos? (:processed res))))

      (t/testing "every file data row is gone"
        (t/is (empty? (db-rows (:id file)))))

      ;; The touches above landed 8 days in the future, so the touched
      ;; collector needs a clock past them plus its deletion delay.
      (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 9}))]
                  (th/run-task! :storage-gc-touched {}))]
        (t/is (pos? (:delete res))))

      (t/testing "every referenced storage object is reclaimed"
        (let [objects (mapv #(sto/get-object storage %) ref-ids)]
          (t/is (every? nil? objects)))))))

(t/deftest file-gc-with-stale-revn-enqueues-nothing
  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)
        before  (db-rows (:id file))
        stale   (inc (or (:revn file) 0))]
    (t/is (not (th/run-task! :file-gc {:file-id (:id file) :revn stale})))
    (th/run-pending-jobs)
    (t/testing "no offload job was enqueued"
      (t/is (empty? (th/db-query :job {:name "offload-file-data"}))))
    (t/testing "every row stays on hot storage"
      (let [after (db-rows (:id file))]
        (t/is (= (count before) (count after)))
        (t/is (every? #(= "db" (:backend %)) after))))))

(t/deftest concurrent-edit-and-offload-stay-coherent
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (create-file 1 profile)
        page-1   (uuid/next)
        shape-1  (uuid/next)
        page-2   (uuid/next)
        shape-2  (uuid/next)]
    (add-page-with-rect profile file page-1 shape-1)

    (let [locked  (promise)
          release (promise)
          holder  (future
                    (db/tx-run! th/*system*
                                (fn [{:keys [::db/conn]}]
                                  (db/exec-one! conn ["SELECT id FROM file WHERE id = ? FOR UPDATE" (:id file)])
                                  (deliver locked true)
                                  @release
                                  :held)))
          _       (t/is (= true (deref locked 10000 ::timeout)))
          updater (future
                    (add-page-with-rect profile file page-2 shape-2))]

      (try
        ;; Give the updater time to block on the held row lock.
        (th/sleep 500)
        (t/is (not (realized? updater)))

        (t/testing "offload skips while the row is locked"
          (let [result (th/run-task! :offload-file-data {:file-id (:id file)})]
            (t/is (= 0 (:offloaded result)))
            (t/is (= 1 (:skipped result)))))
        (finally
          (deliver release true)))

      (t/is (not= ::timeout (deref holder 10000 ::timeout)))
      (t/is (not= ::timeout (deref updater 30000 ::timeout))))

    (t/testing "the blocked edit won as a whole"
      (t/is (some? (get-in (get-file-data profile (:id file))
                           [:pages-index page-2 :objects shape-2]))))

    (t/testing "a later offload migrates the new bytes, never stale ones"
      (let [current (get-file-data profile (:id file))
            result  (th/run-task! :offload-file-data {:file-id (:id file)})]
        (t/is (pos? (:offloaded result)))
        (t/is (= current (get-file-data profile (:id file))))))))

(t/deftest partial-failure-leaves-reclaimable-orphans
  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)
        _       (t/is (>= (count (db-rows (:id file))) 2))
        calls   (volatile! 0)
        orphans (volatile! [])
        real-put @#'sto/put-object!
        mock    (fn [storage params]
                  (if (= (:file-id params) (:id file))
                    (let [n (vswap! calls inc)]
                      (if (= 1 n)
                        (let [object (real-put storage params)]
                          (vswap! orphans conj (:id object))
                          object)
                        (throw (ex-info "storage is down" {}))))
                    (real-put storage params)))]

    (with-redefs [sto/put-object! mock]
      (t/is (thrown? Exception
                     (th/run-task! :offload-file-data {:file-id (:id file)}))))

    (t/testing "the aborted file keeps every row on hot storage"
      (let [rows (db-rows (:id file))]
        (t/is (every? #(= "db" (:backend %)) rows))
        (t/is (every? some? (map :data rows)))))

    (t/testing "the orphaned blob is reclaimed once old enough"
      (t/is (= 1 (count @orphans)))
      (let [storage (sto/resolve th/*system*)]
        (t/is (some? (sto/get-object storage (first @orphans))))
        (let [res (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:hours 3}))]
                    (th/run-task! :storage-gc-touched {}))]
          (t/is (pos? (:delete res))))
        (t/is (nil? (sto/get-object storage (first @orphans))))))))
