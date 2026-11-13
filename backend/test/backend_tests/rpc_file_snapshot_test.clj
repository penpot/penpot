;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-snapshot-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.files.defaults :as cfd]
   [app.common.files.migrations :as fmg]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.objects-map :as omap]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.http :as http]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.set :as set]
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
            calls  (atom [])
            out    (with-redefs [mbus/pub! (fn [_cfg & {:keys [message]}]
                                             (swap! calls conj message))]
                     (th/command! params))
            sent   (first (filter #(= :file-restored (:type %)) @calls))]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))
        ;; Peers learn who restored which version, named by the label the
        ;; snapshot has now (the previous block renamed it)
        (t/is (= (:id profile) (:profile-id sent)))
        (t/is (= "label1-updated" (:label sent)))
        (t/is (ct/inst? (:created-at sent))))

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
        (th/run-pending-jobs)

        (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
          (let [res (th/run-task! :objects-gc {})]
            ;; delete 2 snapshots and 2 file data entries
            (t/is (= 4 (:processed res)))))))))

(t/deftest snapshot-preview-resolves-fragments
  ;; Regression test (R1): previewing an automatic snapshot of a
  ;; pointer-mapped file must return plain data, not pointer
  ;; placeholders the preview UI cannot render.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-id (first (get-in file [:data :pages]))
        shape-id (uuid/random)]

    ;; An update at revn 0 takes an automatic (system) snapshot of the
    ;; pre-change state; with pointer-map that snapshot shares fragments
    ;; with the live file.
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-obj
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
                                    :type :rect})}])

    ;; Single labeled snapshot here, but order explicitly so a
    ;; future edit adding more snapshots cannot pick the wrong one.
    (let [snap-id (->> (th/db-query :file-change {:file-id file-id} {:order-by [:created-at]})
                       (filter #(some? (:label %)))
                       (first)
                       (:id))
          _       (t/is (uuid? snap-id))
          out     (th/command! {::th/type :get-file-snapshot
                                ::rpc/profile-id (:id profile)
                                :file-id file-id
                                :id snap-id
                                :features cfeat/supported-features})]
      (t/is (nil? (:error out)))
      (let [data (get-in (:result out) [:data])]
        (t/is (map? data))
        (t/is (empty? (fdata/get-used-pointer-ids data)))))))

