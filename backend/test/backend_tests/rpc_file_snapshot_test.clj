;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-snapshot-test
  (:require
   [app.common.features :as cfeat]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (:result out)))

(t/deftest snapshots-crud
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id profile)
        proj-id (:default-project-id profile)

        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id proj-id
                                    :is-shared false})
        snapshot-id (volatile! nil)]

    (t/testing "create snapshot"
      (let [params {::th/type :create-file-snapshot
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)
                    :label "label1"}
            out    (th/command! params)]
        ;; (th/print-result! out)

        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (= "label1" (:label result)))
          (t/is (uuid? (:id result)))
          (vswap! snapshot-id (constantly (:id result))))))

    (t/testing "list snapshots"
      (let [params {::th/type :get-file-snapshots
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [[row :as result] (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= "label1" (:label row)))
          (t/is (uuid? (:id row)))
          (t/is (= @snapshot-id (:id row)))
          (t/is (= 0 (:revn row)))
          (t/is (= (:id profile) (:profile-id row))))))

    (t/testing "update snapshot with file-id"
      (let [params {::th/type :update-file-snapshot
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)
                    :id @snapshot-id
                    :label "label1-updated"}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))
        (t/is (= "label1-updated"
                 (:label (th/db-get :file-change {:id @snapshot-id}))))))

    (t/testing "restore snapshot"
      (let [params {::th/type :restore-file-snapshot
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)
                    :id @snapshot-id}
            out    (th/command! params)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out))))

      (let [[row1 row2 :as rows]
            (th/db-query :file-change
                         {:file-id (:id file)}
                         {:order-by [:created-at]})]

        (t/is (= 2 (count rows)))
        (t/is (= "user" (:created-by row1)))
        (t/is (= "system" (:created-by row2)))))

    (t/testing "delete snapshot"
      (let [[row1 row2 :as rows]
            (th/db-query :file-change
                         {:file-id (:id file)}
                         {:order-by [:created-at]})]

        (t/testing "delete user created snapshot"
          (let [params {::th/type :delete-file-snapshot
                        ::rpc/profile-id (:id profile)
                        :file-id (:id file)
                        :id (:id row1)}
                out    (th/command! params)]

            ;; (th/print-result! out)
            (t/is (nil? (:error out)))
            (t/is (true? (:result out)))))

        (t/testing "delete system created snapshot"
          (let [params {::th/type :delete-file-snapshot
                        ::rpc/profile-id (:id profile)
                        :file-id (:id file)
                        :id (:id row2)}
                out    (th/command! params)]

            ;; (th/print-result! out)
            (let [error (:error out)
                  data  (ex-data error)]
              (t/is (th/ex-info? error))
              (t/is (= (:type data) :validation))
              (t/is (= (:code data) :system-snapshots-cant-be-deleted)))))

        ;; this will run pending task triggered by deleting user snapshot
        (th/run-pending-tasks!)

        (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
          (let [res (th/run-task! :objects-gc {})]
            ;; delete 2 snapshots and 2 file data entries
            (t/is (= 4 (:processed res)))))))))

(t/deftest snapshots-locking
  (let [profile-1 (th/create-profile* 1 {:is-active true})
        profile-2 (th/create-profile* 2 {:is-active true})

        team
        (th/create-team* 1 {:profile-id (:id profile-1)})

        project
        (th/create-project* 1 {:profile-id (:id profile-1)
                               :team-id (:id team)})

        file
        (th/create-file* 1 {:profile-id (:id profile-1)
                            :project-id (:id project)
                            :is-shared false})

        snapshot
        (let [params {::th/type :create-file-snapshot
                      ::rpc/profile-id (:id profile-1)
                      :file-id (:id file)
                      :label "label1"}
              out    (th/command! params)]
          ;; (th/print-result! out)

          (t/is (nil? (:error out)))
          (:result out))]

    ;; Add the secont profile to the team
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id profile-2)
                           :role :admin})

    (t/testing "lock snapshot"
      (let [params {::th/type :lock-file-snapshot
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))

        (let [snapshot (th/db-get :file-change {:id (:id snapshot)} {::db/remove-deleted false})]
          (t/is (= (:id profile-1) (:locked-by snapshot))))))

    (t/testing "delete locked snapshot"
      (let [params {::th/type :delete-file-snapshot
                    ::rpc/profile-id (:id profile-2)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]

        ;; (th/print-result! out)
        (let [error (:error out)
              data  (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type data) :validation))
          (t/is (= (:code data) :snapshot-is-locked)))))

    (t/testing "unlock snapshot"
      (let [params {::th/type :unlock-file-snapshot
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))

        (let [snapshot (th/db-get :file-change {:id (:id snapshot)})]
          (t/is (= nil (:locked-by snapshot))))))

    (t/testing "delete locked snapshot"
      (let [params {::th/type :delete-file-snapshot
                    ::rpc/profile-id (:id profile-2)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))))))

