;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-snapshot-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
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

(defn- create-edited-file
  "Creates a file with objects-map, with or without `pointer-map?`, and saves
  a rect on it; the save takes a snapshot of the file before the rect."
  [& {:keys [pointer-map?]}]
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false
                                    :features (-> (cfeat/get-enabled-features cf/flags)
                                                  (conj "fdata/objects-map")
                                                  (cond-> (not pointer-map?) (disj "fdata/pointer-map")))})
        page-id (-> file :data :pages first)
        rect-id (uuid/next)]
    (update-file! :file-id (:id file)
                  :profile-id (:id profile)
                  :changes [(th/add-rect-change page-id rect-id)])
    {:profile-id (:id profile)
     :file-id (:id file)
     :page-id page-id
     :rect-id rect-id
     :snapshot-id (-> (th/db-query :file-change {:file-id (:id file) :label "internal/snapshot/0"}
                                   {::db/remove-deleted false})
                      first
                      :id)}))

(defn- snapshot-row
  [{:keys [file-id snapshot-id]}]
  (th/db-get :file-data {:file-id file-id :id snapshot-id :type "snapshot"}
             {::db/remove-deleted false}))

(defn- snapshot-data
  [ctx]
  (-> (snapshot-row ctx) :data blob/decode))

(defn- compaction-tasks
  []
  (th/db-exec! ["select * from task where name = 'compact-file-snapshot' and status = 'new'"]))

