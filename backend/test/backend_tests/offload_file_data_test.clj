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
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

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