(t/deftest snapshots-require-file-id
  ;; Since version 2.19 the snapshot commands require the `file-id`
  ;; parameter, so partition pruning can always be applied on the
  ;; `file_change` table.
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id profile)
        proj-id (:default-project-id profile)

        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id proj-id
                                    :is-shared false})

        snapshot
        (let [params {::th/type :create-file-snapshot
                      ::rpc/profile-id (:id profile)
                      :file-id (:id file)
                      :label "label1"}
              out    (th/command! params)]
          ;; (th/print-result! out)

          (t/is (nil? (:error out)))
          (:result out))]

    (doseq [type [:update-file-snapshot :lock-file-snapshot
                  :unlock-file-snapshot :delete-file-snapshot]]
      (let [params (merge {::th/type type
                           ::rpc/profile-id (:id profile)
                           :id (:id snapshot)}
                          (when (= type :update-file-snapshot)
                            {:label "label2"}))
            out    (th/command! params)]

        (t/testing (str (name type) " without file-id")
          (t/is (th/ex-of-code? (:error out) :params-validation)))))))

(t/deftest snapshots-reject-a-file-id-that-does-not-match
  ;; The snapshot commands filter by `file_id` so that, once `file_change` is
  ;; hash-partitioned by it, the lookup prunes to a single partition. Sending
  ;; a `file-id` of another file must not find nor touch the snapshot.
  (let [profile  (th/create-profile* 1 {:is-active true})
        file-a   (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})
        file-b   (th/create-file* 2 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})

        snapshot (-> (th/command! {::th/type :create-file-snapshot
                                   ::rpc/profile-id (:id profile)
                                   :file-id (:id file-a)
                                   :label "label1"})
                     :result)]

    (doseq [type [:update-file-snapshot :lock-file-snapshot
                  :unlock-file-snapshot :delete-file-snapshot]]
      (let [params (merge {::th/type type
                           ::rpc/profile-id (:id profile)
                           :file-id (:id file-b)
                           :id (:id snapshot)}
                          (when (= type :update-file-snapshot)
                            {:label "label2"}))
            out    (th/command! params)]

        (t/testing (name type)
          (t/is (th/ex-info? (:error out)))
          (t/is (= :not-found (:type (ex-data (:error out)))))
          (t/is (= :snapshot-not-found (:code (ex-data (:error out)))))
          (t/is (= "label1"
                   (:label (th/db-get :file-change {:id (:id snapshot)}
                                      {::db/remove-deleted false})))
                "the snapshot of file-a stays intact"))))))

