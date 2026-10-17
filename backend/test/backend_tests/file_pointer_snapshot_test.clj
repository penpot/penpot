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
   [app.common.files.migrations :as fmg]
   [app.common.time :as ct]
   [app.common.types.objects-map :as omap]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
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

(defn- add-rect-changes
  [page-id n]
  (mapv #(first (add-rect-change page-id %)) (repeatedly n uuid/random)))

(defn- page-pointer-ids
  [file-id & {:as opts}]
  (let [f (apply bfc/get-file th/*system* file-id (mapcat identity opts))]
    (into {} (map (fn [[pid p]] [pid (pmap/get-id p)]))
          (:pages-index (:data f)))))

(defn- fragment-objects
  [file-id fragment-id]
  (some-> (th/db-get :file-data {:file-id file-id
                                 :id fragment-id
                                 :type "fragment"})
          :data blob/decode :objects))

(defn- snapshots-by-revn
  [file-id revn]
  (th/db-exec! ["select id, label, revn from file_change
                  where file_id = ? and label is not null and revn = ?"
                file-id revn]))

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

(t/deftest snapshot-release-frees-fragments
  ;; Full pin-release lifecycle through the real paths: the replaced
  ;; fragment stays alive while the snapshot exists, and the next
  ;; file-gc after deleting + purging the snapshot marks it.
  ;; Note: every update takes an automatic snapshot, so besides the
  ;; manual one there is also an automatic snapshot of the same state;
  ;; the release phase deletes all of them.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-id (first (:pages (:data f0)))]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change page-id (uuid/random)))

    (let [f1   (bfc/get-file th/*system* file-id)
          snap (fsnap/create! th/*system* f1 {:profile-id (:id profile)})]
      (t/is (uuid? (:id snap)))

      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 1
                    :changes (add-rect-change page-id (uuid/random)))

      (let [f2      (bfc/get-file th/*system* file-id)
            new-ids (fdata/get-used-pointer-ids (:data f2))
            old-ids (fdata/get-used-pointer-ids (:data f1))
            orphan  (first (remove #(contains? new-ids %) old-ids))]
        (t/is (some? orphan))

        (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
        (t/is (nil? (:deleted-at (first (filter #(= orphan (:id %))
                                                (fragment-rows file-id))))))

        ;; Every update takes an automatic snapshot and each of them may
        ;; share the orphan, so release all of them through the real
        ;; path: delete! submits a :delete-object job per snapshot that
        ;; marks both its file_change and its file_data rows as deleted.
        (doseq [{:keys [id]} (filter #(some? (:label %))
                                     (th/db-query :file-change {:file-id file-id}))]
          (fsnap/delete! th/*system* {:id id
                                      :file-id file-id
                                      :deleted-at (ct/minus (ct/now) {:hours 1})}))
        (th/run-pending-jobs)
        (t/is (pos? (:processed (th/run-task! :objects-gc {}))))

        (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
        (let [row (first (filter #(= orphan (:id %)) (fragment-rows file-id)))]
          (t/is (some? row))
          (t/is (some? (:deleted-at row))))))))

(t/deftest file-gc-is-idempotent-on-unchanged-file
  ;; A file-gc pass over an unchanged file must not create fragment
  ;; rows nor mark any: unchanged pages keep their ids.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        before  (live-fragment-ids file-id)]
    (t/is (pos? (count before)))
    (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
    (let [after-first (live-fragment-ids file-id)]
      (t/is (= before after-first))
      (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
      (t/is (= after-first (live-fragment-ids file-id)))
      (t/is (every? #(nil? (:deleted-at %)) (fragment-rows file-id))))))

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

(t/deftest migrating-save-keeps-snapshot-on-pre-save-content
  ;; A save that triggers a file migration must attach the automatic
  ;; snapshot before migrating. Otherwise the migration gives the page
  ;; pointer a fresh id and `modified?`, the save's change `assoc`s on
  ;; that same pointer (which then keeps its id) and the snapshot ends
  ;; up naming the fragment the save writes with the post-save content.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-a  (uuid/random)
        page-b  (uuid/random)
        shape-1 (uuid/random)
        shape-2 (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-page :name "a" :id page-a}
                            {:type :add-page :name "b" :id page-b}])

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 1
                  :changes (add-rect-change page-a shape-1))

    ;; Force a pending migration on the next save: drop one applied
    ;; migration row so `need-migration?` is true again.
    (th/db-delete! :file-migration {:file-id file-id
                                    :name "0031-migrate-stroke-width-token-attr"})

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 2
                  :changes (add-rect-change page-a shape-2))

    (let [snapshot  (first (snapshots-by-revn file-id 2))
          snap-data (:data (fsnap/get-snapshot th/*system* file-id (:id snapshot)))
          snap-id   (pmap/get-id (get-in snap-data [:pages-index page-a]))
          main-id   (pmap/get-id (get-in (:data (bfc/get-file th/*system* file-id))
                                         [:pages-index page-a]))
          snap-objs (:objects (blob/decode
                               (:data (th/db-get :file-data {:file-id file-id
                                                             :id snap-id
                                                             :type "fragment"}))))]

      (t/is (uuid? (:id snapshot)))
      ;; The snapshot must not share the fragment the save rewrites.
      (t/is (not= snap-id main-id))
      (t/is (contains? snap-objs shape-1))
      (t/is (not (contains? snap-objs shape-2)))

      ;; Restoring it gives the pre-save page on a fully migrated file.
      (let [out      (th/command! {::th/type :restore-file-snapshot
                                   ::rpc/profile-id (:id profile)
                                   :file-id file-id
                                   :id (:id snapshot)})
            restored (bfc/get-file th/*system* file-id :realize? true)
            objects  (get-in restored [:data :pages-index page-a :objects])]
        (t/is (nil? (:error out)))
        (t/is (contains? objects shape-1))
        (t/is (not (contains? objects shape-2)))
        (t/is (not (fmg/need-migration? restored)))))))

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

(t/deftest gc-reattaches-unchanged-pages-and-marks-orphan
  ;; No-churn boundary when content changes: modify exactly one page of
  ;; a two-page file and run file-gc. Unmodified pages must keep their
  ;; fragment ids (reattach), the replaced fragment must be marked, a
  ;; follow-up objects-gc must purge only that row, and the live file
  ;; must still resolve fully.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-1  (first (:pages (:data f0)))
        page-2  (uuid/next)
        shape-2 (uuid/random)]
    ;; Second page so "unchanged" has a witness.
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-page
                             :name "second"
                             :id page-2}
                            {:type :add-obj
                             :page-id page-2
                             :id shape-2
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (cts/setup-shape
                                   {:id shape-2
                                    :name "rect"
                                    :frame-id uuid/zero
                                    :parent-id uuid/zero
                                    :type :rect})}])
    (let [f1        (bfc/get-file th/*system* file-id)
          pages-1   (:pages-index (:data f1))
          id-1-before (pmap/get-id (get pages-1 page-1))
          id-2-before (pmap/get-id (get pages-1 page-2))]
      (t/is (some? id-1-before))
      (t/is (some? id-2-before))
      (t/is (not= id-1-before id-2-before))
      ;; Modify exactly page-1.
      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 1
                    :changes (add-rect-change page-1 (uuid/random)))
      (let [f2          (bfc/get-file th/*system* file-id)
            pages-2     (:pages-index (:data f2))
            id-1-after  (pmap/get-id (get pages-2 page-1))
            id-2-after  (pmap/get-id (get pages-2 page-2))
            orphan      id-1-before]
        (t/is (not= id-1-before id-1-after))
        (t/is (= id-2-before id-2-after))
        ;; Updates take automatic snapshots that would pin the orphan;
        ;; drop them so it is really unreferenced (same cleanup the
        ;; gc-marks test performs before asserting marking).
        (th/db-delete! :file-change {:file-id file-id})
        (th/db-delete! :file-data {:file-id file-id :type "snapshot"})
        (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
        ;; Unchanged page kept its id through the pass.
        (let [f3     (bfc/get-file th/*system* file-id)
              pages-3 (:pages-index (:data f3))]
          (t/is (= id-2-after (pmap/get-id (get pages-3 page-2)))))
        ;; Orphan marked, nothing else touched.
        (let [rows   (fragment-rows file-id)
              marked (filter #(some? (:deleted-at %)) rows)]
          (t/is (= 1 (count marked)))
          (t/is (= orphan (:id (first marked)))))
        ;; objects-gc purges exactly the orphan row.
        (t/is (pos? (:processed (th/run-task! :objects-gc {}))))
        (let [rows (fragment-rows file-id)]
          (t/is (nil? (some #(= orphan (:id %)) rows)))
          (t/is (= #{id-1-after id-2-after} (live-fragment-ids file-id))))
        ;; Live file still resolves fully.
        (let [live (:data (bfc/get-file th/*system* file-id :realize? true))]
          (t/is (empty? (fdata/get-used-pointer-ids live))))))))

(t/deftest snapshot-preview-resolves-after-offload
  ;; The preview path must resolve fragments that now live in the
  ;; storage backend, not just in db.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        snap    (fsnap/create! th/*system* f0 {:profile-id (:id profile)})]
    (t/is (uuid? (:id snap)))
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change
                            (first (:pages (:data f0)))
                            (uuid/random)))
    (let [result (th/run-task! :offload-file-data {:file-id file-id})]
      (t/is (zero? (:skipped result)))
      (t/is (pos? (:offloaded result))))
    (let [out (th/command! {::th/type :get-file-snapshot
                            ::rpc/profile-id (:id profile)
                            :file-id file-id
                            :id (:id snap)
                            :features cfeat/supported-features})]
      (t/is (nil? (:error out)))
      (t/is (empty? (fdata/get-used-pointer-ids
                     (get-in out [:result :data])))))))

(t/deftest with-tracked-tracks-and-fails-loud-without-loader
  ;; Direct pin of the with-tracked contract: wrapping tracks the new
  ;; pointer, and a pointer that needs loading raises loudly because
  ;; no loader is bound. (`wrap` carries its value inline so it
  ;; derefs fine; `create` with an id starts unloaded, so it must
  ;; load — and there is nothing to load from.)
  (fdata/with-tracked
    (let [wrapped  (pmap/wrap {:a 1})
          unloaded (pmap/create (uuid/next) {})]
      (t/is (pmap/pointer-map? wrapped))
      (t/is (pmap/pointer-map? unloaded))
      (t/is (contains? @pmap/*tracked* (pmap/get-id wrapped)))
      (t/is (contains? @pmap/*tracked* (pmap/get-id unloaded)))
      (t/is (= {:a 1} @wrapped))
      (t/is (thrown? UnsupportedOperationException @unloaded))))) \n
(t/deftest legacy-snapshot-does-not-break-liveness
  ;; Snapshots written before the feature carry materialized data in
  ;; file_change.data (backend "legacy-db") instead of a file_data row.
  ;; file-gc must scan them without failing, and the legacy preview must
  ;; still resolve. Setup pins the legacy shape: file_change row with
  ;; data, no file_data snapshot row.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-id (first (:pages (:data f0)))]
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change page-id (uuid/random)))
    ;; Drop every snapshot row so only the manual one below exists.
    (th/db-delete! :file-change {:file-id file-id})
    (th/db-delete! :file-data {:file-id file-id :type "snapshot"})
    (let [f1       (bfc/get-file th/*system* file-id)
          snap     (fsnap/create! th/*system* f1 {:profile-id (:id profile)})
          snap-id  (:id snap)
          realized (:data (fdata/realize th/*system* {:id file-id :data (:data (fsnap/get-snapshot th/*system* file-id snap-id))}))]
      ;; Convert to the legacy shape: materialize into file_change.data,
      ;; drop the file_data snapshot row.
      (th/db-delete! :file-data {:file-id file-id :type "snapshot"})
      (th/db-update! :file-change {:data (blob/encode realized)} {:id snap-id})
      ;; Pin the legacy shape: data on file_change, no file_data row.
      ;; (db-get filters rows with deleted_at set, and system snapshots
      ;; carry a future one, so opt out of that filter explicitly.)
      (t/is (nil? (seq (th/db-query :file-data {:file-id file-id :type "snapshot"}))))
      (t/is (some? (:data (th/db-get :file-change {:id snap-id} {::db/remove-deleted false})))))
    ;; file-gc scans the legacy snapshot without failing...
    (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
    ;; ...the live file still resolves fully...
    (let [live (:data (bfc/get-file th/*system* file-id :realize? true))]
      (t/is (empty? (fdata/get-used-pointer-ids live))))
    ;; ...and the legacy preview resolves with no leftover pointers.
    (let [snap-data (:data (fsnap/get-snapshot th/*system* file-id
                                               (->> (th/db-query :file-change {:file-id file-id})
                                                    (filter #(some? (:data %)))
                                                    (first)
                                                    (:id))))]
      (t/is (some? snap-data))
      (t/is (empty? (fdata/get-used-pointer-ids snap-data))))))

(t/deftest offload-edit-gc-cycle-keeps-liveness
  ;; Full cycle on one file: offload to cold storage, edit (which brings
  ;; rows back hot), file-gc. Liveness must stay correct throughout and
  ;; the file must resolve fully at the end. (R1's fix is what lets
  ;; metadata-bearing files survive the first step; this file carries no
  ;; metadata, so the cycle also passes without it.)
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-id (first (:pages (:data f0)))
        before  (into {} (map (juxt :id identity))
                      (th/db-query :file-data {:file-id file-id}))]
    (t/is (pos? (count before)))
    (let [result (th/run-task! :offload-file-data {:file-id file-id})]
      (t/is (zero? (:skipped result)))
      (t/is (pos? (:offloaded result))))
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change page-id (uuid/random)))
    ;; The edit wrote hot rows back: some file_data is db-backed again.
    (t/is (pos? (count (filter #(= "db" (:backend %))
                               (th/db-query :file-data {:file-id file-id})))))
    (th/db-delete! :file-change {:file-id file-id})
    (th/db-delete! :file-data {:file-id file-id :type "snapshot"})
    (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
    (let [live (:data (bfc/get-file th/*system* file-id :realize? true))]
      (t/is (empty? (fdata/get-used-pointer-ids live))))
    ;; Every referenced fragment is live.
    (let [used (fdata/get-used-pointer-ids (:data (bfc/get-file th/*system* file-id)))
          live (live-fragment-ids file-id)]
      (t/is (pos? (count used)))
      (t/is (every? live used)))))

(t/deftest save-keeps-untouched-page-fragments
  ;; A save must not rewrite the fragments of pages it does not touch,
  ;; even when the page's :objects map is a hash map (45 shapes), the
  ;; page itself has more than 8 keys (PersistentHashMap) and the
  ;; :pages-index is a hash map (9 pages). Re-assoc with an identical
  ;; value keeps map and pointer identity in array and hash maps alike.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-a  (uuid/random)
        others  (vec (repeatedly 8 uuid/random))
        page-b  (first others)
        guide   (uuid/random)
        flow    (uuid/random)
        thread  (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (into [{:type :add-page :name "a" :id page-a}]
                                 (map-indexed (fn [i pid]
                                                {:type :add-page
                                                 :name (str "p" i)
                                                 :id pid})
                                              others)))

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 1
                  :changes (into [{:type :mod-page
                                   :id page-a
                                   :background "#ffffff"
                                   :background-token "token"
                                   :pixel-grid-color "#000000"
                                   :pixel-grid-opacity 0.5}
                                  {:type :set-plugin-data
                                   :object-type :page
                                   :object-id page-a
                                   :page-id page-a
                                   :namespace "probe"
                                   :key "k"
                                   :value "1"}
                                  {:type :set-guide
                                   :page-id page-a
                                   :id guide
                                   :params {:id guide :axis :x :position 10}}
                                  {:type :set-flow
                                   :page-id page-a
                                   :id flow
                                   :params {:id flow
                                            :name "flow"
                                            :starting-frame uuid/zero}}
                                  {:type :set-comment-thread-position
                                   :page-id page-a
                                   :comment-thread-id thread
                                   :frame-id uuid/zero
                                   :position {:x 10 :y 10}}]
                                 (add-rect-changes page-a 45)))

    (let [ids-before (page-pointer-ids file-id)
          page       (fdata/with-pointers th/*system* file-id
                       (deref (get-in (:data (bfc/get-file th/*system* file-id))
                                      [:pages-index page-a])))]

      ;; The page is a hash map and its objects map is a hash map: the
      ;; scenario the churn suspicion was about.
      (t/is (instance? clojure.lang.PersistentHashMap page))
      (t/is (= 46 (count (:objects page))))

      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 2
                    :changes (add-rect-change page-b (uuid/random)))

      (let [ids-after (page-pointer-ids file-id)]
        (t/is (= (get ids-before page-a) (get ids-after page-a)))
        (t/is (not= (get ids-before page-b) (get ids-after page-b))))

      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 3
                    :changes (add-rect-change page-b (uuid/random)))

      (t/is (= (get ids-before page-a)
               (get (page-pointer-ids file-id) page-a))))))

(t/deftest objects-map-flip-leaves-unloaded-pages-until-edited
  ;; A data-level flip of `fdata/objects-map` does not rewrite pages the
  ;; save does not load: `enable-objects-map` skips unloaded pointers, so
  ;; the page keeps its plain fragment until it is edited. The first edit
  ;; wraps it once and later saves keep the new id.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-a  (uuid/random)
        page-b  (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (into [{:type :add-page :name "a" :id page-a}
                                  {:type :add-page :name "b" :id page-b}]
                                 (add-rect-changes page-a 45)))

    ;; Simulate a file created with pointer-map only: strip the objects
    ;; maps, drop the feature and persist through the same encode path.
    (db/tx-run! th/*system*
                (fn [cfg]
                  (fdata/with-tracked-pointers cfg file-id
                    (let [file (bfc/get-file cfg file-id)
                          file (fdata/disable-objects-map file)]
                      (bfc/update-file! cfg file)))))

    (let [page-plain (get (page-pointer-ids file-id) page-a)]
      (t/is (not (omap/objects-map? (fragment-objects file-id page-plain))))

      ;; Add the feature at data level, the way a migration would.
      (th/db-exec! ["update file
                        set features = array_append(features, 'fdata/objects-map')
                      where id = ?"
                    file-id])

      ;; A save on another page must leave the unloaded page alone.
      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 1
                    :changes (add-rect-change page-b (uuid/random)))

      (t/is (= page-plain (get (page-pointer-ids file-id) page-a)))
      (t/is (not (omap/objects-map? (fragment-objects file-id page-plain))))

      ;; The first edit of page A loads it and wraps it once.
      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 2
                    :changes (add-rect-change page-a (uuid/random)))

      (let [page-wrapped (get (page-pointer-ids file-id) page-a)]
        (t/is (not= page-plain page-wrapped))
        (t/is (omap/objects-map? (fragment-objects file-id page-wrapped)))

        (update-file! :profile-id (:id profile)
                      :file-id file-id
                      :revn 3
                      :changes (add-rect-change page-b (uuid/random)))

        ;; Converged: no further rewrite.
        (t/is (= page-wrapped (get (page-pointer-ids file-id) page-a)))))))

(t/deftest save-loads-only-edited-page
  ;; With objects-map enabled, encoding a save must not load the
  ;; fragments of pages the change does not touch: each fragment was
  ;; already wrapped when it was written. A three-page file with a
  ;; one-page edit loads exactly one fragment (the edited page).
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        f0      (bfc/get-file th/*system* file-id)
        page-1  (first (:pages (:data f0)))
        page-2  (uuid/random)
        page-3  (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-page :name "b" :id page-2}
                            {:type :add-page :name "c" :id page-3}
                            (first (add-rect-change page-2 (uuid/random)))])

    (let [loads (atom 0)
          load  fdata/load-pointer]
      (with-redefs [fdata/load-pointer
                    (fn [cfg file-id id]
                      (swap! loads inc)
                      (load cfg file-id id))]
        (update-file! :profile-id (:id profile)
                      :file-id file-id
                      :revn 1
                      :changes (add-rect-change page-2 (uuid/random))))

      (t/is (= 1 @loads))

      (let [ids (page-pointer-ids file-id)]
        (t/is (some? (get ids page-1)))
        (t/is (some? (get ids page-3)))))))

(t/deftest restore-does-not-load-shared-pages
  ;; Restoring a snapshot reuses the shared fragments; encoding the
  ;; restored file must not load the pages it does not change.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-1  (first (:pages (:data (bfc/get-file th/*system* file-id))))
        page-2  (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-page :name "b" :id page-2}
                            (first (add-rect-change page-2 (uuid/random)))])

    (let [f1        (bfc/get-file th/*system* file-id)
          snap      (fsnap/create! th/*system* f1 {:profile-id (:id profile)})
          page-2-id (get (page-pointer-ids file-id) page-2)]

      (update-file! :profile-id (:id profile)
                    :file-id file-id
                    :revn 1
                    :changes (add-rect-change page-1 (uuid/random)))

      (let [loads (atom 0)
            load  fdata/load-pointer]
        (with-redefs [fdata/load-pointer
                      (fn [cfg file-id id]
                        (swap! loads inc)
                        (load cfg file-id id))]
          (db/tx-run! th/*system*
                      (fn [cfg] (fsnap/restore! cfg file-id (:id snap)))))

        (t/is (zero? @loads))
        (t/is (= page-2-id (get (page-pointer-ids file-id) page-2)))))))

(t/deftest migration-keeps-unchanged-page-fragments
  ;; Applying a pending migration on read keeps the fragment ids of the
  ;; pages the migration does not change; a page it changes gets a new
  ;; id. The caller still receives realized data, not pointers.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-a  (uuid/random)
        page-b  (uuid/random)
        shape-b (uuid/random)]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-page :name "a" :id page-a}
                            {:type :add-page :name "b" :id page-b}
                            (first (add-rect-change page-a (uuid/random)))
                            (-> (first (add-rect-change page-b shape-b))
                                (update :obj assoc :applied-tokens
                                        {:stroke-width "token"}))])

    (let [ids-before (page-pointer-ids file-id)]
      ;; Force the 0031 migration on the next read.
      (th/db-delete! :file-migration
                     {:file-id file-id
                      :name "0031-migrate-stroke-width-token-attr"})

      (let [returned (db/tx-run! th/*system*
                                 (fn [cfg] (bfc/get-file cfg file-id)))]
        (t/is (not (pmap/pointer-map? (get-in returned [:data :pages-index page-a]))))
        (t/is (not (pmap/pointer-map? (get-in returned [:data :pages-index page-b]))))
        (t/is (not (fmg/need-migration? returned)))

        (let [tokens (get-in returned [:data :pages-index page-b
                                       :objects shape-b :applied-tokens])]
          (t/is (contains? tokens :stroke-width-top))
          (t/is (not (contains? tokens :stroke-width)))))

      (let [ids-after (page-pointer-ids file-id)]
        (t/is (= (get ids-before page-a) (get ids-after page-a)))
        (t/is (not= (get ids-before page-b) (get ids-after page-b))))

      ;; A second read neither migrates nor changes ids again.
      (let [ids-after (page-pointer-ids file-id)
            returned  (db/tx-run! th/*system*
                                  (fn [cfg] (bfc/get-file cfg file-id)))]
        (t/is (= ids-after (page-pointer-ids file-id)))
        (t/is (not (fmg/need-migration? returned)))))))

(t/deftest migration-read-only-keeps-fragments-untouched
  ;; A read-only migration returns realized data but does not persist:
  ;; the stored pointers and the pending migration stay as they were.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-1  (first (:pages (:data (bfc/get-file th/*system* file-id))))]

    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes (add-rect-change page-1 (uuid/random)))

    (th/db-delete! :file-migration
                   {:file-id file-id
                    :name "0031-migrate-stroke-width-token-attr"})

    (let [ids-before (page-pointer-ids file-id {:migrate? false})
          returned   (db/tx-run! th/*system*
                                 (fn [cfg] (bfc/get-file cfg file-id
                                                         :read-only? true)))]
      (t/is (not (pmap/pointer-map? (get-in returned [:data :pages-index page-1]))))
      (t/is (empty? (th/db-query :file-migration
                                 {:file-id file-id
                                  :name "0031-migrate-stroke-width-token-attr"})))
      (t/is (= ids-before (page-pointer-ids file-id {:migrate? false}))))

    ;; A normal read migrates, and the unchanged page keeps its id.
    (let [ids-before (page-pointer-ids file-id {:migrate? false})]
      (db/tx-run! th/*system* (fn [cfg] (bfc/get-file cfg file-id)))
      (t/is (= ids-before (page-pointer-ids file-id)))
      (t/is (seq (th/db-query :file-migration
                              {:file-id file-id
                               :name "0031-migrate-stroke-width-token-attr"}))))))
