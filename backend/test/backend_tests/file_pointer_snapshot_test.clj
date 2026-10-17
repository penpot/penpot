;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-pointer-snapshot-test
  "Snapshots share fragment rows with the live file (fdata/pointer-map),
  so file-gc must keep fragments referenced by any live snapshot."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

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
    (t/is (nil? (:error out)))
    (:result out)))

(defn- fragment-rows
  [file-id]
  (th/db-query :file-data {:file-id file-id :type "fragment"}))

(defn- live-fragment-ids
  [file-id]
  (into #{} (map :id) (filter #(nil? (:deleted-at %)) (fragment-rows file-id))))

(defn- add-rect-change
  [page-id shape-id]
  [{:type :add-obj
    :page-id page-id
    :id shape-id
    :parent-id uuid/zero
    :frame-id uuid/zero
    :components-v2 true
    :obj (cts/setup-shape {:id shape-id
                           :name "rect"
                           :frame-id uuid/zero
                           :parent-id uuid/zero
                           :type :rect})}])

(t/deftest snapshot-shares-fragments-without-duplicates
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        before  (live-fragment-ids file-id)]
    (t/is (pos? (count before)))

    (let [f0       (bfc/get-file th/*system* file-id)
          snapshot (fsnap/create! th/*system* f0 {:profile-id (:id profile)})
          after    (live-fragment-ids file-id)]
      ;; No new fragment rows: the snapshot reuses the live ones.
      (t/is (= before after))
      (t/is (some? (:id snapshot)))

      ;; Snapshot pointers are a subset of the live pointers.
      (let [main-ids (fdata/get-used-pointer-ids (:data f0))
            snap     (fsnap/get-snapshot th/*system* file-id (:id snapshot))
            snap-ids (fdata/get-used-pointer-ids (:data snap))]
        (t/is (pos? (count snap-ids)))
        (t/is (every? #(contains? main-ids %) snap-ids))))))

(t/deftest gc-keeps-fragments-referenced-by-live-snapshot
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-id (first (:pages (:data f0)))
        snap    (fsnap/create! th/*system* f0 {:profile-id (:id profile)})
        old-ids (fdata/get-used-pointer-ids (:data f0))]

    ;; Modify the page: its fragment id changes, the old row is now only
    ;; referenced by the snapshot.
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change page-id (uuid/random)))

    (let [f1      (bfc/get-file th/*system* file-id)
          new-ids (fdata/get-used-pointer-ids (:data f1))
          orphan  (first (remove #(contains? new-ids %) old-ids))]
      (t/is (some? orphan))

      (t/is (true? (th/run-task! :file-gc {:file-id file-id})))

      ;; The old fragment is still alive because the snapshot needs it.
      (let [rows (fragment-rows file-id)
            row  (first (filter #(= orphan (:id %)) rows))]
        (t/is (some? row))
        (t/is (nil? (:deleted-at row))))

      ;; The snapshot still restores the pre-change content.
      (let [snap-data (:data (fsnap/get-snapshot th/*system* file-id (:id snap)))
            snap-ids  (fdata/get-used-pointer-ids snap-data)]
        (t/is (contains? snap-ids orphan))))))

(t/deftest gc-marks-fragments-without-live-reference
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-id (first (:pages (:data f0)))
        old-ids (fdata/get-used-pointer-ids (:data f0))]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change page-id (uuid/random)))

    (let [f1      (bfc/get-file th/*system* file-id)
          new-ids (fdata/get-used-pointer-ids (:data f1))
          orphan  (first (remove #(contains? new-ids %) old-ids))]
      (t/is (some? orphan))
      ;; The update above may have left an automatic snapshot sharing the
      ;; replaced fragment; drop snapshot rows first so the fragment is
      ;; really unreferenced (same cleanup the rpc-file-test fragment
      ;; test performs before asserting GC marking).
      (th/db-delete! :file-change {:file-id file-id})
      (th/db-delete! :file-data {:file-id file-id :type "snapshot"})
      (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
      (let [row (first (filter #(= orphan (:id %)) (fragment-rows file-id)))]
        (t/is (some? row))
        (t/is (some? (:deleted-at row)))))))

(t/deftest pointer-context-contract
  ;; The invariant the whole backend relies on: a pointer can only be
  ;; derefed while a loader is bound; with-pointers binds the loader
  ;; for the given file.
  (let [pointer (pmap/create (uuid/next) {})]
    (t/is (thrown? UnsupportedOperationException (deref pointer)))
    (fdata/with-pointers th/*system* (uuid/random)
      (try
        (deref pointer)
        (t/is false "expected a fragment-not-found error")
        (catch clojure.lang.ExceptionInfo cause
          (t/is (= :fragment-not-found (:code (ex-data cause)))))))))

(t/deftest restore-returns-snapshot-content
  ;; Snapshot, modify the page so the old fragment is snapshot-only,
  ;; restore, and compare fully resolved content with the snapshot.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        snap    (fsnap/create! th/*system* f0 {:profile-id (:id profile)})]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change
                            (first (:pages (:data f0)))
                            (uuid/random)))

    (db/tx-run! th/*system*
                (fn [cfg] (fsnap/restore! cfg file-id (:id snap))))

    (let [snap-data (:data (fsnap/get-snapshot th/*system* file-id (:id snap)))
          snap-content (:data (fdata/realize th/*system*
                                             {:id file-id :data snap-data}))
          live-content (:data (bfc/get-file th/*system* file-id
                                            :realize? true))]
      (t/is (= snap-content live-content)))))

(t/deftest offload-keeps-fragment-semantics
  ;; Every file_data row (main, snapshot, fragments) moves to storage
  ;; without changing ids, and reads keep working.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        snap    (fsnap/create! th/*system* f0 {:profile-id (:id profile)})
        _       (update-file! :profile-id (:id profile)
                              :file-id file-id
                              :revn 0
                              :changes (add-rect-change
                                        (first (:pages (:data f0)))
                                        (uuid/random)))
        before  (into {} (map (juxt :id identity))
                      (th/db-query :file-data {:file-id file-id}))
        ;; Snapshot rows are born with a future deleted-at (they expire),
        ;; and offload only moves rows with deleted-at IS NULL.
        live-before (into {} (filter (comp nil? :deleted-at val)) before)
        main-before (fdata/get-used-pointer-ids
                     (:data (bfc/get-file th/*system* file-id)))]
    (t/is (pos? (count before)))
    (t/is (every? #(= "db" (:backend %)) (vals before)))

    (let [result (th/run-task! :offload-file-data {:file-id file-id})]
      (t/is (zero? (:skipped result)))
      (t/is (= (count live-before) (:offloaded result))))

    (let [after (into {} (map (juxt :id identity))
                      (th/db-query :file-data {:file-id file-id}))
          live-after (into {} (filter (comp nil? :deleted-at val)) after)]
      (t/is (= (set (keys live-before)) (set (keys live-after))))
      (t/is (every? #(= "storage" (:backend %)) (vals live-after)))
      ;; Same pointers after the move, and the shared snapshot loads.
      (t/is (= main-before
               (fdata/get-used-pointer-ids
                (:data (bfc/get-file th/*system* file-id)))))
      (t/is (some? (:data (fsnap/get-snapshot th/*system* file-id (:id snap))))))))
