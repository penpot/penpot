;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-change-refs-job-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-change-refs :as refs]
   [app.features.file-changes :as fchanges]
   [app.jobs :as jobs]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   java.util.UUID))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-file
  []
  (let [profile (th/create-profile* 1)]
    (:id (th/create-file* 1 {:profile-id (:id profile)
                             :project-id (:default-project-id profile)}))))

(defn- run-scheduler
  [file-id]
  (th/run-task! :index-file-change-refs-scheduler {:file-id file-id}))

(defn- run-index
  [file-id change-id]
  (th/run-task! :index-file-change-refs {:file-id file-id :change-id change-id}))

(defn- index-jobs
  []
  (th/db-query :job {:name "index-file-change-refs"}))

(t/deftest the-index-job-requires-a-change-id
  (let [failure (ex/try! (jobs/submit th/*system* {::jobs/name :index-file-change-refs
                                                   ::jobs/params {:file-id (uuid/next)}}))]
    (t/is (= :data-validation (:code (ex-data failure))))
    (t/is (empty? (index-jobs)))))

(t/deftest scheduler-queues-uncovered-changes-across-pages-without-indexing
  (let [file-id    (create-file)
        change-ids (mapv #(UUID. 0 (long %)) (range 1 67))
        first-id   (first change-ids)]
    (doseq [id change-ids]
      (th/db-insert! :file-change {:file-id file-id :id id :kind fchanges/revision-kind}))
    (db/run! th/*system* refs/index-change file-id first-id)
    (binding [cf/flags (conj cf/flags :feature-fdata-pointer-map)]
      (let [id  (jobs/submit th/*system* {::jobs/name :index-file-change-refs-scheduler
                                          ::jobs/params {:file-id file-id}})
            job (jobs/get-job th/*system* id)]
        (t/is (= 1 (jobs/claim th/*system* id (:scheduled-at job))))
        (let [failure (ex/try! (jobs/complete th/*system* :job-id id
                                              :result {:file-id file-id :scheduled -1}))]
          (t/is (= :invalid-result (:code (ex-data failure)))))
        (let [result (run-scheduler file-id)]
          (t/is (= {:file-id file-id :scheduled 65} result))
          (t/is (= 1 (jobs/complete th/*system* :job-id id :result result)))
          (t/is (= result (:result (jobs/get-job th/*system* id))))))
      (t/is (= {:file-id file-id :scheduled 0} (run-scheduler file-id)))
      (let [missing-id (uuid/next)]
        (t/is (= {:file-id missing-id :scheduled 0} (run-scheduler missing-id)))))
    (t/is (= (set (map str (rest change-ids)))
             (into #{} (map (comp :change-id db/decode-json-pgobject :params)) (index-jobs))))
    (t/is (= [(second change-ids)] (refs/get-missing-change-ids th/*system* file-id 1)))
    (t/is (empty? (th/db-query :job {:name "file-fragments-gc"})))))

(t/deftest a-running-index-job-is-not-scheduled-again
  (let [file-id   (create-file)
        change-id (uuid/next)]
    (th/db-insert! :file-change {:file-id file-id :id change-id :kind fchanges/revision-kind})
    (t/is (= {:file-id file-id :scheduled 1} (run-scheduler file-id)))
    (let [{:keys [id scheduled-at]} (first (index-jobs))]
      (t/is (= 1 (jobs/claim th/*system* id scheduled-at)))
      (t/is (= {:file-id file-id :scheduled 0} (run-scheduler file-id)))
      (let [failure (ex/try! (jobs/complete th/*system* :job-id id
                                            :result {:file-id file-id :change-id change-id :indexed 1}))]
        (t/is (= :invalid-result (:code (ex-data failure)))))
      (let [result (run-index file-id change-id)]
        (t/is (= {:file-id file-id :change-id change-id :indexed true} result))
        (t/is (= 1 (jobs/complete th/*system* :job-id id :result result)))
        (t/is (= result (:result (jobs/get-job th/*system* id))))))
    (t/is (= {:file-id file-id :change-id change-id :indexed false} (run-index file-id change-id)))
    (t/is (= 1 (count (index-jobs))))))

(t/deftest index-skips-a-snapshot-without-a-payload
  (let [file-id  (create-file)
        snapshot (UUID. 0 1)
        revision (UUID. 0 2)]
    (th/db-insert! :file-change {:file-id file-id :id snapshot :kind fchanges/snapshot-kind
                                 :label "missing"})
    (th/db-insert! :file-change {:file-id file-id :id revision :kind fchanges/revision-kind})

    (t/is (= {:file-id file-id :change-id snapshot :indexed false} (run-index file-id snapshot)))
    (t/is (= {:file-id file-id :change-id revision :indexed true} (run-index file-id revision)))
    (t/is (= [snapshot] (refs/get-missing-change-ids th/*system* file-id 2)))))

(t/deftest an-index-failure-only-retries-the-affected-change
  (let [file-id    (create-file)
        first-id   (UUID. 0 1)
        failing-id (UUID. 0 2)
        later-id   (UUID. 0 3)
        payload    (blob/encode {:components (pmap/create (uuid/next) {})})]
    (doseq [id [first-id failing-id later-id]]
      (th/db-insert! :file-change {:file-id file-id :id id :kind fchanges/snapshot-kind
                                   :label "snapshot"})
      (th/db-insert! :file-data {:file-id file-id :id id :type "snapshot" :backend "db"
                                 :data (if (= id failing-id) (byte-array [1]) payload)}))

    (t/is (= {:file-id file-id :scheduled 3} (run-scheduler file-id)))
    (t/is (:indexed (run-index file-id first-id)))
    (let [failure (ex/try! (run-index file-id failing-id))]
      (t/is (= ::wrk/retry (:type (ex-data failure))))
      (t/is (= :file-change-index-failed (:code (ex-data failure))))
      (t/is (= failing-id (:change-id (ex-data failure))))
      (t/is (= (ct/duration "1m") (:delay (ex-data failure)))))
    (t/is (= [failing-id later-id] (refs/get-missing-change-ids th/*system* file-id 3)))
    (t/is (:indexed (run-index file-id later-id)))
    (t/is (= [failing-id] (refs/get-missing-change-ids th/*system* file-id 3)))

    (th/db-update! :file-data {:data payload} {:file-id file-id :id failing-id})
    (t/is (:indexed (run-index file-id failing-id)))
    (t/is (empty? (refs/get-missing-change-ids th/*system* file-id 3)))))
