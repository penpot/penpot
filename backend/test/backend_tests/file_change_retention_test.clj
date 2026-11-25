;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-change-retention-test
  "Retention of `file_change` rows through the file lifecycle.

  Retained revisions follow a file into and out of the trash. Expiring
  revisions keep the one-hour schedule they were born with, independent of
  the trash. Snapshots keep their separate policy."
  (:require
   [app.binfile.common :as bfc]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-changes :as fchanges]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.tasks.objects-gc :as objects-gc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private test-now
  "The instant every test pins the clock to. Whole seconds on purpose: a value
  with a sub-second part does not survive the round trip through the database,
  and the test could not compare it for equality."
  (ct/inst "2026-10-01T00:00:00Z"))

(def ^:private revision-lifetime
  "The lifetime the `update-file` flow gives a revision when it inserts it."
  (ct/duration {:hours 1}))

(defn- create-file
  [i]
  (let [profile (th/create-profile* i {:is-active true})]
    [profile (th/create-file* i {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)})]))

(defn- insert-change
  "Inserts a `file_change` row directly, so the test builds the exact shape it
  needs. The row class is decided by what the caller passes: a revision carries
  only `changes`, a snapshot carries `label`, and a legacy payload row carries
  `data` without a label. Returns the new row id."
  [file-id {:keys [label data changes created-at deleted-at created-by]}]
  (let [created-at (or created-at (ct/now))
        id         (uuid/next)]
    (th/db-insert! :file-change
                   {:id         id
                    :file-id    file-id
                    :created-at created-at
                    :updated-at created-at
                    :deleted-at deleted-at
                    :revn       1
                    :version    0
                    :created-by (or created-by "system")
                    :label      label
                    :kind       (if label fchanges/snapshot-kind fchanges/revision-kind)
                    :data       data
                    :changes    changes}
                   {::db/return-keys false})
    id))

(defn- insert-revision
  "A revision exactly as the `update-file` flow writes it: no label, no data and
  `changes` filled, already scheduled for deletion one hour after creation."
  [file-id created-at]
  (insert-change file-id {:created-at created-at
                          :changes    (blob/encode {:foo "bar"})
                          :deleted-at (ct/plus created-at revision-lifetime)}))

(defn- insert-file-data
  "A `file_data` payload. A revision payload shares the id and the deletion
  schedule of its revision; a snapshot payload carries no date of its own
  until the file is deleted."
  [file-id id {:keys [type created-at deleted-at]}]
  (th/db-insert! :file-data
                 {:file-id     file-id
                  :id          id
                  :created-at  created-at
                  :modified-at created-at
                  :type        (or type "revision")
                  :backend     "db"
                  :data        (blob/encode {:redo-changes [] :undo-changes []})
                  :deleted-at  deleted-at}
                 {::db/return-keys false}))

(defn- find-change
  [id]
  (th/db-get :file-change {:id id} {::db/remove-deleted false}))

(defn- find-file-data
  [file-id id]
  (th/db-get :file-data {:file-id file-id :id id} {::db/remove-deleted false}))

(defn- find-file
  [id]
  (th/db-get :file {:id id} {::db/remove-deleted false}))

