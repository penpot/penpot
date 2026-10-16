;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.offload-file-data-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [promesa.exec :as px]))

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

(t/deftest offload-sleeps-every-n-rows
  (let [profile (th/create-profile* 1)
        file    (create-file 1 profile)
        calls   (volatile! [])]
    (with-redefs [px/sleep (fn [ms] (vswap! calls conj ms) nil)]
      (let [result (th/run-task! :offload-file-data {:file-id (:id file)
                                                     :sleep-every 1
                                                     :sleep-ms 5})]
        (t/is (pos? (:offloaded result)))
        (t/is (= (:offloaded result) (count @calls)))
        (t/is (every? #(= 5 %) @calls))))))