(t/deftest file-change-mutations-maintain-updated-at
  ;; `updated_at` is the per-row watermark used by the online partitioning
  ;; copy of `file_change` (hash partitioning by file_id): every mutation
  ;; must bump it, otherwise the copy misses the row. Lock, unlock and the
  ;; soft-delete used to leave it stale.
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})

        snapshot
        (let [params {::th/type :create-file-snapshot
                      ::rpc/profile-id (:id profile)
                      :file-id (:id file)
                      :label "label1"}
              out    (th/command! params)]
          ;; (th/print-result! out)

          (t/is (nil? (:error out)))
          (:result out))

        stale-at #inst "2020-01-01T00:00:00.000Z"]

    (doseq [type [:lock-file-snapshot :unlock-file-snapshot :delete-file-snapshot]]
      (t/testing (name type)
        ;; make the row look old, so the mutation has to bump it
        (th/db-update! :file-change {:updated-at stale-at}
                       {:id (:id snapshot)}
                       {::db/remove-deleted false})

        (let [params {::th/type type
                      ::rpc/profile-id (:id profile)
                      :file-id (:id file)
                      :id (:id snapshot)}
              out    (th/command! params)]

          (t/is (nil? (:error out)))
          (t/is (true? (:result out)))
          (t/is (pos? (- (inst-ms (:updated-at (th/db-get :file-change
                                                          {:id (:id snapshot)}
                                                          {::db/remove-deleted false})))
                         (inst-ms stale-at)))
                "updated_at must be bumped by the mutation"))))

    (t/testing "the deleted snapshot is marked as deleted"
      (t/is (some? (:deleted-at (th/db-get :file-change {:id (:id snapshot)}
                                           {::db/remove-deleted false})))))

    ;; process the delete-object task enqueued by the snapshot delete
    (th/run-pending-tasks!)))

(t/deftest unknown-snapshot-is-reported-as-not-found
  ;; The error points at the id that was not found: the snapshots are looked
  ;; up by id, not by label. Both commands that resolve a snapshot report it
  ;; the same way.
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})

        params  {::rpc/profile-id (:id profile)
                 :file-id (:id file)
                 :id (uuid/random)}]

    (doseq [type [:restore-file-snapshot :get-file-snapshot]]
      (t/testing (name type)
        (let [out (th/command! (assoc params ::th/type type))]

          (t/is (th/ex-of-code? (:error out) :snapshot-not-found))
          (t/is (= "unable to find snapshot with the provided id"
                   (:hint (ex-data (:error out))))))))))

(t/deftest revision-and-snapshot-carry-different-content
  ;; A revision is the delta of a save: it carries the changes and nothing
  ;; else. A snapshot is the state of the file at a point: it carries the
  ;; features and the migrations.
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (uuid/random)]

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test"
       :id page-id}])

    (t/testing "the revision carries the changes and not the features"
      (let [rows     (th/db-query :file-change {:file-id (:id file)})
            revision (first (filter #(nil? (:label %)) rows))]

        (t/is (some? revision))
        (t/is (some? (:changes revision)))
        (t/is (nil? (:features revision)))))

    (t/testing "the snapshot carries the migrations and the features"
      (let [out (th/command! {::th/type :create-file-snapshot
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)
                              :label "label1"})]

        (t/is (nil? (:error out)))

        (let [snapshot (th/db-get :file-change {:id (get-in out [:result :id])})]
          (t/is (= "label1" (:label snapshot)))
          (t/is (some? (:migrations snapshot)))
          (t/is (some? (:features snapshot))))))))

(t/deftest file-change-rows-have-a-kind
  ;; The kind tells the two kinds of rows of `file_change` apart: a snapshot
  ;; (1) and a revision (2). The values are the contract with the database.
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (uuid/random)]

    (update-file!
     :file-id (:id file)
     :profile-id (:id profile)
     :revn 0
     :vern 0
     :changes
     [{:type :add-page
       :name "test"
       :id page-id}])

    (t/testing "the revision is a revision"
      (let [rows     (th/db-query :file-change {:file-id (:id file)})
            revision (first (filter #(nil? (:label %)) rows))]

        (t/is (some? revision))
        (t/is (= 2 (:kind revision)))))

    (t/testing "the snapshot is a snapshot"
      (let [out (th/command! {::th/type :create-file-snapshot
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)
                              :label "label1"})]

        (t/is (nil? (:error out)))

        (let [snapshot (th/db-get :file-change {:id (get-in out [:result :id])})]
          (t/is (= 1 (:kind snapshot))))))))