(defn- get-revision-data
  [row]
  (db/run! th/*system*
           (fn [cfg]
             (->> (fdata/resolve-file-data cfg row)
                  :data
                  blob/decode))))

(defn- restore-file
  [file-id]
  (db/tx-run! th/*system*
              (fn [{:keys [::db/conn]}]
                (#'files/restore-files conn [file-id]))))

(defn- save-file
  [command profile file]
  (let [changes [(th/add-rect-change (first (get-in file [:data :pages])) (uuid/next))]
        params  {::th/type command
                 ::rpc/profile-id (:id profile)
                 :id (:id file)
                 :session-id (uuid/next)
                 :revn (:revn file)
                 :vern (:vern file)
                 :features (:features file)}
        params  (if (= :update-file command)
                  (assoc params :changes changes)
                  (assoc params :commits [{:commit-id (uuid/next) :changes changes}]))
        result  (th/command! params)]
    (t/is (nil? (:error result)))
    (-> result :result (get (if (= :update-file command) :lagged :written)) peek :id)))

(defn- request-delete
  [profile file]
  (let [result (th/command! {::th/type :delete-file
                             ::rpc/profile-id (:id profile)
                             :id (:id file)})]
    (t/is (nil? (:error result)))
    (th/run-pending-jobs)))

(defn- request-restore
  [profile file]
  (let [result (th/command! {::th/type :restore-deleted-team-files
                             ::rpc/profile-id (:id profile)
                             :team-id (:default-team-id profile)
                             :ids #{(:id file)}})]
    (t/is (nil? (:error result)))
    (-> result :result th/consume-sse peek peek)))

(t/deftest new-revisions-and-payloads-expire-only-without-retention
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[profile off-file] (create-file 1)
          [v1-owner v1-file]  (create-file 2)
          [v2-owner v2-file]  (create-file 3)
          old-id             (binding [cf/flags (disj cf/flags :file-change-retention)]
                               (save-file :update-file profile off-file))
          v1-id              (binding [cf/flags (conj cf/flags :file-change-retention)]
                               (save-file :update-file v1-owner v1-file))
          v2-id              (binding [cf/flags (conj cf/flags :file-change-retention)]
                               (save-file :update-file-v2 v2-owner v2-file))]
      (t/is (false? (:retained (find-change old-id))))
      (t/is (not (:retained (fdata/decode-metadata (:metadata (find-file-data (:id off-file) old-id))))))
      (t/is (= (ct/plus test-now revision-lifetime) (:deleted-at (find-change old-id))))
      (t/is (= (ct/plus test-now revision-lifetime) (:deleted-at (find-file-data (:id off-file) old-id))))
      (binding [ct/*clock* (ct/fixed-clock (ct/plus test-now (ct/duration {:hours 2})))]
        (th/run-task! :objects-gc {}))
      (t/is (nil? (find-change old-id)))
      (t/is (nil? (find-file-data (:id off-file) old-id)))
      (doseq [[file id] [[v1-file v1-id] [v2-file v2-id]]]
        (let [row     (find-change id)
              payload (find-file-data (:id file) id)]
          (t/is (true? (:retained row)))
          (t/is (nil? (:deleted-at row)))
          (t/is (true? (:retained (fdata/decode-metadata (:metadata payload)))))
          (t/is (nil? (:deleted-at payload)))
          (t/is (seq (:redo-changes (blob/decode (:data payload))))))))))

(t/deftest a-restored-file-keeps-its-offloaded-revision
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[profile file] (create-file 1)
          file-id        (:id file)
          revision-id    (binding [cf/flags (conj cf/flags :file-change-retention)]
                           (save-file :update-file profile file))
          payload        (blob/decode (:data (find-file-data file-id revision-id)))]
      (t/is (pos? (:offloaded (th/run-task! :offload-file-data {:file-id file-id}))))
      (let [offloaded (find-file-data file-id revision-id)]
        (t/is (= "storage" (:backend offloaded)))
        (t/is (true? (:retained (fdata/decode-metadata (:metadata offloaded)))))
        (t/is (= [revision-id] (mapv :id (th/db-query :file-change {:file-id file-id
                                                                    :kind fchanges/revision-kind}))))
        (t/is (= payload (get-revision-data offloaded))))
      (request-delete profile file)
      (let [deadline (:deleted-at (find-file file-id))]
        (t/is (= deadline (:deleted-at (find-change revision-id))))
        (t/is (= deadline (:deleted-at (find-file-data file-id revision-id))))
        (t/is (= #{file-id} (request-restore profile file)))
        (binding [ct/*clock* (ct/fixed-clock deadline)]
          (th/run-task! :objects-gc {})))
      (let [row      (find-change revision-id)
            restored (find-file-data file-id revision-id)]
        (t/is (true? (:retained row)))
        (t/is (nil? (:deleted-at row)))
        (t/is (nil? (:deleted-at restored)))
        (t/is (= payload (get-revision-data restored)))))))

(t/deftest a-restore-admitted-before-expiry-keeps-both-rows-during-gc
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[profile file] (create-file 1)
          file-id        (:id file)
          revision-id    (binding [cf/flags (conj cf/flags :file-change-retention)]
                           (save-file :update-file profile file))
          payload        (blob/decode (:data (find-file-data file-id revision-id)))
          restore        @#'files/restore-files
          admitted       (promise)
          release        (promise)]
      (request-delete profile file)
      (let [deadline (:deleted-at (find-file file-id))]
        (with-redefs-fn {#'files/restore-files
                         (fn [conn ids]
                           (deliver admitted true)
                           (when (= ::timeout (deref release 15000 ::timeout))
                             (throw (ex-info "Timed out waiting for GC" {})))
                           (restore conn ids))}
          (fn []
            (let [restoring (future
                              (binding [ct/*clock* (ct/fixed-clock (ct/minus deadline (ct/duration {:millis 1})))]
                                (request-restore profile file)))]
              (try
                (t/is (= true (deref admitted 10000 ::timeout)))
                (binding [ct/*clock* (ct/fixed-clock deadline)]
                  (th/run-task! :objects-gc {}))
                (t/is (some? (find-change revision-id)))
                (t/is (some? (find-file-data file-id revision-id)))
                (finally
                  (deliver release true)))
              (t/is (= #{file-id} (deref restoring 10000 ::timeout)))))))
      (t/is (nil? (:deleted-at (find-file file-id))))
      (let [restored (find-file-data file-id revision-id)]
        (t/is (nil? (:deleted-at (find-change revision-id))))
        (t/is (nil? (:deleted-at restored)))
        (t/is (= payload (some-> restored :data blob/decode))))
      (t/is (= 1 (:revn (bfc/get-file th/*system* file-id :realize? true)))))))

(t/deftest gc-at-the-deadline-prevents-restore-before-the-file-row-is-deleted
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[profile file] (create-file 1)
          file-id        (:id file)
          revision-id    (binding [cf/flags (conj cf/flags :file-change-retention)]
                           (save-file :update-file profile file))
          purge-files    @#'objects-gc/delete-files-chunk
          rows-deleted   (promise)
          release        (promise)]
      (request-delete profile file)
      (binding [ct/*clock* (ct/fixed-clock (:deleted-at (find-file file-id)))]
        (with-redefs-fn {#'objects-gc/delete-files-chunk
                         (fn [cfg ids]
                           (deliver rows-deleted true)
                           (when (= ::timeout (deref release 15000 ::timeout))
                             (throw (ex-info "Timed out waiting for restore" {})))
                           (purge-files cfg ids))}
          (fn []
            (let [collecting (future (th/run-task! :objects-gc {}))]
              (try
                (t/is (= true (deref rows-deleted 10000 ::timeout)))
                (t/is (some? (find-file file-id)))
                (t/is (nil? (find-change revision-id)))
                (t/is (nil? (find-file-data file-id revision-id)))
                (t/is (= #{} (request-restore profile file)))
                (finally
                  (deliver release true)))
              (t/is (map? (deref collecting 10000 ::timeout)))))))
      (t/is (nil? (find-file file-id))))))

(t/deftest permanent-delete-collects-an-offloaded-revision-and-its-file
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[profile file] (create-file 1)
          file-id        (:id file)
          revision-id    (binding [cf/flags (conj cf/flags :file-change-retention)]
                           (save-file :update-file profile file))]
      (th/run-task! :offload-file-data {:file-id file-id})
      (let [storage-id (:storage-object-id (find-file-data file-id revision-id))]
        (t/is (uuid? storage-id))
        (request-delete profile file)
        (binding [ct/*clock* (ct/fixed-clock (ct/plus test-now (ct/duration {:days 1})))]
          (let [result (th/command! {::th/type :permanently-delete-team-files
                                     ::rpc/profile-id (:id profile)
                                     :team-id (:default-team-id profile)
                                     :ids #{file-id}})]
            (t/is (nil? (:error result)))
            (t/is (= #{file-id} (-> result :result th/consume-sse peek peek))))
          (t/is (= #{} (request-restore profile file)))
          (th/run-pending-jobs)
          (t/is (= (ct/now) (:deleted-at (find-change revision-id))))
          (t/is (= (ct/now) (:deleted-at (find-file-data file-id revision-id))))
          (th/run-task! :objects-gc {})
          (t/is (nil? (find-change revision-id)))
          (t/is (nil? (find-file-data file-id revision-id)))
          (t/is (nil? (find-file file-id)))
          (t/is (= #{} (request-restore profile file)))
          (t/is (some? (:touched-at (th/db-get :storage-object {:id storage-id}))))
          (th/run-task! :storage-gc-touched {:skip-delay true})
          (t/is (some? (:deleted-at (th/db-get :storage-object {:id storage-id}
                                               {::db/remove-deleted false})))))))))

(t/deftest restoring-a-file-leaves-the-revision-schedule-alone
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[_ file] (create-file 1)
          file-id   (:id file)
          trash-at  (ct/in-future {:days 7})
          old-at    (ct/in-past {:hours 2})

          revision-id (insert-revision file-id old-at)
          revision-at (:deleted-at (find-change revision-id))
          _           (insert-file-data file-id revision-id
                                        {:created-at old-at
                                         :deleted-at revision-at})

          snapshot-id (insert-change file-id {:created-at old-at
                                              :created-by "user"
                                              :label      "a snapshot"
                                              :deleted-at trash-at})

          legacy-id   (insert-change file-id {:created-at old-at
                                              :data       (blob/encode {:legacy true})
                                              :deleted-at trash-at})]

      (restore-file file-id)

      (t/testing "the revision keeps the schedule it was born with"
        (t/is (= revision-at (:deleted-at (find-change revision-id)))))

      (t/testing "the revision payload keeps the schedule it was born with"
        (t/is (= revision-at
                 (:deleted-at (find-file-data file-id revision-id)))))

      (t/testing "the snapshot comes back"
        (t/is (nil? (:deleted-at (find-change snapshot-id)))))

      (t/testing "the legacy payload row keeps its date"
        (t/is (= trash-at (:deleted-at (find-change legacy-id))))))))

(t/deftest deleting-a-file-does-not-push-the-revision-schedule-out
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[_ file] (create-file 1)
          file-id   (:id file)
          trash-at  (ct/in-future {:days 7})
          old-at    (ct/in-past {:hours 2})
          system-at (ct/in-future {:days 2})

          revision-id (insert-revision file-id old-at)
          revision-at (:deleted-at (find-change revision-id))

          snapshot-id (insert-change file-id {:created-at old-at
                                              :created-by "user"
                                              :label      "a snapshot"})

          system-id   (insert-change file-id {:created-at old-at
                                              :label      "internal/snapshot/1"
                                              :deleted-at system-at})]

      (th/db-update! :file {:deleted-at trash-at} {:id file-id})
      (th/run-task! :delete-object {:object     :file
                                    :deleted-at trash-at
                                    :id         file-id})

      (t/testing "the revision keeps the schedule it was born with"
        (t/is (= revision-at (:deleted-at (find-change revision-id)))))

      (t/testing "the snapshot takes the file's deletion date"
        (t/is (= (:deleted-at (find-file file-id))
                 (:deleted-at (find-change snapshot-id)))))

      (t/testing "the system snapshot keeps the date it already had"
        (t/is (= system-at (:deleted-at (find-change system-id))))))))

(t/deftest updating-a-snapshot-cannot-reach-a-revision
  ;; The snapshot paths clear the deletion mark of the row they touch, which is
  ;; only legitimate for durable history. They reach a row through
  ;; `sql:snapshots`, which filters `label IS NOT NULL`, and a revision never
  ;; carries one. This test pins that: the row has to keep the date it was born
  ;; with, and the RPC has to reject the id. If a future change loosens that
  ;; read filter, this goes red instead of the revision losing its date in
  ;; silence.
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[profile file] (create-file 1)
          file-id        (:id file)
          revision-id    (insert-revision file-id (ct/in-past {:hours 2}))
          revision-at    (:deleted-at (find-change revision-id))

          out (th/command! {::th/type :update-file-snapshot
                            ::rpc/profile-id (:id profile)
                            :file-id file-id
                            :id revision-id
                            :label "renamed"})]

      (t/testing "the RPC rejects the id"
        (t/is (th/ex-info? (:error out)))
        (t/is (= :not-found (:type (ex-data (:error out))))))

      (t/testing "and the revision keeps the date it was born with"
        (t/is (= revision-at (:deleted-at (find-change revision-id))))))))

(t/deftest a-deleted-then-restored-file-does-not-leak-its-revisions
  ;; The lifecycle that leaked 358,517 rows in production: a file is edited, it
  ;; ends up in the trash, it comes back, and the revisions that were scheduled
  ;; to die an hour after they were written have to die anyway. The payload of
  ;; a revision follows the same rule: it is born with the revision's schedule,
  ;; deleting the file does not push it out and restoring the file does not
  ;; clear it.
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[_ file] (create-file 1)
          file-id   (:id file)
          old-at    (ct/in-past {:hours 2})

          revision-id (insert-revision file-id old-at)
          revision-at (:deleted-at (find-change revision-id))

          _ (insert-file-data file-id revision-id
                              {:created-at old-at
                               :deleted-at revision-at})]

      (let [trash-at (ct/in-future {:days 7})]
        (th/db-update! :file {:deleted-at trash-at} {:id file-id})
        (th/run-task! :delete-object {:object     :file
                                      :deleted-at trash-at
                                      :id         file-id}))

      (t/testing "deleting the file leaves the revision schedule alone"
        (t/is (= revision-at (:deleted-at (find-change revision-id))))
        (t/is (= revision-at
                 (:deleted-at (find-file-data file-id revision-id)))))

      (restore-file file-id)

      (t/testing "restoring the file leaves it alone too"
        (t/is (= revision-at (:deleted-at (find-change revision-id))))
        (t/is (= revision-at
                 (:deleted-at (find-file-data file-id revision-id)))))

      (th/run-task! :objects-gc {})

      (t/testing "so the collector takes it"
        (t/is (nil? (find-change revision-id)))
        (t/is (nil? (find-file-data file-id revision-id)))))))

(t/deftest deleting-a-file-keeps-the-schedule-a-payload-was-born-with
  ;; Only the payloads without a date take the file's deletion date. A revision
  ;; payload already has one, and a snapshot payload (the other undated row)
  ;; still gets the trash lifetime, so the restoration of a deleted file keeps
  ;; finding it.
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[_ file] (create-file 1)
          file-id  (:id file)
          trash-at (ct/in-future {:days 7})
          old-at   (ct/in-past {:hours 2})
          rev-id   (uuid/next)
          snap-id  (uuid/next)
          rev-at   (ct/plus old-at revision-lifetime)]

      (insert-file-data file-id rev-id {:created-at old-at :deleted-at rev-at})
      (insert-file-data file-id snap-id {:type "snapshot" :created-at old-at})

      (th/db-update! :file {:deleted-at trash-at} {:id file-id})
      (th/run-task! :delete-object {:object     :file
                                    :deleted-at trash-at
                                    :id         file-id})

      (t/testing "the revision payload keeps the date it was born with"
        (t/is (= rev-at (:deleted-at (find-file-data file-id rev-id)))))

      (t/testing "the undated payload takes the file's deletion date"
        (t/is (= trash-at (:deleted-at (find-file-data file-id snap-id))))))))
