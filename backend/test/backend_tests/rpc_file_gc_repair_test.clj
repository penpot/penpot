;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-gc-repair-test
  "Regression tests for file-gc on files whose stored data fails schema
  validation (see https://github.com/penpot/penpot/issues/12433)."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.test-helpers.files :as thf]
   [app.common.time :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.srepl.procs.file-repair :as procs-repair]
   [app.tasks.file-gc :as file-gc]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- wait-for-log
  "First captured log record matching pred, waiting up to the
  deadline because logging is asynchronous."
  [records pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (if-let [record (first (filter pred @records))]
        record
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 10)
          (recur))))))

(defn- error-with-hint
  "Log record predicate: an error log with the given hint."
  [hint]
  (fn [record]
    (let [props (:app.common.logging/props record)]
      (and (= :error (:app.common.logging/level record))
           (= hint (get props :hint))))))

(defn- apply-file-changes
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

(defn- read-file
  [file-id]
  (db/run! th/*system*
           (fn [cfg]
             (bfc/get-file cfg file-id :realize? true))))

(defn- persist-file
  [file]
  (db/tx-run! th/*system*
              (fn [cfg]
                (bfc/update-file! cfg file))))

(defn- repair-file!
  "Run the read and repair phases the way the task does: plain read
  with the current revn as baseline, then in-memory repair on the
  once-resolved libraries."
  [file-id]
  (let [props {:file-id file-id
               :revn (:revn (th/db-get :file {:id file-id}))}
        {:keys [file libs]} (#'file-gc/get-file th/*system* props)]
    (some-> file
            (#'file-gc/repair-file @libs))))

(defn- run-process-file!
  "Run the write phase alone on a repaired snapshot, mirroring the
  handler: timestamp set once, expensive scan outside the write
  transaction. Takes the snapshot's pre-sanitize data to decide the
  modified-at touch, like the handler does. The default only fits
  abort paths (no write happens there): anyone testing the write
  passes the pre-change data explicitly."
  [file & [orig-data]]
  (let [cfg                (assoc th/*system* :app.tasks.file-gc/timestamp (ct/now))
        deleted-components (ctkl/deleted-components-seq (:data file))
        used-remote        (#'file-gc/find-used-components cfg (:id file) deleted-components)
        orig-data          (or orig-data (:data file))]
    (db/tx-run! cfg #'file-gc/process-file used-remote deleted-components file orig-data)))

(defn- backdate-modified-at!
  "Push a file's modified-at far into the past so a later touch is
  deterministic: same-millisecond comparisons would be flaky."
  [file-id]
  (th/db-update! :file {:modified-at (ct/minus (ct/now) {:days 30})} {:id file-id}))

(defn- corrupt-with-nil-page
  "Mirrors production files where the `:pages` vector holds nil and
  `:pages-index` holds a nil key (see legacy-57, already applied on
  those files, so the nils came back through a write path)."
  [data]
  (-> data
      (update :pages conj nil)
      (assoc-in [:pages-index nil] {:id nil :name "ghost" :objects {}})))

(defn- setup-file-with-rect
  [profile file]
  (let [page-id  (first (:pages (:data file)))
        shape-id (uuid/next)]
    (apply-file-changes
     :profile-id (:id profile)
     :file-id (:id file)
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
    (:id file)))

(defn- instantiate-component
  "Adds a copy of the library component to the given file."
  [profile file library-id component-id]
  (let [page-id  (first (:pages (:data file)))
        shape-id (uuid/next)]
    (apply-file-changes
     :profile-id (:id profile)
     :file-id (:id file)
     :changes [{:type :add-obj
                :page-id page-id
                :id shape-id
                :parent-id uuid/zero
                :frame-id uuid/zero
                :components-v2 true
                :obj (cts/setup-shape
                      {:id shape-id
                       :name "Board"
                       :frame-id uuid/zero
                       :parent-id uuid/zero
                       :type :frame
                       :main-instance false
                       :component-root true
                       :component-file library-id
                       :component-id component-id})}])
    shape-id))

(defn- add-library-component
  "Adds a component (with its main instance) to the library file.
  Returns the map with the main shape id and the component id."
  [profile library component-name]
  (let [lib-page-id (first (get-in library [:data :pages]))
        shape-id    (uuid/next)
        component-id (uuid/next)]
    (apply-file-changes
     :profile-id (:id profile)
     :file-id (:id library)
     :changes [{:type :add-obj
                :page-id lib-page-id
                :id shape-id
                :parent-id uuid/zero
                :frame-id uuid/zero
                :components-v2 true
                :obj (cts/setup-shape
                      {:id shape-id
                       :name "Board"
                       :frame-id uuid/zero
                       :parent-id uuid/zero
                       :type :frame
                       :main-instance true
                       :component-root true
                       :component-file (:id library)
                       :component-id component-id})}
               {:type :add-component
                :path ""
                :name component-name
                :main-instance-id shape-id
                :main-instance-page lib-page-id
                :id component-id
                :anotation nil}])
    {:shape-id shape-id :component-id component-id :page-id lib-page-id}))

(t/deftest file-gc-cleans-library-used-by-several-files
  (let [profile (th/create-profile* 1)
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})
        users   (mapv (fn [i]
                        (th/create-file* i {:profile-id (:id profile)
                                            :project-id (:default-project-id profile)
                                            :is-shared false}))
                      [2 3 4])
        _       (doseq [user users]
                  (th/link-file-to-library*
                   {:file-id (:id user)
                    :library-id (:id library)}))
        used    (add-library-component profile library "Used")
        unused  (add-library-component profile library "Unused")]

    ;; Only the used component is instantiated in the user files.
    (doseq [user users]
      (instantiate-component profile user (:id library) (:component-id used)))

    ;; Delete both components (and their mains) from the library.
    (doseq [{:keys [component-id page-id shape-id]} [used unused]]
      (apply-file-changes
       :profile-id (:id profile)
       :file-id (:id library)
       :changes [{:type :del-component :id component-id}
                 {:type :del-obj :page-id page-id :id shape-id
                  :ignore-touched true}]))

    ;; The scan over library users must not hold a write transaction
    ;; open (it used to hold a db/plan cursor while loading every
    ;; user file, tripping idle-in-transaction timeouts in prod).
    (t/testing "file-gc cleans a library used by several files"
      (t/is (true? (:cleaned (th/run-file-gc! (:id library)))))
      (let [components (get-in (read-file (:id library)) [:data :components])]
        (t/is (contains? components (:component-id used))
              "component still used by library users is preserved")
        (t/is (not (contains? components (:component-id unused)))
              "component used nowhere is deleted"))
      (t/is (true? (:has-media-trimmed (th/db-get :file {:id (:id library)})))))))

(t/deftest file-gc-repairs-unconverted-schema-types-before-cleaning
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)]

    ;; Corrupt the stored data the way production files look today and
    ;; persist it through the real (non-validating) write path.
    (-> (read-file file-id)
        (update :data thf/corrupt-shape-types)
        (persist-file))

    ;; Pin the failure cause: the persisted file is schema-invalid.
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid file data"
                            (cfv/validate-file-schema! (read-file file-id))))

    ;; The file reads fine (no migration pending) but must fail at
    ;; clean-media validation until the repair is implemented.
    (backdate-modified-at! file-id)
    (t/testing "file-gc repairs schema-invalid types and completes"
      (let [revn-before (:revn (th/db-get :file {:id file-id}))
            modified-before (:modified-at (th/db-get :file {:id file-id}))]
        (t/is (true? (:cleaned (th/run-file-gc! file-id))))
        (t/is (= revn-before (:revn (th/db-get :file {:id file-id})))
              "curing data does not mint a new version")
        (t/is (ct/is-after? (:modified-at (th/db-get :file {:id file-id})) modified-before)
              "the cure touches modified-at so the ETag invalidates")
        (t/is (some? (cfv/validate-file-schema! (read-file file-id)))
              "the healed data was still persisted")
        (t/is (true? (:has-media-trimmed (th/db-get :file {:id file-id}))))))))

(t/deftest file-gc-repairs-nil-pages-that-fail-at-migration
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)]

    (-> (read-file file-id)
        (update :data corrupt-with-nil-page)
        (persist-file))

    ;; Force the read-time migration check by dropping the applied
    ;; migrations, mirroring production files that fail inside
    ;; get-file/migrate-file before cleaning ever starts.
    (th/db-delete! :file-migration {:file-id file-id})

    ;; Since the 0027 migration heals nil pages, the read now
    ;; succeeds where it used to throw invalid file data.

    (backdate-modified-at! file-id)
    (t/testing "file-gc repairs nil pages and completes"
      (let [revn-before (:revn (th/db-get :file {:id file-id}))
            modified-before (:modified-at (th/db-get :file {:id file-id}))]
        (t/is (true? (:cleaned (th/run-file-gc! file-id))))
        (t/is (= revn-before (:revn (th/db-get :file {:id file-id})))
              "curing data does not mint a new version")
        (t/is (ct/is-after? (:modified-at (th/db-get :file {:id file-id})) modified-before)
              "the cure touches modified-at so the ETag invalidates")
        (t/is (some? (cfv/validate-file-schema! (read-file file-id)))
              "the healed data was still persisted")
        (t/is (true? (:has-media-trimmed (th/db-get :file {:id file-id}))))))))

(t/deftest file-gc-leaves-pristine-file-at-same-revn
  ;; A pristine file has nothing to repair or collect: the run marks
  ;; it trimmed without minting a new version.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)]
    (let [revn-before (:revn (th/db-get :file {:id file-id}))
          modified-before (:modified-at (th/db-get :file {:id file-id}))
          result      (th/run-file-gc! file-id)]
      (t/is (true? (:cleaned result)))
      (t/is (= :cleaned (:reason result)))
      (t/is (= revn-before (:revn (th/db-get :file {:id file-id})))
            "a no-op GC run does not mint a new version")
      (t/is (= modified-before (:modified-at (th/db-get :file {:id file-id})))
            "a no-op GC run touches nothing")
      (t/is (true? (:has-media-trimmed (th/db-get :file {:id file-id})))
            "the file is marked as trimmed"))))

(defn- corrupt-with-unknown-type
  "Corruption no migration can heal: an unknown shape type. The file
  must keep failing loudly instead of being silently rewritten."
  [data page-id shape-id]
  (assoc-in data [:pages-index page-id :objects shape-id :type] "triangle"))

(t/deftest file-gc-read-does-not-persist-without-repair
  ;; White-box: read and repair return the sanitized snapshot but
  ;; write nothing when the repair loop finds no errors; only the
  ;; write phase persists.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        file    (read-file file-id)
        page-id (first (:pages (:data file)))
        shape-id (first (remove #{uuid/zero}
                                (filter uuid? (keys (get-in file [:data :pages-index page-id :objects])))))]
    (t/is (some? shape-id) "a non-root shape exists")

    (-> (read-file file-id)
        (update :data thf/corrupt-shape-types)
        (persist-file))

    (let [revn-before (:revn (th/db-get :file {:id file-id}))
          repaired (repair-file! file-id)]
      (t/is (some? repaired) "read and repair return the snapshot")
      (t/testing "read and repair persist nothing"
        (t/is (= revn-before (:revn (th/db-get :file {:id file-id})))
              "the stored revn is untouched")
        ;; Stored keys keep the corrupted forms (strings in
        ;; pages-index, keywords in objects): decode does not
        ;; normalize them, sanitize does the value coercion.
        (t/is (= "rect" (get-in (read-file file-id)
                                [:data :pages-index (str page-id) :objects (keyword (str shape-id)) :type]))
              "the stored data keeps the unconverted type")
        (let [shapes (vals (get-in repaired [:data :pages-index page-id :objects]))
              rect   (first (filter #(= "rect" (:name %)) shapes))]
          (t/is (some? rect) "the snapshot keeps the shape")
          (t/is (= :rect (:type rect))
                "the returned snapshot is sanitized"))))))

(t/deftest file-gc-gives-up-cleanly-on-unfixable-file
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        file    (read-file file-id)
        page-id (first (:pages (:data file)))
        shape-id (first (remove #{uuid/zero}
                                (filter uuid? (keys (get-in file [:data :pages-index page-id :objects])))))]
    (t/is (some? shape-id) "a non-root shape exists")

    (-> (read-file file-id)
        (update :data #(corrupt-with-unknown-type % page-id shape-id))
        (persist-file))

    ;; Force the read-time migration check: the single read path
    ;; cannot fix an unknown type.
    (th/db-delete! :file-migration {:file-id file-id})

    (let [revn-before (:revn (th/db-get :file {:id file-id}))
          records (atom [])
          _ (add-watch l/log-record ::capture
                       (fn [_ _ _ record] (swap! records conj record)))]
      (try
        (let [result (th/run-file-gc! file-id)
              ;; Keep the watch installed while waiting: log emission
              ;; is asynchronous, removing it right after run-task!
              ;; would drop the record before it arrives.
              cleaning (wait-for-log records (error-with-hint "error on cleaning file"))]
          ;; The repair failure propagates to the outer handler catch:
          ;; loud in Loki, never half-persisted. No phased wrapper
          ;; anymore: the raw cause reaches the log with its stack
          ;; trace. The handler reports the outcome instead of nil so
          ;; the cause travels with the result.
          (t/testing "file-gc gives up cleanly on unfixable files"
            (t/is (false? (:cleaned result)))
            (t/is (= :unfixable (:reason result))
                  "the cause travels with the result")
            (t/is (= file-id (:file-id result)))
            (t/is (false? (:has-media-trimmed (th/db-get :file {:id file-id})))
                  "file is not marked as trimmed")
            (t/is (= revn-before (:revn (th/db-get :file {:id file-id})))
                  "no half transaction was persisted")
            (t/is (some? cleaning)
                  "the failure is reported")))
        (finally
          (remove-watch l/log-record ::capture))))))

(defn- corrupt-with-broken-child
  "Semantic breakage that repair-file heals: a dangling child id on the
  root shape. Validation reports :child-not-found and the repair drops
  the id, so the file is clean afterwards."
  [data page-id]
  (update-in data [:pages-index page-id :objects uuid/zero :shapes] conj (uuid/next)))

(t/deftest file-gc-write-aborts-when-file-changed
  ;; White-box: the handler always reads and writes back to back, so
  ;; the revn-abort is only isolable by calling run-process-file!
  ;; directly.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        page-id (first (:pages (:data (read-file file-id))))]

    (-> (read-file file-id)
        (update :data #(corrupt-with-broken-child % page-id))
        (persist-file))

    (let [repaired (repair-file! file-id)]
      (t/is (some? repaired) "read and repair return the file")
      ;; Concurrent write after the read, based on the stored revn:
      ;; the snapshot still carries it untouched.
      (let [stored-revn (:revn (th/db-get :file {:id file-id}))]
        ;; Concurrent write after the read: bump revn through the real RPC path.
        (apply-file-changes
         :profile-id (:id profile)
         :file-id file-id
         :revn stored-revn
         :changes [{:type :mod-page :id page-id :name "bumped"}])
        (let [revn-after-bump (:revn (th/db-get :file {:id file-id}))
              result          (run-process-file! repaired)]
          (t/testing "the write aborts on stale revn without writing"
            (t/is (false? result))
            (t/is (false? (:has-media-trimmed (th/db-get :file {:id file-id})))
                  "the file is not marked as trimmed")
            (t/is (= revn-after-bump (:revn (th/db-get :file {:id file-id})))
                  "the write persisted nothing")
            (t/is (> revn-after-bump stored-revn)
                  "the concurrent bump is still there")))))))

(t/deftest file-gc-write-skips-when-file-deleted
  ;; White-box like the stale-revn test: delete the file after the
  ;; read and call run-process-file! directly with its snapshot. The
  ;; light row lock sees the deleted row and the write skips without
  ;; writing.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)]
    (let [repaired (repair-file! file-id)]
      (t/is (some? repaired) "read and repair return the file")
      (th/db-update! :file {:deleted-at (ct/now)} {:id file-id})
      (let [result (run-process-file! repaired)
            row    (th/db-get :file {:id file-id} {::db/remove-deleted false})]
        (t/testing "the write skips a file deleted after the read without writing"
          (t/is (false? result))
          (t/is (some? (:deleted-at row)) "still deleted")
          (t/is (false? (:has-media-trimmed row))
                "the file is not marked as trimmed"))))))

(t/deftest file-gc-write-persists-the-repair
  ;; White-box: read and repair work in memory and write nothing;
  ;; only the write phase persists. Direct phase calls isolate each
  ;; side, the handler always runs them together.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        file    (read-file file-id)
        page-id (first (:pages (:data file)))]

    (let [corrupted (-> (read-file file-id)
                        (update :data #(corrupt-with-broken-child % page-id)))]
      (persist-file corrupted)

      (let [stored-before (th/db-get :file {:id file-id})
            repaired      (repair-file! file-id)]
        (t/is (some? repaired) "read and repair return the snapshot")
        (t/testing "read and repair persist nothing"
          (t/is (= (:revn stored-before) (:revn (th/db-get :file {:id file-id})))
                "the stored revn is untouched")
          (t/is (nil? (cfv/validate-file repaired {}))
                "the snapshot validates clean"))
        (backdate-modified-at! file-id)
        (t/testing "the write persists the repair"
          (t/is (true? (run-process-file! repaired (:data corrupted))))
          (let [row-after-write (th/db-get :file {:id file-id})]
            (t/is (= (:revn stored-before) (:revn row-after-write))
                  "a GC write never mints a new version")
            (t/is (ct/is-after? (:modified-at row-after-write) (:modified-at stored-before))
                  "persisting changed data touches modified-at")
            (t/is (nil? (cfv/validate-file (read-file file-id) {}))
                  "the persisted file validates clean")
            (t/is (true? (:has-media-trimmed row-after-write))
                  "the file is marked as trimmed")))))))

(t/deftest file-gc-cleans-without-revn-in-props
  ;; Pins the nil-revn tolerance in get-file (TODO(2.20)): props without
  ;; revn still clean. No bump at all anymore: the run only touches
  ;; modified-at when the persisted snapshot differs (change-on-clean).
  ;; If the tolerance is ever dropped, this test fails and must change
  ;; together with the behavior.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        page-id (first (:pages (:data (read-file file-id))))]

    (-> (read-file file-id)
        (update :data #(corrupt-with-broken-child % page-id))
        (persist-file))

    (backdate-modified-at! file-id)
    (let [revn-before (:revn (th/db-get :file {:id file-id}))
          modified-before (:modified-at (th/db-get :file {:id file-id}))
          result      (th/run-task! :file-gc {:file-id file-id})]
      (t/is (true? (:cleaned result))
            "the file is cleaned without revn in props")
      (t/is (= revn-before (:revn (th/db-get :file {:id file-id})))
            "a GC write never mints a new version")
      (t/is (ct/is-after? (:modified-at (th/db-get :file {:id file-id})) modified-before)
            "persisting changed data touches modified-at")
      (t/is (true? (:has-media-trimmed (th/db-get :file {:id file-id})))
            "the file is marked as trimmed"))))

(t/deftest file-gc-skips-soft-deleted-file-without-error
  ;; A file soft-deleted between scheduling and running is a normal
  ;; race: the read returns nil through the plain path and the handler
  ;; reports {:cleaned false :reason :gone}, without logging an ERROR.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        revn    (:revn (th/db-get :file {:id file-id}))]

    (th/db-update! :file {:deleted-at (ct/now)} {:id file-id})

    (let [records (atom [])
          _ (add-watch l/log-record ::capture
                       (fn [_ _ _ record] (swap! records conj record)))]
      (try
        (let [result (th/run-task! :file-gc {:file-id file-id :revn revn})]
          ;; Log emission is asynchronous: give a late ERROR the
          ;; chance to arrive before asserting its absence.
          (th/sleep 1000)
          (let [cleaning-errors (filter (error-with-hint "error on cleaning file") @records)]
            (t/testing "a file deleted after scheduling is skipped quietly"
              (t/is (false? (:cleaned result))
                    "the file is reported as not cleaned, not nil")
              (t/is (= :gone (:reason result))
                    "the cause travels with the result")
              (t/is (= file-id (:file-id result))
                    "the file id is echoed back")
              (t/is (empty? cleaning-errors)
                    "no ERROR is logged for this normal race"))))
        (finally
          (remove-watch l/log-record ::capture))))))

(t/deftest file-gc-skips-when-props-revn-is-stale
  ;; A file modified between scheduling and running is a normal
  ;; race: the read-time revn check returns nil and the handler
  ;; reports {:cleaned false :reason :stale-revn} quietly, leaving
  ;; the file pending so the scheduler retries it later.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        stale-revn (:revn (th/db-get :file {:id file-id}))
        page-id (first (:pages (:data (read-file file-id))))]

    ;; Concurrent write through the real RPC path.
    (apply-file-changes
     :profile-id (:id profile)
     :file-id file-id
     :revn stale-revn
     :changes [{:type :mod-page :id page-id :name "bumped"}])

    (let [result (th/run-task! :file-gc {:file-id file-id :revn stale-revn})]
      (t/testing "a file changed after scheduling is skipped quietly"
        (t/is (false? (:cleaned result))
              "the file is reported as not cleaned")
        (t/is (= :stale-revn (:reason result))
              "the cause travels with the result")
        (t/is (= file-id (:file-id result))
              "the file id is echoed back")
        (t/is (false? (:has-media-trimmed (th/db-get :file {:id file-id})))
              "the file is not marked as trimmed")))))

(t/deftest srepl-repair-delegate-repairs-like-the-shared-loop
  ;; Regression: procs/repair-file is called by h/process-file! with
  ;; a positional opts map lacking :max-iterations. It must repair
  ;; like the shared loop instead of silently returning the input.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        page-id (first (:pages (:data (read-file file-id))))
        broken  (-> (read-file file-id)
                    (update :data #(corrupt-with-broken-child % page-id)))]
    (t/is (some? (seq (cfv/validate-file broken {})))
          "the file is broken")
    (let [fixed (procs-repair/repair-file broken {} {:rollback? true})]
      (t/is (nil? (cfv/validate-file fixed {}))
            "the delegate converges like the shared loop"))
    (t/testing "the delegate also heals unconverted types once sanitized"
      (let [unconverted (-> (read-file file-id)
                            (update :data thf/corrupt-shape-types))
            fixed       (procs-repair/repair-file unconverted {})]
        (t/is (false? (identical? unconverted fixed))
              "sanitize lets the loop converge instead of returning the input")
        (t/is (nil? (cfv/validate-file fixed {}))
              "the healed snapshot validates clean")))))

(t/deftest file-gc-offload-failure-keeps-truthful-result
  ;; A submit! failure after a successful write must not falsify the
  ;; outcome: the file is clean, so the handler reports cleaned and
  ;; logs the offload error instead of claiming unfixable.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)]
    (let [records (atom [])
          _ (add-watch l/log-record ::capture
                       (fn [_ _ _ record] (swap! records conj record)))]
      (try
        (let [result (with-redefs [cf/flags (conj cf/flags :tiered-file-data-storage)
                                   wrk/submit! (fn [_] (throw (ex-info "redis down" {})))]
                       (th/run-file-gc! file-id))
              ;; Keep the watch installed while waiting: log emission
              ;; is asynchronous, removing it right after run-file-gc!
              ;; would drop the record before it arrives.
              offload (wait-for-log records (error-with-hint "error submitting file-gc offload"))]
          (t/testing "a failed offload keeps the truthful cleaning result"
            (t/is (true? (:cleaned result)))
            (t/is (= :cleaned (:reason result))
                  "the result describes the file, not the queue")
            (t/is (= file-id (:file-id result)))
            (t/is (true? (:has-media-trimmed (th/db-get :file {:id file-id})))
                  "the cleaning was persisted")
            (t/is (some? offload)
                  "the offload failure is logged")))
        (finally
          (remove-watch l/log-record ::capture))))))

(t/deftest file-gc-survives-unsanitized-libraries
  ;; A library with decoder leftovers (text ids, string types) breaks
  ;; ref resolution; the GC must sanitize libraries on load instead of
  ;; detaching the copies that point at them.
  (let [profile (th/create-profile* 1)
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})
        user    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        _       (th/link-file-to-library*
                 {:file-id (:id user)
                  :library-id (:id library)})
        used    (add-library-component profile library "Used")
        shape-id (instantiate-component profile user (:id library) (:component-id used))]

    ;; The raw add-obj above bypasses component sync, so point the
    ;; copy at the main root like a real instantiation would.
    (let [page-id (first (:pages (:data (read-file (:id user)))))]
      (-> (read-file (:id user))
          (assoc-in [:data :pages-index page-id :objects shape-id :shape-ref]
                    (:shape-id used))
          (persist-file)))

    ;; Corrupt the library the way production data looks, then run GC
    ;; on the user before the library itself is ever cleaned.
    (-> (read-file (:id library))
        (update :data thf/corrupt-shape-types)
        (persist-file))

    (let [result (th/run-file-gc! (:id user))]
      (t/testing "a copy on a corrupt library survives the GC"
        (t/is (true? (:cleaned result)))
        (let [page-id (first (:pages (:data (read-file (:id user)))))
              copy    (get-in (read-file (:id user))
                              [:data :pages-index page-id :objects shape-id])]
          (t/is (some? (:component-id copy))
                "the copy keeps its component")
          (t/is (some? (:component-file copy))
                "the copy keeps its library")
          (t/is (some? (:shape-ref copy))
                "the copy keeps its master ref"))))))

(t/deftest file-gc-repairs-through-loaded-library
  ;; The library loads lazily through the weak-map loader when the
  ;; user file references it. If that load throws, the repair loop
  ;; swallows the error and keeps the original file: nothing is
  ;; repaired and the run still looks clean. A linked library with a
  ;; remote copy must load without errors while a dangling child is
  ;; repaired, proving validation really ran through the loaded
  ;; library.
  (let [profile (th/create-profile* 1)
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})
        user    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        _       (th/link-file-to-library*
                 {:file-id (:id user)
                  :library-id (:id library)})
        used    (add-library-component profile library "Used")
        shape-id (instantiate-component profile user (:id library) (:component-id used))
        page-id (first (:pages (:data (read-file (:id user)))))
        bogus   (uuid/next)]
    ;; The test env enables every feature it supports on every file;
    ;; production never enables fdata/pointer-map. Re-persist both
    ;; files without it so this regression covers the data shape
    ;; production actually has: plain data, objects-map lazy only.
    (doseq [f [library user]]
      (-> (read-file (:id f))
          (assoc :features (disj (set (:features f)) "fdata/pointer-map"))
          (persist-file)))
    ;; The raw add-obj above bypasses component sync, so point the
    ;; copy at the main root like a real instantiation would, and
    ;; add a dangling child id that only validation can heal.
    (-> (read-file (:id user))
        (assoc-in [:data :pages-index page-id :objects shape-id :shape-ref]
                  (:shape-id used))
        (update-in [:data :pages-index page-id :objects uuid/zero :shapes]
                   conj bogus)
        (persist-file))

    (let [result (th/run-file-gc! (:id user))]
      (t/testing "validation loads the remote library and repairs locally"
        (t/is (true? (:cleaned result)))
        (let [data (:data (read-file (:id user)))
              copy (get-in data [:pages-index page-id :objects shape-id])
              root (get-in data [:pages-index page-id :objects uuid/zero])]
          (t/is (some? (:component-id copy))
                "the copy keeps its component")
          (t/is (some? (:shape-ref copy))
                "the copy keeps its master ref")
          (t/is (not (contains? (set (:shapes root)) bogus))
                "the dangling child was repaired, so validation ran"))))))

(t/deftest file-gc-gives-up-when-repair-does-not-converge
  ;; A file the repair loop cannot make clean must never be
  ;; persisted as a clean snapshot. The non-convergence raises, the
  ;; handler classifies it as :unfixable, nothing is marked and the
  ;; revn stays put so any manual fix has the original revision to
  ;; work on.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)
        page-id (first (:pages (:data (read-file file-id))))]
    (-> (read-file file-id)
        (update :data #(corrupt-with-broken-child % page-id))
        (persist-file))

    (backdate-modified-at! file-id)

    (let [{:keys [revn modified-at]} (th/db-get :file {:id file-id})
          result (with-redefs [cfr/repair-file (fn [& _] [])]
                   (th/run-file-gc! file-id))]
      (t/testing "the run reports the file as unfixable"
        (t/is (false? (:cleaned result)))
        (t/is (= :unfixable (:reason result))
              "the cause travels with the result")
        (t/is (= file-id (:file-id result))))
      (t/testing "nothing is persisted"
        (t/is (false? (:has-media-trimmed (th/db-get :file {:id file-id})))
              "file is not marked as trimmed")
        (t/is (= revn (:revn (th/db-get :file {:id file-id})))
              "no revision was persisted")
        (t/is (= modified-at (:modified-at (th/db-get :file {:id file-id})))
              "no modification touch was persisted")))))

(t/deftest file-gc-scan-counts-unsanitized-copies-as-used
  ;; Same corruption family on the consuming side: a user file with
  ;; text ids makes instance-of? miss, so the scan must sanitize
  ;; before asking who uses a deleted component.
  (let [profile (th/create-profile* 1)
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})
        user    (th/create-file* 2 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        _       (th/link-file-to-library*
                 {:file-id (:id user)
                  :library-id (:id library)})
        used    (add-library-component profile library "Used")
        shape-id (instantiate-component profile user (:id library) (:component-id used))]

    ;; Delete the component (and its main) from the library.
    (apply-file-changes
     :profile-id (:id profile)
     :file-id (:id library)
     :changes [{:type :del-component :id (:component-id used)}
               {:type :del-obj :page-id (:page-id used) :id (:shape-id used)
                :ignore-touched true}])

    ;; Corrupt the user the way production data looks, including the
    ;; component refs as text: instance-of? compares with = so the
    ;; copy would not count as a use without sanitizing first.
    (let [page-id (first (:pages (:data (read-file (:id user)))))
          obj-key (keyword (str shape-id))]
      (-> (read-file (:id user))
          (update :data thf/corrupt-shape-types)
          (update-in [:data :pages-index (str page-id) :objects obj-key :component-id] str)
          (update-in [:data :pages-index (str page-id) :objects obj-key :component-file] str)
          (persist-file)))

    (let [result (th/run-file-gc! (:id library))]
      (t/testing "a deleted component still used counts as used"
        (t/is (true? (:cleaned result)))
        (t/is (contains? (get-in (read-file (:id library)) [:data :components])
                         (:component-id used))
              "the component used by the unsanitized copy is preserved")))))

(t/deftest file-gc-scan-loads-users-outside-write-transaction
  ;; Pins the design rule behind the idle-in-transaction timeouts:
  ;; every library-user file loads on its own connection, never with
  ;; the write transaction held open. If the scan moves back inside,
  ;; the captured cfgs carry ::db/conn and this fails.
  (let [profile (th/create-profile* 1)
        library (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared true})
        users   (mapv (fn [i]
                        (th/create-file* i {:profile-id (:id profile)
                                            :project-id (:default-project-id profile)
                                            :is-shared false}))
                      [2 3])
        _       (doseq [user users]
                  (th/link-file-to-library*
                   {:file-id (:id user)
                    :library-id (:id library)}))
        used    (add-library-component profile library "Used")]
    (doseq [user users]
      (instantiate-component profile user (:id library) (:component-id used)))
    (doseq [{:keys [component-id page-id shape-id]} [used]]
      (apply-file-changes
       :profile-id (:id profile)
       :file-id (:id library)
       :changes [{:type :del-component :id component-id}
                 {:type :del-obj :page-id page-id :id shape-id
                  :ignore-touched true}]))

    (let [seen  (atom [])
          orig  bfc/get-file]
      (with-redefs [bfc/get-file (fn [cfg & args]
                                   (swap! seen conj cfg)
                                   (apply orig cfg args))]
        (t/is (true? (:cleaned (th/run-file-gc! (:id library))))))
      (t/testing "user files load without a write connection"
        (t/is (seq @seen) "the scan loaded files through bfc/get-file")
        (t/is (every? #(nil? (::db/conn %)) @seen)
              "no load happens inside the write transaction")))))

(t/deftest file-gc-reports-error-on-transient-failure
  ;; Unexpected failures (DB down, storage hiccup) are not data
  ;; corruption: they report :error so operators stop reading every
  ;; unfixable as a broken file. The file stays unmarked either way,
  ;; so the scheduler retries the run later.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (setup-file-with-rect profile file)]
    (let [records (atom [])
          _ (add-watch l/log-record ::capture
                       (fn [_ _ _ record] (swap! records conj record)))]
      (try
        (let [result (with-redefs [db/tx-run! (fn [& _] (throw (RuntimeException. "db down")))]
                       (th/run-file-gc! file-id))
              logged (wait-for-log records (error-with-hint "error on cleaning file"))]
          (t/testing "transient failures report :error"
            (t/is (false? (:cleaned result)))
            (t/is (= :error (:reason result))
                  "the cause is not mistaken for corrupt data")
            (t/is (= file-id (:file-id result)))
            (t/is (false? (:has-media-trimmed (th/db-get :file {:id file-id})))
                  "the file is not marked as cleaned")
            (t/is (some? logged)
                  "the failure is still loud")))
        (finally
          (remove-watch l/log-record ::capture))))))
