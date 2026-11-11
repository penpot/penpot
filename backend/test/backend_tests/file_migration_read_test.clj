;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-migration-read-test
  "Persisting the migrations applied on read is an optimization of the read:
  the read already returns migrated data, so the write must never overwrite a
  row written after that read, and it must keep a single decoded copy of the
  file in the heap."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as fmg]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-migrations :as fmigr]
   [app.rpc :as-alias rpc]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private migration-name
  "0031-migrate-stroke-width-token-attr")

(defn- create-file!
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]
    {:profile-id (:id profile)
     :file-id    (:id file)
     :page-id    (first (:pages (:data (bfc/get-file th/*system* (:id file)))))}))

(defn- force-migration!
  "Make the next read see a pending migration, the way the snapshot tests do:
  the version matches, so only a missing row triggers the migration."
  [file-id]
  (th/db-delete! :file-migration
                 {:file-id file-id
                  :name migration-name}))

(defn- file-data-row
  [file-id]
  (th/db-get :file-data {:file-id file-id :id file-id}))

(defn- storage-object
  [id]
  (th/db-get :storage-object {:id id} {::db/remove-deleted false}))

(defn- save!
  "Run a real save request so the test exercises the same write path a client
  takes, not a hand-built row."
  [profile-id file-id revn changes]
  (th/command! {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features (cfeat/get-enabled-features cf/flags)
                :changes changes}))

(t/deftest migrate-on-read-does-not-overwrite-a-concurrent-save
  ;; A save commits after the read that saw the pending migration. The read
  ;; must decide again on the map it takes under the `file` row lock, so it
  ;; writes nothing: the save survives, `revn` never goes back (a client whose
  ;; revn is higher than the stored one cannot save again) and the object the
  ;; save left stays referenced instead of becoming a frozen orphan.
  (with-redefs [cf/get (th/config-get-mock {:file-data-backend "storage"})]
    (let [{:keys [profile-id file-id page-id]} (create-file!)
          shape-id   (uuid/random)
          fired?     (atom false)
          replaced   (atom nil)
          saved-at   (atom nil)
          saved-revn (atom nil)]

      (force-migration! file-id)

      (with-redefs [fmigr/resolve-applied-migrations
                    (let [orig fmigr/resolve-applied-migrations]
                      (fn [cfg file]
                        (let [result (orig cfg file)]
                          (when (compare-and-set! fired? false true)
                            ;; The save commits once the read has already
                            ;; seen the pending migration. It applies the
                            ;; migration on its own and records it.
                            (let [out (save! profile-id file-id 0
                                             [(th/add-rect-change page-id shape-id)])]
                              (t/is (nil? (:error out))))

                            (let [row (file-data-row file-id)]
                              (reset! replaced (:storage-object-id row))
                              (reset! saved-revn (:revn (th/db-get :file {:id file-id})))
                              (reset! saved-at (:modified-at (th/db-get :file {:id file-id}))))

                            ;; Freeze the object the save left, as the storage
                            ;; GC would if it saw it before a stale write
                            ;; replaced the row again: a write that does not
                            ;; touch it leaks the blob forever.
                            (th/db-update! :storage-object
                                           {:touched-at nil}
                                           {:id @replaced}))
                          result)))]

        (let [returned  (db/tx-run! th/*system*
                                    (fn [cfg] (bfc/get-file cfg file-id)))
              row       (th/db-get :file {:id file-id})
              persisted (db/tx-run! th/*system*
                                    (fn [cfg] (bfc/get-file cfg file-id
                                                            :migrate? false
                                                            :realize? true)))]

          (t/testing "the save survives the read"
            (t/is (= @saved-revn (:revn returned)))
            (t/is (= @saved-revn (:revn row)))
            (t/is (= @saved-at (:modified-at row)))
            (t/is (some? (get-in persisted [:data :pages-index page-id :objects shape-id]))))

          (t/testing "the read writes nothing and the file ends migrated"
            (t/is (not (fmg/need-migration? returned)))
            (t/is (= @replaced (:storage-object-id (file-data-row file-id))))
            (t/is (seq (th/db-query :file-migration
                                    {:file-id file-id
                                     :name migration-name}))))

          (t/testing "the next save of the same client does not conflict"
            (let [out (save! profile-id file-id @saved-revn
                             [(th/add-rect-change page-id (uuid/random))])]
              (t/is (nil? (:error out))))))))))

(t/deftest migration-already-applied-by-another-reader-is-not-persisted
  ;; Two readers see the migration pending; the first to get the lock migrates
  ;; and persists. The second must re-check on its locked read and write
  ;; nothing (a second write would create another object for the same data).
  (with-redefs [cf/get (th/config-get-mock {:file-data-backend "storage"})]
    (let [{:keys [file-id]} (create-file!)
          fired?  (atom false)
          pointer (atom nil)]

      (force-migration! file-id)

      (with-redefs [fmg/need-migration?
                    (let [orig fmg/need-migration?]
                      (fn [file]
                        (let [result (orig file)]
                          (when (and result (compare-and-set! fired? false true))
                            ;; The other reader wins the lock, migrates and
                            ;; persists before this one gets it.
                            (db/tx-run! th/*system*
                                        (fn [cfg] (bfc/get-file cfg file-id)))
                            (reset! pointer (:storage-object-id (file-data-row file-id))))
                          result)))]

        (db/tx-run! th/*system*
                    (fn [cfg] (bfc/get-file cfg file-id)))

        (t/testing "the reader that lost the race does not write"
          (t/is (uuid? @pointer))
          (t/is (= @pointer (:storage-object-id (file-data-row file-id)))))

        (t/testing "the file ends migrated"
          (t/is (seq (th/db-query :file-migration
                                  {:file-id file-id
                                   :name migration-name}))))))))

(t/deftest migration-read-loads-the-file-data-once
  ;; The probe decides with a row read without data, so the migration path
  ;; keeps a single decoded copy of the file in the heap (a large file can be
  ;; hundreds of MB and a second copy can exhaust it).
  (let [{:keys [file-id]} (create-file!)
        reads (atom 0)]

    (force-migration! file-id)

    (with-redefs [fdata/resolve-file-data
                  (let [orig fdata/resolve-file-data]
                    (fn [cfg file]
                      (when (= file-id (:id file))
                        (swap! reads inc))
                      (orig cfg file)))]

      (db/tx-run! th/*system*
                  (fn [cfg] (bfc/get-file cfg file-id)))

      (t/is (= 1 @reads)))))

(t/deftest realize-runs-once-per-read-path
  ;; `fdata/realize` is a full walk of the decoded data. The read owns it once
  ;; per path; the migration path pays it twice on purpose, once inside
  ;; `migrate-file` (the chain needs plain data) and once on its result.
  (let [{:keys [file-id]} (create-file!)
        calls (atom 0)
        read! (fn [opts]
                (reset! calls 0)
                (with-redefs [fdata/realize
                              (let [orig fdata/realize]
                                (fn [cfg file]
                                  (swap! calls inc)
                                  (orig cfg file)))]
                  (db/tx-run! th/*system*
                              (fn [cfg] (bfc/get-file cfg file-id opts)))))]
    (t/testing "a plain read realizes once"
      (read! {:realize? true})
      (t/is (= 1 @calls)))

    (t/testing "a read-only read realizes once"
      (read! {:realize? true :read-only? true})
      (t/is (= 1 @calls)))

    (t/testing "the migration path realizes twice"
      (force-migration! file-id)
      (read! {:realize? true})
      (t/is (= 2 @calls)))))

(t/deftest row-only-read-resolves-the-applied-migrations-on-demand
  ;; A row-only read resolves the applied migrations by default, because the
  ;; row can be completed later and the caller may need them; the explicit
  ;; opt-out leaves them out.
  (let [{:keys [file-id]} (create-file!)
        with-migrations    (bfc/get-file th/*system* file-id :load-data? false)
        without            (bfc/get-file th/*system* file-id
                                         :load-data? false
                                         :load-migrations? false)]
    (t/is (seq (:migrations with-migrations)))
    (t/is (nil? (:migrations without)))))

(t/deftest migrating-read-returns-the-pointer-it-persisted
  ;; The map a migrating read returns must carry the pointer of the row it
  ;; left: a caller that writes with it touches that object instead of the
  ;; replaced one. The storage GC freezes a referenced object, so a write
  ;; that misses it leaks the blob forever.
  (with-redefs [cf/get (th/config-get-mock {:file-data-backend "storage"})]
    (let [{:keys [file-id]} (create-file!)]
      (force-migration! file-id)
      (let [file (db/tx-run! th/*system*
                             (fn [cfg] (bfc/get-file cfg file-id)))
            left (:storage-object-id (file-data-row file-id))]

        (t/testing "the read reports the pointer of the persisted row"
          (t/is (uuid? left))
          (t/is (= left (:storage-object-id file))))

        ;; Freeze the object while the row still references it, as the storage
        ;; GC does; the later write must touch it (see the concurrent-save
        ;; test for the same technique).
        (th/db-update! :storage-object {:touched-at nil} {:id left})

        (db/tx-run! th/*system*
                    (fn [cfg] (bfc/update-file! cfg file)))

        (let [row (file-data-row file-id)]
          (t/testing "the write replaces the row and touches the object it left"
            (t/is (not= left (:storage-object-id row)))
            (t/is (some? (:touched-at (storage-object left))))))))))

(t/deftest read-completes-with-a-single-row-read
  ;; The common read is one row read (with its data columns) and
  ;; `attach-file-data` completes it.
  (let [{:keys [file-id page-id]} (create-file!)
        [file sqls] (th/read-recording-sql
                     #(db/tx-run! th/*system*
                                  (fn [cfg] (bfc/get-file cfg file-id))))]
    (t/is (= [bfc/sql:get-file] sqls))
    (t/is (some? (get-in file [:data :pages-index page-id])))))

(t/deftest read-resolves-the-applied-migrations-once
  ;; The common read resolves the applied migrations once. The migration path
  ;; resolves them again on the locked re-read (it must decide on the fresh
  ;; row) and once more after persisting (to return the applied set); no path
  ;; resolves them twice without a reason.
  (let [{:keys [file-id]} (create-file!)
        calls (atom 0)
        read! (fn []
                (reset! calls 0)
                (with-redefs [fmigr/resolve-applied-migrations
                              (let [orig fmigr/resolve-applied-migrations]
                                (fn [cfg file]
                                  (swap! calls inc)
                                  (orig cfg file)))]
                  (db/tx-run! th/*system*
                              (fn [cfg] (bfc/get-file cfg file-id)))))]

    (read!)
    (t/is (= 1 @calls))

    (force-migration! file-id)
    (read!)
    (t/is (= 3 @calls))))

(t/deftest migration-read-rereads-the-row-under-lock
  ;; The first read decides; only when a migration is pending the row is read
  ;; again, and that second read takes the `file` row lock.
  (let [{:keys [file-id]} (create-file!)]
    (force-migration! file-id)
    (let [[_ sqls] (th/read-recording-sql
                    #(db/tx-run! th/*system*
                                 (fn [cfg] (bfc/get-file cfg file-id))))]
      (t/is (= [bfc/sql:get-file
                (str bfc/sql:get-file " FOR UPDATE of f")]
               sqls)))))

(t/deftest read-only-migration-never-writes
  ;; A read-only context migrates in memory: no lock, no row write and no
  ;; storage write. The pending migration stays pending.
  (with-redefs [cf/get (th/config-get-mock {:file-data-backend "storage"})]
    (let [{:keys [file-id page-id]} (create-file!)
          pointer (:storage-object-id (file-data-row file-id))]

      (force-migration! file-id)

      (let [returned (db/tx-run! th/*system*
                                 (fn [cfg] (bfc/get-file cfg file-id :read-only? true)))]

        (t/testing "the read returns migrated, realized data"
          (t/is (not (pmap/pointer-map? (get-in returned [:data :pages-index page-id])))))

        (t/testing "nothing is persisted"
          (t/is (= pointer (:storage-object-id (file-data-row file-id))))
          (t/is (empty? (th/db-query :file-migration
                                     {:file-id file-id
                                      :name migration-name}))))))))