(defn- live-fragment-ids
  [file-id]
  (into #{}
        (comp (filter #(nil? (:deleted-at %)))
              (map :id))
        (th/db-query :file-data {:file-id file-id :type "fragment"})))

(t/deftest user-snapshot-shares-fragments
  ;; User-pinned versions share fragment rows like automatic snapshots
  ;; instead of storing a materialized copy.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-id (first (get-in file [:data :pages]))
        before  (live-fragment-ids file-id)
        out     (th/command! {::th/type :create-file-snapshot
                              ::rpc/profile-id (:id profile)
                              :file-id file-id
                              :label "v1"})
        _       (t/is (nil? (:error out)))
        snap-id (:id (:result out))]

    ;; No new fragment rows: the version reuses the live ones.
    (t/is (uuid? snap-id))
    (t/is (= before (live-fragment-ids file-id)))

    ;; The version pointers are a subset of the live pointers.
    (let [live (fdata/get-used-pointer-ids
                (:data (bfc/get-file th/*system* file-id)))
          snap (fdata/get-used-pointer-ids
                (:data (fsnap/get-snapshot th/*system* file-id snap-id)))]
      (t/is (pos? (count snap)))
      (t/is (every? #(contains? live %) snap)))

    (let [shape-id (uuid/random)]

      ;; Modify the file, then restore the version and compare resolved
      ;; content with the version preview (both materialized).
      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 0
                    :changes [{:type :add-obj
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
    (let [preview-out (th/command! {::th/type :get-file-snapshot
                                    ::rpc/profile-id (:id profile)
                                    :file-id file-id
                                    :id snap-id
                                    :features cfeat/supported-features})
          _           (t/is (nil? (:error preview-out)))
          preview     (:result preview-out)
          restore-out (th/command! {::th/type :restore-file-snapshot
                                    ::rpc/profile-id (:id profile)
                                    :file-id file-id
                                    :id snap-id})
          _           (t/is (nil? (:error restore-out)))
          live        (:data (bfc/get-file th/*system* file-id :realize? true))
          ;; The preview resolves pointers but keeps objects-maps
          ;; encoded; realize them too so both sides are plain data.
          preview-data (:data (fdata/realize-objects
                               nil {:data (:data preview)}))]
      (t/is (= preview-data live)))))

(t/deftest user-snapshot-of-file-needing-migration-is-materialized
  ;; A file with pending migrations cannot share fragments: the
  ;; migration path realizes the data, so the stored snapshot holds
  ;; plain maps. This pins the shape so it cannot drift silently.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)]
    (th/db-update! :file {:version (dec (:version file))} {:id file-id})
    (let [out     (th/command! {::th/type :create-file-snapshot
                                ::rpc/profile-id (:id profile)
                                :file-id file-id
                                :label "v1"})
          _       (t/is (nil? (:error out)))
          snap-id (:id (:result out))
          snap    (fsnap/get-snapshot th/*system* file-id snap-id)]
      (t/is (uuid? snap-id))
      (t/is (empty? (fdata/get-used-pointer-ids (:data snap))))
      ;; ...while preview keeps working on the migrated content.
      (let [preview (th/command! {::th/type :get-file-snapshot
                                  ::rpc/profile-id (:id profile)
                                  :file-id file-id
                                  :id snap-id
                                  :features cfeat/supported-features})]
        (t/is (nil? (:error preview)))
        (t/is (map? (get-in preview [:result :data])))))))

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

(t/deftest snapshot-creation-serializes-with-file-gc
  ;; A user snapshot shares fragment rows with the live file, so its
  ;; creation must serialize with a concurrent :file-gc pass (which
  ;; takes FOR UPDATE + SKIP LOCKED on the file row): creation holds
  ;; FOR SHARE, the GC skips locked files instead of racing it.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)]

    (t/testing "gc skips a file locked for share"
      (db/tx-run! th/*system*
                  (fn [cfg]
                    ;; Hold FOR SHARE to commit, like create-file-snapshot does.
                    (bfc/get-file cfg file-id :lock-for-share? true)
                    ;; The GC uses SKIP LOCKED on a different connection, so it
                    ;; must skip instead of blocking or marking.
                    (t/is (not (th/run-task! :file-gc {:file-id file-id}))))))

    (t/testing "creation cannot slip between gc scan and mark"
      ;; Deterministic interleaving: pause the GC right after its
      ;; snapshot scan, then create a snapshot. With the FOR SHARE lock
      ;; the creation waits at the read and sees post-GC fragment ids;
      ;; without it, it reads pre-GC ids and pins a fragment the pass
      ;; is about to mark.
      (let [orig-reduce      @#'fsnap/reduce-snapshots
            orig-create      @#'fsnap/create!
            scan-started     (promise)
            release-scan     (promise)
            reached-insert   (promise)
            do-create        (fn []
                               (th/command! {::th/type :create-file-snapshot
                                             ::rpc/profile-id (:id profile)
                                             :file-id file-id
                                             :label "pinned"}))]
        (with-redefs [fsnap/reduce-snapshots
                      (fn [cfg file-id xform f init]
                        (deliver scan-started true)
                        @release-scan
                        (orig-reduce cfg file-id xform f init))
                      fsnap/create!
                      (fn [& args]
                        (deliver reached-insert true)
                        (apply orig-create args))]
          (let [gc-done (future (th/run-task! :file-gc {:file-id file-id}))]
            (t/is (= true (deref scan-started 10000 ::timeout)))
            (let [created (future (do-create))]
              ;; The creation must not reach the insert while the GC
              ;; pass holds the file row: with FOR SHARE it waits at the
              ;; read instead of snapshotting pre-GC fragment ids.
              (t/is (= ::timeout (deref reached-insert 10000 ::timeout)))
              (deliver release-scan true)
              (t/is (some? (deref gc-done 30000 ::timeout)))
              (let [out (deref created 30000 ::timeout)]
                (t/is (not= ::timeout out))
                (t/is (nil? (:error out)))
                (t/is (uuid? (get-in out [:result :id])))))))))

    (t/testing "snapshot taken around gc still resolves"
      ;; The racing pass marks nothing (the file is unchanged, so
      ;; reattach keeps every id), therefore there is nothing to purge;
      ;; the snapshot keeps resolving afterwards.
      (t/is (zero? (:processed (th/run-task! :objects-gc {}))))
      (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
      ;; The pinned snapshot (not an arbitrary autosnapshot: those may
      ;; reference fragments an earlier pass already marked) must keep
      ;; resolving after another GC cycle.
      (let [snap-id (->> (th/db-query :file-change {:file-id file-id} {:order-by [:created-at]})
                         (filter #(= "pinned" (:label %)))
                         (first)
                         (:id))
            out     (th/command! {::th/type :get-file-snapshot
                                  ::rpc/profile-id (:id profile)
                                  :file-id file-id
                                  :id snap-id
                                  :features cfeat/supported-features})]
        (t/is (uuid? snap-id))
        (t/is (nil? (:error out)))
        (t/is (empty? (fdata/get-used-pointer-ids
                       (get-in out [:result :data]))))))))

(t/deftest restore-backup-serializes-with-file-gc
  ;; The backup snapshot taken by restore shares fragment rows with
  ;; the live file, so its creation must serialize with a concurrent
  ;; :file-gc pass, like ::create-file-snapshot does: with FOR SHARE
  ;; held by the GC pass, the restore waits at the locked read instead
  ;; of snapshotting pre-GC fragment ids that the pass is about to
  ;; mark as deleted.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-id (first (get-in file [:data :pages]))
        shape-id (uuid/random)
        snap-id (:id (:result (th/command! {::th/type :create-file-snapshot
                                            ::rpc/profile-id (:id profile)
                                            :file-id file-id
                                            :label "v1"})))]
    (t/is (uuid? snap-id))
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-obj
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
                                    :type :rect})}])
    (let [orig-reduce  @#'fsnap/reduce-snapshots
          orig-create  @#'fsnap/create!
          scan-started (promise)
          release-scan (promise)
          reached-insert (promise)
          do-restore   (fn []
                         (th/command! {::th/type :restore-file-snapshot
                                       ::rpc/profile-id (:id profile)
                                       :file-id file-id
                                       :id snap-id}))]
      (with-redefs [fsnap/reduce-snapshots
                    (fn [cfg file-id xform f init]
                      (deliver scan-started true)
                      @release-scan
                      (orig-reduce cfg file-id xform f init))
                    fsnap/create!
                    (fn [& args]
                      (deliver reached-insert true)
                      (apply orig-create args))]
        (let [gc-done (future (th/run-task! :file-gc {:file-id file-id}))]
          (t/is (= true (deref scan-started 10000 ::timeout)))
          (let [restored (future (do-restore))]
            (t/is (= ::timeout (deref reached-insert 10000 ::timeout)))
            (deliver release-scan true)
            (t/is (some? (deref gc-done 30000 ::timeout)))
            (let [out (deref restored 30000 ::timeout)]
              (t/is (not= ::timeout out))
              (t/is (nil? (:error out))))))))
    (let [backup-ids (->> (th/db-query :file-change {:file-id file-id})
                          (map (juxt :id :label))
                          (filter (fn [[_ label]]
                                    (and (string? label)
                                         (str/starts-with? label "snapshot-"))))
                          (map first)
                          (set))
          backup-ids (set/intersection
                      backup-ids
                      (into #{}
                            (map :id)
                            (th/db-query :file-data {:file-id file-id
                                                     :type "snapshot"})))
          live       (into #{}
                           (map :id)
                           (filter #(nil? (:deleted-at %))
                                   (th/db-query :file-data {:file-id file-id
                                                            :type "fragment"})))]
      (t/is (= 1 (count backup-ids)))
      (doseq [backup-id backup-ids
              :let [snap (fsnap/get-snapshot th/*system* file-id backup-id)]]
        (t/is (every? live (fdata/get-used-pointer-ids (:data snap))))
        (let [out (th/command! {::th/type :get-file-snapshot
                                ::rpc/profile-id (:id profile)
                                :file-id file-id
                                :id backup-id
                                :features cfeat/supported-features})]
          (t/is (nil? (:error out)))
          (t/is (empty? (fdata/get-used-pointer-ids
                         (get-in out [:result :data])))))))))

(t/deftest snapshot-preview-applies-pending-migrations
  ;; A snapshot taken while a migration was pending stores the old
  ;; format. The preview must apply the pending migration in memory
  ;; (the same pure step a normal read runs) instead of handing the old
  ;; format to the UI, and must not persist anything: the stored
  ;; version keeps its data so restoring it re-migrates on next read.
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})
        file-id  (:id file)
        page-id  (first (get-in file [:data :pages]))
        shape-id (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-obj
                             :page-id page-id
                             :id shape-id
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (-> (cts/setup-shape {:id shape-id
                                                        :name "rect"
                                                        :frame-id uuid/zero
                                                        :parent-id uuid/zero
                                                        :type :rect})
                                      (assoc :applied-tokens {:stroke-width "token"}))}])

    ;; Force the 0031 migration on the next save.
    (th/db-delete! :file-migration {:file-id file-id
                                    :name "0031-migrate-stroke-width-token-attr"})

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 1
                  :changes [{:type :add-page :name "b" :id (uuid/random)}])

    (let [snapshot (->> (th/db-query :file-change {:file-id file-id})
                        (filter #(and (some? (:label %))
                                      (= 1 (:revn %))))
                        (first))
          snapshot-id (:id snapshot)
          stored      (fdata/realize th/*system*
                                     (assoc (fsnap/get-snapshot th/*system* file-id snapshot-id)
                                            :id file-id))
          out         (th/command! {::th/type :get-file-snapshot
                                    ::rpc/profile-id (:id profile)
                                    :file-id file-id
                                    :id snapshot-id
                                    :features cfeat/supported-features})]

      (t/is (uuid? snapshot-id))

      ;; The stored version keeps the old format and the pending
      ;; migration: the preview must not touch the stored row.
      (t/is (not (contains? (set (:migrations stored))
                            "0031-migrate-stroke-width-token-attr")))
      (t/is (= {:stroke-width "token"}
               (get-in stored [:data :pages-index page-id
                               :objects shape-id :applied-tokens])))

      ;; The preview migrates it in memory.
      (t/is (nil? (:error out)))
      (t/is (= file-id (get-in out [:result :data :id])))
      (let [tokens (get-in out [:result :data :pages-index page-id
                                :objects shape-id :applied-tokens])]
        (t/is (not (contains? tokens :stroke-width)))
        (t/is (contains? tokens :stroke-width-top))
        (t/is (contains? tokens :stroke-width-bottom)))

      ;; The preview declared the migrated set in the payload.
      (t/is (contains? (set (get-in out [:result :migrations]))
                       "0031-migrate-stroke-width-token-attr")))

    ;; Nothing was persisted by the preview.
    (let [snapshot-id (:id (->> (th/db-query :file-change {:file-id file-id})
                                (filter #(and (some? (:label %))
                                              (= 1 (:revn %))))
                                (first)))
          stored      (fdata/realize th/*system*
                                     (assoc (fsnap/get-snapshot th/*system* file-id snapshot-id)
                                            :id file-id))]
      (t/is (= {:stroke-width "token"}
               (get-in stored [:data :pages-index page-id
                               :objects shape-id :applied-tokens]))))))

(t/deftest snapshot-preview-realizes-objects-for-clients-without-the-feature
  ;; `:features` works like in `get-file`: a client that does not
  ;; declare `fdata/objects-map` receives plain objects instead of the
  ;; storage structure of the fragment.
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})
        file-id  (:id file)
        page-id  (first (get-in file [:data :pages]))
        shape-id (uuid/random)
        _        (update-file! :profile-id (:id profile)
                               :file-id file-id
                               :revn 0
                               :changes [{:type :add-obj
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
                                                 :type :rect})}])

        snapshot-id (:id (:result (th/command! {::th/type :create-file-snapshot
                                                ::rpc/profile-id (:id profile)
                                                :file-id file-id
                                                :label "v1"})))
        preview     (fn [features]
                      (th/command! {::th/type :get-file-snapshot
                                    ::rpc/profile-id (:id profile)
                                    :file-id file-id
                                    :id snapshot-id
                                    :features features}))
        with-feature    (preview cfeat/supported-features)
        without-feature (preview (disj cfeat/supported-features
                                       "fdata/objects-map"))]

    (t/is (nil? (:error with-feature)))
    (t/is (nil? (:error without-feature)))
    (t/is (omap/objects-map?
           (get-in with-feature [:result :data :pages-index page-id :objects])))
    (t/is (not (omap/objects-map?
                (get-in without-feature [:result :data :pages-index page-id :objects]))))))

(t/deftest snapshot-preview-migrates-legacy-snapshots-without-migrations
  ;; Snapshots written before the `migrations` column existed (SQL
  ;; migration 0140) store NULL and the format version of their time.
  ;; The decoder must hand an ordered set (a vector would make
  ;; `need-migration?` compare indexes), and the preview must
  ;; regenerate the applied set from the stored version, migrate the
  ;; data in memory and leave the row untouched.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        snap-id (:id (:result (th/command! {::th/type :create-file-snapshot
                                            ::rpc/profile-id (:id profile)
                                            :file-id file-id
                                            :label "legacy"})))]

    (t/is (d/ordered-set? (:migrations (fsnap/get-snapshot th/*system* file-id snap-id))))

    ;; Turn the version into a pre-0140 snapshot: no applied set and an
    ;; old format version.
    (th/db-exec! ["UPDATE file_change SET migrations = NULL, version = 60 WHERE id = ?"
                  snap-id])

    (let [stored (fsnap/get-snapshot th/*system* file-id snap-id)
          out    (th/command! {::th/type :get-file-snapshot
                               ::rpc/profile-id (:id profile)
                               :file-id file-id
                               :id snap-id
                               :features cfeat/supported-features})
          result (:result out)]

      (t/is (nil? (:migrations stored)))
      (t/is (= 60 (:version stored)))

      (t/is (nil? (:error out)))
      (t/is (d/ordered-set? (:migrations result)))
      ;; The preview regenerates everything the stored version lacks
      ;; instead of trusting the absent applied set.
      (t/is (= cfd/version (:version result)))
      (t/is (= (into #{} fmg/available-migrations)
               (set (:migrations result))))
      (t/is (set/subset? (into #{} (fmg/generate-migrations-from-version cfd/version))
                         (set (:migrations result))))

      ;; Nothing is persisted.
      (let [stored (fsnap/get-snapshot th/*system* file-id snap-id)]
        (t/is (nil? (:migrations stored)))
        (t/is (= 60 (:version stored)))))))
(t/deftest snapshots-require-file-id
  ;; Since version 2.20 the snapshot commands require the `file-id`
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

    (doseq [type [:lock-file-snapshot :unlock-file-snapshot
                  :update-file-snapshot :delete-file-snapshot]]
      (t/testing (name type)
        ;; make the row look old, so the mutation has to bump it
        (th/db-update! :file-change {:updated-at stale-at}
                       {:id (:id snapshot)}
                       {::db/remove-deleted false})

        (let [params (cond-> {::th/type type
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)
                              :id (:id snapshot)}
                       (= :update-file-snapshot type)
                       (assoc :label "renamed"))
              out    (th/command! params)
              row    (th/db-get :file-change {:id (:id snapshot)}
                                {::db/remove-deleted false})]

          (t/is (nil? (:error out)))
          (t/is (true? (:result out)))
          (t/is (pos? (- (inst-ms (:updated-at row)) (inst-ms stale-at)))
                "updated_at must be bumped by the mutation")
          (t/is (<= (inst-ms (:updated-at row)) (inst-ms (ct/now)))
                "updated_at must not be in the future")
          (t/is (<= (- (inst-ms (ct/now)) (inst-ms (:updated-at row))) 60000)
                "updated_at must be the moment of the mutation")
          (when (= type :delete-file-snapshot)
            (t/is (< (inst-ms (:updated-at row)) (inst-ms (:deleted-at row)))
                  "the soft delete must not stamp updated_at with the future deleted_at")))))

    (t/testing "the deleted snapshot is marked as deleted"
      (t/is (some? (:deleted-at (th/db-get :file-change {:id (:id snapshot)}
                                           {::db/remove-deleted false})))))

    ;; process the delete-object job enqueued by the snapshot delete
    (th/run-pending-jobs)))

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
