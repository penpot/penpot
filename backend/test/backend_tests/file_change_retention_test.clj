;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-change-retention-test
  "Retention of `file_change` rows through the file lifecycle.

  A file has two kinds of row. Durable history: the snapshots, which a restore
  brings back. And ephemeral revisions: the xlog of an edit, born already
  scheduled for deletion one hour later, which nobody needs once the sessions
  that were going to read them are gone.

  The invariant under test is that a deletion date is assigned once and never
  moved: restoring a file must not unmark a revision, and deleting a file must
  not push a revision's date out to the trash lifetime. When either happens the
  revision becomes invisible to `objects-gc`, which only looks at rows that
  carry a date, and it stays in the table for ever."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
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

(defn- find-change
  [id]
  (th/db-get :file-change {:id id} {::db/remove-deleted false}))

(defn- find-file
  [id]
  (th/db-get :file {:id id} {::db/remove-deleted false}))

(defn- restore-file
  [file-id]
  (db/tx-run! th/*system*
              (fn [{:keys [::db/conn]}]
                (#'files/restore-files conn [file-id]))))

(t/deftest restoring-a-file-leaves-the-revision-schedule-alone
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[_ file] (create-file 1)
          file-id   (:id file)
          trash-at  (ct/in-future {:days 7})
          old-at    (ct/in-past {:hours 2})

          revision-id (insert-revision file-id old-at)
          revision-at (:deleted-at (find-change revision-id))

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

      (t/testing "the snapshot comes back"
        (t/is (nil? (:deleted-at (find-change snapshot-id)))))

      (t/testing "the legacy payload row comes back"
        (t/is (nil? (:deleted-at (find-change legacy-id))))))))

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
  ;; to die an hour after they were written have to die anyway.
  (binding [ct/*clock* (ct/fixed-clock test-now)]
    (let [[_ file] (create-file 1)
          file-id   (:id file)

          revision-id (insert-revision file-id (ct/in-past {:hours 2}))
          revision-at (:deleted-at (find-change revision-id))]

      (th/run-task! :delete-object {:object     :file
                                    :deleted-at (ct/in-future {:days 7})
                                    :id         file-id})

      (t/testing "deleting the file leaves the revision schedule alone"
        (t/is (= revision-at (:deleted-at (find-change revision-id)))))

      (restore-file file-id)

      (t/testing "restoring the file leaves it alone too"
        (t/is (= revision-at (:deleted-at (find-change revision-id)))))

      (th/run-task! :objects-gc {})

      (t/testing "so the collector takes it"
        (t/is (nil? (find-change revision-id)))))))