(t/deftest update-file-snapshot-is-compacted-later
  (let [{:keys [page-id rect-id] :as ctx} (create-edited-file)
        before (snapshot-row ctx)
        data   (snapshot-data ctx)]

    (t/testing "the save stores the data as it was, objects-map included"
      (t/is (uuid? (:snapshot-id ctx)))
      (t/is (fdata/has-objects-map? data))
      (t/is (not (contains? (get-in data [:pages-index page-id :objects]) rect-id))))

    (t/testing "the save queues the compaction"
      (t/is (= [{:file-id (:file-id ctx) :id (:snapshot-id ctx)}]
               (mapv #(-> % :props db/decode-transit-pgobject) (compaction-tasks)))))

    (th/run-pending-tasks!)

    (t/testing "the task stores the same data with plain objects"
      (let [after (snapshot-row ctx)]
        (t/is (not (fdata/has-objects-map? (snapshot-data ctx))))
        (t/is (= (fdata/process-objects data (partial into {}))
                 (snapshot-data ctx)))
        (t/is (= (:deleted-at before) (:deleted-at after)))
        (t/is (= (:modified-at before) (:modified-at after)))))

    (t/testing "a second compaction does nothing"
      (t/is (nil? (db/tx-run! th/*system* fsnap/compact! (:file-id ctx) (:snapshot-id ctx)))))))

(t/deftest compaction-keeps-a-snapshot-pinned-before-it
  (let [{:keys [profile-id snapshot-id] :as ctx} (create-edited-file)
        out (th/command! {::th/type :update-file-snapshot
                          ::rpc/profile-id profile-id
                          :id snapshot-id
                          :label "pinned"})]
    (t/is (nil? (:error out)))
    (th/run-pending-tasks!)
    (t/is (nil? (:deleted-at (snapshot-row ctx))))
    (t/is (not (fdata/has-objects-map? (snapshot-data ctx))))
    (t/is (= "pinned" (:label (th/db-get :file-change {:id snapshot-id}))))))

(defn- run-compaction
  "Runs the compaction task handler on snapshot `snapshot-id`."
  [{:keys [file-id snapshot-id]}]
  (let [handler (get th/*system* :app.tasks.compact-file-snapshot/handler)]
    (handler {:props {:file-id file-id :id snapshot-id}})))

(t/deftest compaction-of-a-missing-snapshot-does-nothing
  (let [{:keys [file-id snapshot-id] :as ctx} (create-edited-file)]
    (th/db-delete! :file-data {:file-id file-id :id snapshot-id :type "snapshot"})
    (t/is (nil? (db/tx-run! th/*system* fsnap/compact! file-id snapshot-id)))
    (run-compaction ctx)
    (t/is (nil? (snapshot-row ctx)))))

(defn- get-file
  [file-id]
  (db/run! th/*system* #(bfc/get-file % file-id)))

(t/deftest snapshot-with-objects-map-can-be-taken-outside-a-transaction
  (let [{:keys [profile-id file-id] :as ctx} (create-edited-file)
        file     (assoc (get-file file-id) :data (snapshot-data ctx))
        snapshot (fsnap/create! th/*system* file {:profile-id profile-id})
        ctx      (assoc ctx :snapshot-id (:id snapshot))]
    (t/is (fdata/has-objects-map? (snapshot-data ctx)))
    (t/is (some #(= (:id snapshot) (-> % :props db/decode-transit-pgobject :id))
                (compaction-tasks)))
    (run-compaction ctx)
    (t/is (not (fdata/has-objects-map? (snapshot-data ctx))))))

(t/deftest compaction-leaves-pages-behind-a-pointer-unloaded
  (let [{:keys [profile-id file-id page-id] :as ctx} (create-edited-file)
        pointer-page-id (uuid/next)
        pointer-id      (uuid/next)
        data     (-> (snapshot-data ctx)
                     (assoc-in [:pages-index pointer-page-id] (pmap/create pointer-id {})))
        file     (assoc (get-file file-id) :data data)
        snapshot (fsnap/create! th/*system* file {:profile-id profile-id})
        ctx      (assoc ctx :snapshot-id (:id snapshot))]
    (run-compaction ctx)
    (let [data (snapshot-data ctx)
          page (get-in data [:pages-index pointer-page-id])]
      (t/is (not (fdata/has-objects-map? data)))
      (t/is (map? (get-in data [:pages-index page-id :objects])))
      (t/is (pmap/pointer-map? page))
      (t/is (= pointer-id (pmap/get-id page))))))

(t/deftest compaction-with-storage-backend
  (binding [cf/config (assoc cf/config :file-data-backend "storage")]
    (let [ctx     (create-edited-file)
          before  (snapshot-row ctx)
          old-ref (-> before :metadata fdata/decode-metadata :storage-ref-id)
          storage (sto/resolve th/*system*)
          data    (fn [row]
                    (->> (-> row :metadata fdata/decode-metadata :storage-ref-id)
                         (sto/get-object storage)
                         (sto/get-object-bytes storage)
                         (blob/decode)))]
      (t/is (= "storage" (:backend before)))
      (t/is (fdata/has-objects-map? (data before)))

      (run-compaction ctx)

      (let [after   (snapshot-row ctx)
            new-ref (-> after :metadata fdata/decode-metadata :storage-ref-id)]
        (t/is (= "storage" (:backend after)))
        (t/is (not= old-ref new-ref))
        (t/is (= (fdata/process-objects (data before) (partial into {}))
                 (data after)))
        (t/testing "the replaced object is left for the storage gc"
          (t/is (some? (:touched-at (th/db-get :storage-object {:id old-ref})))))))))

(t/deftest snapshot-before-compaction-can-be-previewed-and-restored
  (let [{:keys [profile-id file-id page-id rect-id snapshot-id]} (create-edited-file)]

    (t/testing "preview"
      (let [out (th/command! {::th/type :get-file-snapshot
                              ::rpc/profile-id profile-id
                              :file-id file-id
                              :id snapshot-id})]
        (t/is (nil? (:error out)))
        (t/is (contains? (-> out :result :data :pages-index (get page-id) :objects) uuid/zero))
        (t/is (not (contains? (-> out :result :data :pages-index (get page-id) :objects) rect-id)))))

    (t/testing "restore"
      (let [out (th/command! {::th/type :restore-file-snapshot
                              ::rpc/profile-id profile-id
                              :file-id file-id
                              :id snapshot-id})
            objects (-> (th/command! {::th/type :get-file
                                      ::rpc/profile-id profile-id
                                      :id file-id})
                        :result :data :pages-index (get page-id) :objects)]
        (t/is (nil? (:error out)))
        (t/is (contains? objects uuid/zero))
        (t/is (not (contains? objects rect-id)))))))

(t/deftest pointer-map-file-snapshot-resolves-pointers-and-is-compacted-later
  (let [{:keys [page-id rect-id] :as ctx} (create-edited-file :pointer-map? true)
        data (snapshot-data ctx)]
    (t/is (uuid? (:snapshot-id ctx)))
    (t/is (empty? (fdata/get-used-pointer-ids data)))
    (t/is (fdata/has-objects-map? data))
    (t/is (not (contains? (get-in data [:pages-index page-id :objects]) rect-id)))
    (t/is (= 1 (count (compaction-tasks))))
    (th/run-pending-tasks!)
    (t/is (not (fdata/has-objects-map? (snapshot-data ctx))))))

(t/deftest snapshot-without-objects-map-needs-no-compaction
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false
                                    :features (-> (cfeat/get-enabled-features cf/flags)
                                                  (disj "fdata/objects-map" "fdata/pointer-map"))})]
    ;; The save enables fdata/objects-map, but the data it snapshots has none
    (update-file! :file-id (:id file)
                  :profile-id (:id profile)
                  :changes [(th/add-rect-change (-> file :data :pages first) (uuid/next))])
    (t/is (= 1 (count (th/db-query :file-change {:file-id (:id file) :label "internal/snapshot/0"}
                                   {::db/remove-deleted false}))))
    (t/is (empty? (compaction-tasks)))))
