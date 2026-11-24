;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.import-team-concurrency-test
  (:require
   [app.binfile.v3 :as v3]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.rpc.commands.management :as management]
   [app.rpc.commands.projects :as projects]
   [app.storage :as sto]
   [app.worker :as worker]
   [app.worker.dispatcher :as dispatcher]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(defn- await-result
  [p]
  (let [value (deref p 15000 ::timeout)]
    (when (= ::timeout value)
      (throw (ex-info "Timed out waiting for concurrent operation" {})))
    value))

(defn- get-pid
  [cfg]
  (:pid (db/exec-one! cfg ["SELECT pg_backend_pid() AS pid"])))

(defn- await-blocked
  [pid]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (let [row (db/exec-one! th/*system*
                              ["SELECT cardinality(pg_blocking_pids(?)) > 0 AS blocked" pid])]
        (cond
          (:blocked row) true
          (< (System/nanoTime) deadline) (recur)
          :else (throw (ex-info "Database operation did not block" {:pid pid})))))))

(defn- setup-import
  [profile project-id team-id]
  (let [path     (-> "backend_tests/test_files/svg-attrs-camel-case.penpot"
                     io/resource jio/file)
        staged   (js/put-resource th/*system* (:id profile)
                                  {:content (sto/content path)
                                   :filename "package.penpot"
                                   :mtype "application/zip"})
        params   (cond-> {:project-id project-id :name "imported" :version 3}
                   team-id (assoc :team-id team-id))
        job-id   (th/create-job* :import-binfile {:profile-id (:id profile)
                                                  :resource-id (:resource-id staged)})]
    (th/db-update! :job {:params (db/json params)} {:id job-id})
    {:id job-id :params params}))

(defn- run-import
  [{:keys [id params]}]
  (jobs/invoke (assoc th/*system*
                      ::jobs/name :import-binfile
                      ::jobs/params params
                      ::jobs/context (jobs/make-context (jobs/get-job th/*system* id)))))

(defn- get-files
  [project-id]
  (th/db-exec! ["SELECT id FROM file WHERE project_id = ? AND deleted_at IS NULL"
                project-id]))

(defn- move
  [cfg profile-id project-id team-id]
  (management/move-project cfg {:profile-id profile-id
                                :project-id project-id
                                :team-id team-id}))

(t/deftest move-waits-for-import-to-commit
  (let [profile     (th/create-profile* 1)
        project     (th/create-project* 1 {:profile-id (:id profile)
                                           :team-id (:default-team-id profile)})
        destination (th/create-team* 1 {:profile-id (:id profile)})
        job         (setup-import profile (:id project) (:default-team-id profile))
        entered     (promise)
        release     (promise)
        move-pid    (promise)
        original    v3/import-files!]
    (with-redefs [v3/import-files! (fn [cfg]
                                     (deliver entered true)
                                     (await-result release)
                                     (original cfg))]
      (let [import (future (run-import job))]
        (try
          (await-result entered)
          (let [moving (future
                         (db/tx-run! th/*system*
                                     (fn [cfg]
                                       (deliver move-pid (get-pid cfg))
                                       (move cfg (:id profile) (:id project) (:id destination)))))]
            (try
              (t/is (true? (await-blocked (await-result move-pid))))
              (t/is (= (:default-team-id profile)
                       (:team-id (th/db-get :project {:id (:id project)}))))
              (deliver release true)
              (let [result (await-result import)]
                (await-result moving)
                (t/is (= (set (:file-ids result))
                         (set (map :id (get-files (:id project)))))))
              (t/is (= (:id destination)
                       (:team-id (th/db-get :project {:id (:id project)}))))
              (finally
                (deliver release true)
                (await-result moving))))
          (finally
            (deliver release true)
            (await-result import)))))))

(t/deftest import-waits-for-move-and-rejects-the-new-team
  (let [profile     (th/create-profile* 1)
        project     (th/create-project* 1 {:profile-id (:id profile)
                                           :team-id (:default-team-id profile)})
        destination (th/create-team* 1 {:profile-id (:id profile)})
        job         (setup-import profile (:id project) (:default-team-id profile))
        moved       (promise)
        release     (promise)
        import-pid  (promise)
        original    projects/get-project-for-update
        moving      (future
                      (db/tx-run! th/*system*
                                  (fn [cfg]
                                    (move cfg (:id profile) (:id project) (:id destination))
                                    (deliver moved true)
                                    (await-result release))))]
    (try
      (await-result moved)
      (with-redefs [projects/get-project-for-update
                    (fn [cfg id]
                      (deliver import-pid (get-pid cfg))
                      (original cfg id))]
        (let [import (future (try (run-import job)
                                  (catch Throwable cause cause)))]
          (try
            (t/is (true? (await-blocked (await-result import-pid))))
            (deliver release true)
            (await-result moving)
            (t/is (= :import-team-changed (-> (await-result import) ex-data :code)))
            (t/is (empty? (get-files (:id project))))
            (finally
              (deliver release true)
              (await-result import)))))
      (finally
        (deliver release true)
        (await-result moving)))))

(t/deftest moved-import-without-destination-access-returns-not-found
  (let [profile     (th/create-profile* 1)
        project     (th/create-project* 1 {:profile-id (:id profile)
                                           :team-id (:default-team-id profile)})
        destination (th/create-team* 1 {:profile-id (:id profile)})
        job         (setup-import profile (:id project) (:default-team-id profile))]
    (db/tx-run! th/*system*
                #(move % (:id profile) (:id project) (:id destination)))
    (th/db-exec! ["DELETE FROM team_profile_rel WHERE team_id = ? AND profile_id = ?"
                  (:id destination) (:id profile)])
    (let [error (try (run-import job)
                     (catch Throwable cause cause))]
      (t/is (= :not-found (:type (ex-data error))))
      (t/is (= :object-not-found (:code (ex-data error))))
      (t/is (empty? (get-files (:id project)))))))

(t/deftest import-waits-for-move-rollback-and-succeeds
  (let [profile     (th/create-profile* 1)
        project     (th/create-project* 1 {:profile-id (:id profile)
                                           :team-id (:default-team-id profile)})
        destination (th/create-team* 1 {:profile-id (:id profile)})
        job         (setup-import profile (:id project) (:default-team-id profile))
        moved       (promise)
        release     (promise)
        import-pid  (promise)
        original    projects/get-project-for-update
        moving      (future
                      (try
                        (db/tx-run! th/*system*
                                    (fn [cfg]
                                      (move cfg (:id profile) (:id project) (:id destination))
                                      (deliver moved true)
                                      (await-result release)
                                      (throw (ex-info "Rollback move" {:rollback true}))))
                        (catch Throwable cause cause)))]
    (try
      (await-result moved)
      (with-redefs [projects/get-project-for-update
                    (fn [cfg id]
                      (deliver import-pid (get-pid cfg))
                      (original cfg id))]
        (let [import (future (run-import job))]
          (try
            (t/is (true? (await-blocked (await-result import-pid))))
            (deliver release true)
            (t/is (true? (-> (await-result moving) ex-data :rollback)))
            (let [result (await-result import)]
              (t/is (seq (:file-ids result)))
              (t/is (= (set (:file-ids result))
                       (set (map :id (get-files (:id project)))))))
            (t/is (= (:default-team-id profile)
                     (:team-id (th/db-get :project {:id (:id project)}))))
            (finally
              (deliver release true)
              (await-result import)))))
      (finally
        (deliver release true)
        (await-result moving)))))

(defn- blocked-terminal-import
  [legacy terminal]
  (let [profile    (th/create-profile* 1)
        project-id (:default-project-id profile)
        job        (setup-import profile project-id (when-not legacy (:default-team-id profile)))
        locked     (promise)
        release    (promise)
        import-pid (promise)
        original   projects/get-project-for-update
        holder     (future
                     (db/tx-run! th/*system*
                                 (fn [cfg]
                                   (db/exec-one! cfg ["SELECT id FROM project WHERE id = ? FOR NO KEY UPDATE"
                                                      project-id])
                                   (deliver locked true)
                                   (await-result release))))]
    (try
      (await-result locked)
      (with-redefs [projects/get-project-for-update
                    (fn [cfg id]
                      (deliver import-pid (get-pid cfg))
                      (original cfg id))]
        (let [import (future (try (run-import job)
                                  (catch Throwable cause cause)))]
          (try
            (t/is (true? (await-blocked (await-result import-pid))))
            (if (= terminal :cancelled)
              (t/is (= 1 (await-result (future (jobs/cancel th/*system* (:id job))))))
              (do
                (th/db-update! :job {:modified-at (ct/minus (ct/now)
                                                            (ct/plus (cf/get-jobs-lease)
                                                                     (ct/duration {:minutes 1})))}
                               {:id (:id job)})
                (db/tx-run! th/*system*
                            (fn [cfg]
                              (#'dispatcher/mark-orphan-jobs
                               (assoc cfg ::dispatcher/timestamp (ct/now)
                                      ::worker/tenant (cf/get :tenant)))))))
            (t/is (= (name terminal) (:status (jobs/get-job th/*system* (:id job)))))
            (deliver release true)
            (await-result holder)
            (t/is (= :job-interrupted (-> (await-result import) ex-data :code)))
            (t/is (empty? (get-files project-id)))
            (t/is (zero? (jobs/complete th/*system* :job-id (:id job)
                                        :result {:file-ids [] :name "imported" :version 3})))
            (finally
              (deliver release true)
              (await-result import)))))
      (finally
        (deliver release true)
        (await-result holder)))))

(t/deftest cancellation-of-a-blocked-import
  (blocked-terminal-import false :cancelled))

(t/deftest cancellation-of-a-blocked-legacy-import
  (blocked-terminal-import true :cancelled))

(t/deftest lease-expiry-of-a-blocked-import
  (blocked-terminal-import false :aborted))

(defn- cancel-running-import
  [legacy]
  (let [profile    (th/create-profile* 1)
        project-id (:default-project-id profile)
        job        (setup-import profile project-id (when-not legacy (:default-team-id profile)))
        entered    (promise)
        release    (promise)
        lock-pid   (promise)
        original   v3/import-files!]
    (with-redefs [v3/import-files!
                  (fn [cfg]
                    (let [result (original cfg)
                          files  (db/exec! cfg ["SELECT id FROM file WHERE project_id = ? AND deleted_at IS NULL"
                                                project-id])]
                      (deliver entered (count files))
                      (await-result release)
                      result))]
      (let [import (future (try (run-import job)
                                (catch Throwable cause cause)))]
        (try
          (t/is (pos? (await-result entered)))
          (let [waiter (future
                         (db/tx-run! th/*system*
                                     (fn [cfg]
                                       (deliver lock-pid (get-pid cfg))
                                       (db/exec-one! cfg ["SELECT id FROM project WHERE id = ? FOR NO KEY UPDATE"
                                                          project-id]))))
                cancel (atom nil)]
            (try
              (t/is (true? (await-blocked (await-result lock-pid))))
              (reset! cancel (future (jobs/cancel th/*system* (:id job))))
              (t/is (= 1 (await-result @cancel)))
              (t/is (= "cancelled" (:status (jobs/get-job th/*system* (:id job)))))
              (deliver release true)
              (let [error (await-result import)]
                (t/is (= :interrupt (:type (ex-data error))))
                (t/is (= :job-interrupted (:code (ex-data error)))))
              (await-result waiter)
              (t/is (empty? (get-files project-id)))
              (finally
                (deliver release true)
                (await-result import)
                (await-result waiter)
                (when @cancel
                  (await-result @cancel)))))
          (finally
            (deliver release true)
            (await-result import)))))))

(t/deftest cancellation-of-a-running-import
  (cancel-running-import false))

(t/deftest cancellation-of-a-running-legacy-import
  (cancel-running-import true))
