;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-change-refs-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-change-refs :as refs]
   [app.features.file-changes :as fchanges]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-file
  [i]
  (let [profile (th/create-profile* i)]
    (:id (th/create-file* i {:profile-id (:id profile)
                             :project-id (:default-project-id profile)}))))

(defn- change-key
  [file-id change-id]
  {:file-id file-id :id change-id})

(defn- create-snapshot
  [file-id params]
  (let [id (uuid/next)]
    (th/db-insert! :file-change (merge (change-key file-id id)
                                       {:kind fchanges/snapshot-kind :label "snapshot"}
                                       params))
    id))

(defn- put-snapshot-data
  [file-id change-id backend payload & {:as params}]
  (fdata/upsert! th/*system*
                 (merge (change-key file-id change-id)
                        {:type "snapshot"
                         :backend backend
                         :storage-object-id nil
                         :data payload}
                        params)))

(defn- get-fragment-ids
  [file-id change-id]
  (some-> (th/db-get :file-change-refs {:file-id file-id
                                        :file-change-id change-id})
          :fragment-ids
          db/pgarray->vector))

(defn- index-change
  [file-id change-id]
  (db/run! th/*system* refs/index-change file-id change-id))

(t/deftest indexes-a-snapshot-by-kind-without-a-label
  (let [file-id (create-file 1)
        id      (create-snapshot file-id {:label nil})
        pointer (uuid/next)
        payload (blob/encode {:components (pmap/create pointer {})})]
    (put-snapshot-data file-id id "db" payload)
    (index-change file-id id)
    (t/is (= [pointer] (get-fragment-ids file-id id)))))

(t/deftest indexes-a-revision-by-kind-even-with-a-label
  (let [file-id (create-file 1)
        id      (uuid/next)]
    (th/db-insert! :file-change (assoc (change-key file-id id)
                                       :kind fchanges/revision-kind
                                       :label "not a snapshot"))
    (index-change file-id id)
    (t/is (= [] (get-fragment-ids file-id id)))))

(t/deftest a-change-without-kind-stays-uncovered
  (let [file-id (create-file 1)
        id      (uuid/next)]
    (th/db-insert! :file-change (change-key file-id id))
    (t/is (= :invalid-file-change-kind
             (:code (ex-data (ex/try! (index-change file-id id))))))
    (t/is (= [id] (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest indexes-a-marked-snapshot-without-loading-fragments
  (let [file-id      (create-file 1)
        expired-at   (ct/in-past "1h")
        change-id    (create-snapshot file-id {:deleted-at expired-at :created-by "user"})
        component-id (uuid/next)
        page-id      (uuid/next)
        page-pointer (uuid/next)
        payload      (blob/encode {:components (pmap/create component-id {})
                                   :pages-index {page-id (pmap/create page-pointer {})}})]
    ;; The pointers do not even have fragment rows: indexing reads only
    ;; their ids, not their contents.
    (put-snapshot-data file-id change-id "db" payload :deleted-at expired-at)

    (t/is (= [change-id] (refs/get-missing-change-ids th/*system* file-id 10)))
    (t/is (true? (index-change file-id change-id)))
    (t/is (false? (index-change file-id change-id)))

    (t/is (= #{component-id page-pointer} (set (get-fragment-ids file-id change-id))))
    (t/is (= [] (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest indexes-legacy-data-and-valid-payloads-without-pointers
  (let [file-id   (create-file 1)
        pointer   (uuid/next)
        legacy-id (create-snapshot file-id {:data (blob/encode {:components (pmap/create pointer {})})})
        empty-id  (create-snapshot file-id {})]
    (put-snapshot-data file-id empty-id "db" (blob/encode {}))

    (index-change file-id legacy-id)
    (index-change file-id empty-id)

    (t/is (= [pointer] (get-fragment-ids file-id legacy-id)))
    (t/is (= [] (get-fragment-ids file-id empty-id)))
    (t/is (= [] (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest offload-preserves-the-references-of-the-same-persisted-payload
  (let [file-id   (create-file 1)
        hot-id    (create-snapshot file-id {})
        cold-id   (create-snapshot file-id {})
        pointer   (uuid/next)
        payload   (blob/encode {:components (pmap/create pointer {})})]
    (put-snapshot-data file-id hot-id "db" payload)
    (put-snapshot-data file-id cold-id "db" payload)
    (index-change file-id hot-id)

    (th/run-task! :offload-file-data {:file-id file-id})
    (t/is (= "storage" (:backend (th/db-get :file-data (change-key file-id cold-id)))))
    (index-change file-id cold-id)

    (t/is (= [pointer] (get-fragment-ids file-id hot-id)))
    (t/is (= [pointer] (get-fragment-ids file-id cold-id)))))

(t/deftest coverage-is-bounded-and-uses-the-full-key
  (let [file-a   (create-file 1)
        file-b   (create-file 2)
        id       #uuid "00000000-0000-0000-0000-000000000001"
        later-id #uuid "00000000-0000-0000-0000-000000000002"]
    (th/db-insert! :file-change (assoc (change-key file-a id) :kind fchanges/revision-kind))
    (th/db-insert! :file-change (assoc (change-key file-a later-id) :kind fchanges/revision-kind))
    ;; A revision can even have the legacy data column filled. Its changes
    ;; and the legacy data are not snapshots and neither should be decoded.
    (th/db-insert! :file-change (assoc (change-key file-b id)
                                       :kind fchanges/revision-kind
                                       :changes (byte-array [1])
                                       :data (byte-array [2])
                                       :deleted-at (ct/in-past "1h")))
    (index-change file-b id)

    (t/is (= [] (get-fragment-ids file-b id)))
    (t/is (= [id] (refs/get-missing-change-ids th/*system* file-a 1)))
    (t/is (= [later-id] (refs/get-missing-change-ids th/*system* file-a 1 :after-id id)))
    (t/is (= [id later-id] (refs/get-missing-change-ids th/*system* file-a 10)))
    (index-change file-a id)
    (t/is (= [later-id] (refs/get-missing-change-ids th/*system* file-a 10)))))

(t/deftest does-not-count-invalid-snapshot-payloads-as-covered
  (let [file-id (create-file 1)]
    (doseq [[label payload] [["encoded nil" (blob/encode nil)]
                             ["encoded vector" (blob/encode [])]
                             ["invalid bytes" (byte-array [1])]]]
      (t/testing label
        (let [id (create-snapshot file-id {:label label})]
          (put-snapshot-data file-id id "db" payload)
          (t/is (thrown? Exception (index-change file-id id)))
          (t/is (nil? (get-fragment-ids file-id id)))
          (t/is (some #{id} (refs/get-missing-change-ids th/*system* file-id 10))))))))

(t/deftest does-not-count-an-unreadable-storage-object-as-covered
  (let [file-id (create-file 1)
        id      (create-snapshot file-id {})
        object  (:storage-object-id (put-snapshot-data file-id id "storage" (blob/encode {})))]
    (sto/del-object! (::sto/storage th/*system*) object)

    (t/is (thrown-with-msg? Exception #"no readable storage object"
                            (index-change file-id id)))
    (t/is (= [id] (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest payload-purged-before-metadata-leaves-a-coverage-gap
  (let [file-id (create-file 1)
        id      (create-snapshot file-id {})]
    ;; Give only the payload a due date to exercise the state between the
    ;; transactions that purge the payload and the metadata.
    (put-snapshot-data file-id id "db" (blob/encode {}) :deleted-at (ct/in-past "1h"))
    (th/run-task! :objects-gc {})

    (t/is (nil? (th/db-get :file-data (change-key file-id id) {::db/remove-deleted false})))
    (t/is (false? (index-change file-id id)))
    (t/is (= [id] (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest skips-a-row-purged-after-reading-its-payload
  (let [file-id    (create-file 1)
        expired-at (ct/in-past "1h")
        id         (create-snapshot file-id {:deleted-at expired-at})
        pointer    (uuid/next)
        payload    (blob/encode {:components (pmap/create pointer {})})
        get-bytes  sto/get-object-bytes]
    (put-snapshot-data file-id id "storage" payload :deleted-at expired-at)
    ;; Interleave at the storage boundary, with actual bytes and a purge on
    ;; independent connections. The source is gone before publication.
    (with-redefs [sto/get-object-bytes (fn [storage object]
                                         (let [result (get-bytes storage object)]
                                           (th/run-task! :objects-gc {})
                                           result))]
      (t/is (false? (index-change file-id id))))

    (t/is (nil? (th/db-get :file-change (change-key file-id id) {::db/remove-deleted false})))
    (t/is (nil? (get-fragment-ids file-id id)))
    (t/is (= [] (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest propagates-a-storage-read-failure-even-if-the-snapshot-is-purged
  (let [file-id    (create-file 1)
        expired-at (ct/in-past "1h")
        id         (create-snapshot file-id {:deleted-at expired-at})]
    (put-snapshot-data file-id id "storage" (blob/encode {}) :deleted-at expired-at)
    (with-redefs [sto/get-object-bytes (fn [_ _]
                                         (th/run-task! :objects-gc {})
                                         (throw (ex-info "snapshot payload disappeared" {})))]
      (t/is (thrown-with-msg? Exception #"snapshot payload disappeared"
                              (index-change file-id id))))

    (t/is (nil? (get-fragment-ids file-id id)))
    (t/is (empty? (refs/get-missing-change-ids th/*system* file-id 10)))))

(t/deftest purging-revisions-snapshots-and-their-file-removes-derived-rows
  (let [file-id    (create-file 1)
        expired-at (ct/in-past "1h")
        snapshot   (create-snapshot file-id {:deleted-at expired-at})
        revision   (uuid/next)]
    (put-snapshot-data file-id snapshot "db" (blob/encode {}) :deleted-at expired-at)
    (th/db-insert! :file-change (assoc (change-key file-id revision)
                                       :kind fchanges/revision-kind
                                       :deleted-at expired-at))
    (th/db-update! :file-data {:deleted-at expired-at} {:file-id file-id})
    (th/db-update! :file {:deleted-at expired-at} {:id file-id})
    (index-change file-id snapshot)
    (index-change file-id revision)
    (t/is (= [] (get-fragment-ids file-id snapshot)))
    (t/is (= [] (get-fragment-ids file-id revision)))

    (th/run-task! :objects-gc {})
    (index-change file-id snapshot)
    (index-change file-id revision)

    (t/is (nil? (th/db-get :file {:id file-id} {::db/remove-deleted false})))
    (t/is (empty? (th/db-query :file-change-refs {:file-id file-id})))))

(defn- get-partition-bounds
  [table]
  (set (map :bounds (th/db-exec! ["SELECT pg_get_expr(c.relpartbound, c.oid) AS bounds
                                    FROM pg_inherits i
                                    JOIN pg_class c ON c.oid = i.inhrelid
                                   WHERE i.inhparent = ?::regclass"
                                  table]))))

(t/deftest partition-bounds-match-the-source-and-file-data
  (let [bounds (get-partition-bounds "file_change_refs")]
    (t/is (= 16 (count bounds)))
    (t/is (= (get-partition-bounds "file_change") bounds))
    (t/is (= (get-partition-bounds "file_data") bounds))))
