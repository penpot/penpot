;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-catch-up-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   java.util.Base64
   java.util.Random))

(defn- with-catch-up
  [next]
  (binding [cf/flags (conj cf/flags :file-catch-up)]
    (next)))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset with-catch-up)

(defn- set-boundary
  [file-id boundary]
  (th/db-update! :file-data
                 {:metadata (db/json {:catch-up-boundary boundary})}
                 {:file-id file-id :id file-id}))

(defn- create-file
  [& {:keys [seed-boundary custom-project] :or {seed-boundary true} :as options}]
  (let [profile    (th/create-profile* 1)
        project-id (if custom-project
                     (:id (th/create-project* 1 {:profile-id (:id profile)
                                                 :team-id (:default-team-id profile)}))
                     (:default-project-id profile))
        file       (th/create-file* 1 (merge {:profile-id (:id profile) :project-id project-id}
                                             (dissoc options :seed-boundary :custom-project)))]
    (when seed-boundary
      (set-boundary (:id file) {:kind "genesis" :vern 0 :revn (:revn file)}))
    {:profile-id (:id profile)
     :project-id project-id
     :file-id (:id file)
     :page-id (first (get-in file [:data :pages]))
     :shape-id (uuid/next)
     :session-id (uuid/next)}))

(defn- save
  [{:keys [file-id profile-id session-id vern] :or {vern 0}} revn changes]
  (th/command! {::th/type :update-file-v2
                ::rpc/profile-id profile-id
                :id file-id
                :session-id session-id
                :features cfeat/supported-features
                :revn revn
                :vern vern
                :repair-in-response true
                :commits [{:commit-id (uuid/next)
                           :changes changes}]}))

(defn- rename-shape
  [{:keys [page-id shape-id]} name]
  [{:type :mod-obj :page-id page-id :id shape-id
    :operations [{:type :set :attr :name :val name}]}])

(defn- add-shape
  [{:keys [shape-id page-id] :as ctx}]
  (let [out (save ctx 0 [(th/add-rect-change page-id shape-id)])]
    (t/is (= 1 (get-in out [:result :saved-revn])))))

(defn- get-file
  [{:keys [file-id profile-id]}]
  (let [out (th/command! {::th/type :get-file
                          ::rpc/profile-id profile-id
                          :id file-id})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- catch-up-params
  [{:keys [file-id profile-id]} since options]
  (merge {::th/type :get-file-changes
          ::rpc/profile-id profile-id
          :file-id file-id
          :since since
          :vern 0}
         options))

(defn- catch-up
  [ctx since & {:as options}]
  (th/command! (catch-up-params ctx since options)))

(defn- save-rename
  [ctx revn name]
  (let [out (save ctx revn (rename-shape ctx name))]
    (t/is (= (inc revn) (get-in out [:result :saved-revn])))
    (-> out :result :written first :id)))

(t/deftest replays-exactly-the-file-data-at-through
  (let [ctx   (create-file)
        _     (add-shape ctx)
        base  (get-file ctx)
        redo  (rename-shape ctx "Renamed")
        saved (save ctx 1 redo)
        after (get-file ctx)
        later (save ctx 2 (rename-shape ctx "Later"))
        out   (th/command-raw! (catch-up-params ctx 1 {:until 2}))]
    (t/is (= 3 (get-in later [:result :saved-revn])))
    (t/is (= {:head 3 :vern 0 :through 2
              :rows [{:id (-> saved :result :written first :id)
                      :revn 2 :session-id (:session-id ctx) :changes redo}]}
             out))
    (t/is (= (:data after)
             (reduce #(cpc/process-changes %1 (:changes %2)) (:data base) (:rows out))))
    (t/is (= "no-store" (get-in (meta out) [:app.http/headers "cache-control"])))))

(t/deftest confirms-only-the-exact-adjacent-row
  (let [ctx     (create-file)
        _       (add-shape ctx)
        _       (save ctx 1 (rename-shape ctx "Remote"))
        saved   (save ctx 2 (rename-shape ctx "Own"))
        own-id  (-> saved :result :written first :id)
        out     (:result (catch-up ctx "1" :until "2" :confirm-revn "3" :confirm-id (str own-id)))]
    (t/is (= [2] (mapv :revn (:rows out))))
    (t/is (= {:revn 3 :id own-id} (:confirmed-row out)))
    (t/is (= {:head 3 :vern 0 :reload true}
             (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id (uuid/next)))))
    (t/is (= {:head 3 :vern 0 :reload true}
             (:result (catch-up ctx 1 :until 1 :confirm-revn 3 :confirm-id own-id))))
    (t/is (th/ex-of-code? (:error (catch-up ctx 1 :until 2 :confirm-revn 3))
                          :params-validation))))

(t/deftest disabled-endpoint-never-answers-up-to-date
  (let [ctx (create-file)
        out (binding [cf/flags (disj cf/flags :file-catch-up)]
              (th/command-raw! (catch-up-params ctx 0 {})))]
    (t/is (= {:head 0 :vern 0 :reload true} out))
    (t/is (= "no-store" (get-in (meta out) [:app.http/headers "cache-control"])))))

(t/deftest a-missing-row-beyond-the-recovery-window-requires-reload
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        saved  (save ctx 1 (rename-shape ctx "Older rename"))
        _      (save ctx 2 (rename-shape ctx "Newer rename"))
        row-id (-> saved :result :written first :id)
        now    (ct/inst (inst-ms (ct/now)))
        cutoff (ct/minus now (ct/duration "24h"))
        where  {:file-id file-id :id row-id}]
    (with-redefs [ct/now (constantly now)]
      (th/db-update! :file-change {:created-at (ct/plus cutoff 1)} where)
      (t/is (= [2 3] (mapv :revn (get-in (catch-up ctx 1) [:result :rows]))))
      (th/db-update! :file-change {:created-at cutoff} where)
      (t/is (= [2 3] (mapv :revn (get-in (catch-up ctx 1) [:result :rows]))))
      (th/db-update! :file-change {:created-at (ct/minus cutoff 1)} where)
      (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
        (t/is (= {:head 3 :vern 0 :reload true} (:result (catch-up ctx 1))))))))

(t/deftest a-post-offer-can-expire-before-the-confirming-get
  (let [{:keys [file-id] :as ctx} (create-file)
        now    (ct/inst (inst-ms (ct/now)))
        cutoff (ct/minus now (ct/duration "24h"))]
    (binding [cf/flags (conj cf/flags :file-change-retention)
              ct/*clock* (ct/fixed-clock now)]
      (add-shape ctx)
      (let [remote-id (save-rename ctx 1 "Remote")]
        (th/db-update! :file-change {:created-at (ct/plus cutoff 1)}
                       {:file-id file-id :id remote-id})
        (let [out    (:result (save ctx 1 (rename-shape ctx "Own")))
              own-id (get-in out [:written 0 :id])]
          (t/is (= {:mode :catch-up :since 1 :until 2 :required [{:revn 2 :id remote-id}]}
                   (:recovery out)))
          (t/is (= {:revn 3 :id own-id}
                   (get-in (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id)
                           [:result :confirmed-row])))
          (binding [ct/*clock* (ct/fixed-clock (ct/plus now 2))]
            (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
              (t/is (= {:head 3 :vern 0 :reload true}
                       (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id)))))))))))

(t/deftest a-live-confirmation-is-proved-even-if-it-is-older-than-the-missing-deltas
  (let [{:keys [file-id] :as ctx} (create-file)
        now (ct/inst (inst-ms (ct/now)))
        old (ct/minus now (ct/duration "25h"))]
    (binding [cf/flags (conj cf/flags :file-change-retention)
              ct/*clock* (ct/fixed-clock now)]
      (add-shape ctx)
      (let [remote-id (save-rename ctx 1 "Remote")
            own-id    (save-rename ctx 2 "Own")
            where     {:file-id file-id :id own-id}
            confirmed {:revn 3 :id own-id}
            reload    {:head 3 :vern 0 :reload true}]
        (th/db-update! :file-change {:created-at old} where)
        (let [out (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id))]
          (t/is (= [remote-id] (mapv :id (:rows out))))
          (t/is (= confirmed (:confirmed-row out))))
        (t/is (= {:head 3 :vern 0 :through 2 :rows [] :confirmed-row confirmed}
                 (:result (catch-up ctx 2 :until 2 :confirm-revn 3 :confirm-id own-id))))
        (t/is (= reload (:result (catch-up ctx 2))))
        (t/is (= reload (:result (catch-up ctx 2 :until 2 :confirm-revn 3 :confirm-id (uuid/next)))))

        (let [data (:changes (th/db-get :file-change where))]
          (th/db-update! :file-change {:changes (blob/encode (rename-shape ctx "Unproved"))} where)
          (t/is (= reload (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id))))
          (th/db-update! :file-change {:changes data :deleted-at old} where))
        (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
          (t/is (= reload (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id)))))))))

(t/deftest a-valid-empty-range-does-not-depend-on-the-age-of-the-file
  (let [{:keys [file-id] :as ctx} (create-file)
        old (ct/minus (ct/now) (ct/duration "48h"))]
    (th/db-update! :file {:created-at old :modified-at old} {:id file-id})
    (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
      (t/is (= {:head 0 :vern 0 :through 0 :rows []} (:result (catch-up ctx 0)))))))

(t/deftest zero-vern-without-proof-cannot-answer-up-to-date
  (let [ctx (create-file :seed-boundary false)]
    (t/is (= {:head 0 :vern 0 :reload true} (:result (catch-up ctx 0))))
    (set-boundary (:file-id ctx) {:kind "genesis" :vern 0 :revn 0})
    (t/is (= {:head 0 :vern 0 :through 0 :rows []} (:result (catch-up ctx 0))))))

(t/deftest genesis-can-start-at-a-nonzero-revision
  (let [ctx (create-file :revn 12)]
    (t/is (= {:head 12 :vern 0 :through 12 :rows []} (:result (catch-up ctx 12))))
    (t/is (= {:head 12 :vern 0 :reload true} (:result (catch-up ctx 11))))))

(t/deftest authenticated-outsiders-cannot-read-the-head
  (let [ctx (create-file)
        stranger (:id (th/create-profile* 2))
        out (catch-up (assoc ctx :profile-id stranger) 0)]
    (t/is (nil? (:result out)))
    (t/is (th/ex-of-type? (:error out) :not-found))))

(t/deftest does-not-replay-updates-that-notify-library-subscribers
  (let [ctx   (create-file :is-shared true)
        _     (add-shape ctx)
        saved (save ctx 1 (rename-shape ctx "Shared rename"))]
    (t/is (= 2 (get-in saved [:result :saved-revn])))
    (t/is (= {:head 2 :vern 0 :reload true}
             (:result (catch-up ctx 1))))))

(t/deftest a-share-link-is-not-a-full-file-permission
  (let [{:keys [file-id profile-id page-id] :as ctx} (create-file)
        stranger (:id (th/create-profile* 2))
        share    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id profile-id
                               :file-id file-id
                               :pages #{page-id}
                               :who-comment "all"
                               :who-inspect "all"})
        share-id (get-in share [:result :id])
        ctx      (assoc ctx :profile-id stranger)]
    (t/is (uuid? share-id))
    (t/is (th/ex-of-type? (:error (catch-up ctx 0)) :not-found))
    (t/is (th/ex-of-code? (:error (catch-up ctx 0 :share-id share-id)) :params-validation))))

(t/deftest deleted-files-do-not-reveal-their-head
  (let [{:keys [file-id profile-id] :as ctx} (create-file)
        deleted (th/command! {::th/type :delete-file
                              ::rpc/profile-id profile-id
                              :id file-id})]
    (t/is (nil? (:error deleted)))
    (let [out (catch-up ctx 0)]
      (t/is (nil? (:result out)))
      (t/is (th/ex-of-type? (:error out) :not-found)))))

(t/deftest a-deleted-project-hides-the-head-before-the-cascade-runs
  (let [{:keys [file-id profile-id project-id] :as ctx} (create-file :custom-project true)
        deleted (th/command! {::th/type :delete-project
                              ::rpc/profile-id profile-id
                              :id project-id})]
    (t/is (nil? (:error deleted)))
    (t/is (some? (th/db-get :file {:id file-id})))
    (let [out (catch-up ctx 0)]
      (t/is (nil? (:result out)))
      (t/is (th/ex-of-type? (:error out) :not-found)))))

(t/deftest rejects-invalid-cursors-and-vern
  (let [ctx (create-file)]
    (t/is (= {:head 0 :vern 0 :reload true} (:result (catch-up ctx 1))))
    (t/is (= {:head 0 :vern 0 :reload true} (:result (catch-up ctx 0 :until 1))))
    (t/is (= {:head 0 :vern 0 :reload true} (:result (catch-up ctx 1 :until 0))))
    (t/is (= {:head 0 :vern 0 :reload true} (:result (catch-up ctx 0 :vern 1))))
    (t/is (th/ex-of-code? (:error (catch-up ctx -1)) :params-validation))))

(t/deftest a-restored-file-needs-a-durable-boundary-for-that-vern
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        reload {:head 2 :vern 11 :reload true}]
    ;; The restore and its persisted proof are separate deliveries. Simulate
    ;; the new epoch and then seed the proof to exercise the reader.
    (th/db-update! :file {:vern 11 :revn 2} {:id file-id})
    (t/is (= reload (:result (catch-up ctx 2 :vern 11))))

    (set-boundary file-id {:kind "restore" :revn 2 :vern 11})
    (t/is (= reload (:result (catch-up ctx 0 :until 1 :vern 11))))
    (t/is (= reload (:result (catch-up ctx 1 :vern 11))))
    (t/is (= reload (:result (catch-up ctx 1 :until 1 :vern 11))))
    (t/is (= {:head 2 :vern 11 :through 2 :rows []}
             (:result (catch-up ctx 2 :vern 11))))

    (save-rename (assoc ctx :vern 11) 2 "New epoch")
    (t/is (= [3] (mapv :revn (get-in (catch-up ctx 2 :vern 11) [:result :rows]))))))

(t/deftest both-reading-and-saving-refuse-to-replay-a-pending-migration
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        _      (save-rename ctx 1 "Before migration")
        reload {:head 2 :vern 0 :reload true}]
    (th/db-delete! :file-migration {:file-id file-id :name (last fmg/available-migrations)})
    (t/is (= reload (:result (catch-up ctx 1))))
    (save-rename ctx 2 "On top of migration")
    (t/is (= {:head 3 :vern 0 :reload true} (:result (catch-up ctx 2))))))

(t/deftest excludes-renames-with-other-data-normalization
  (let [{:keys [file-id] :as ctx} (create-file)
        _     (add-shape ctx)
        where {:file-id file-id :id file-id}
        data  (-> (th/db-get :file-data where) :data blob/decode (assoc :plugin-data nil))]
    (th/db-update! :file-data {:data (blob/encode data)} where)
    (t/is (contains? (:data (get-file ctx)) :plugin-data))
    (save-rename ctx 1 "With normalization")
    (t/is (not (contains? (:data (get-file ctx)) :plugin-data)))
    (t/is (= {:head 2 :vern 0 :reload true} (:result (catch-up ctx 1))))))

(t/deftest excludes-a-rename-that-updates-a-local-component-clock
  (let [{:keys [file-id page-id] :as ctx} (create-file)]
    (thi/reset-idmap!)
    (db/tx-run! th/*system*
                (fn [cfg]
                  (let [file (-> (bfc/get-file cfg file-id :lock-for-update? true :realize? true)
                                 (with-meta {:current-page-id page-id})
                                 (tho/add-simple-component :component :main :child))]
                    (fdata/with-tracked-pointers cfg file-id
                      (bfc/update-file! cfg file)))))
    (let [before (get-in (get-file ctx) [:data :components (thi/id :component) :modified-at])]
      (save-rename (assoc ctx :shape-id (thi/id :child)) 0 "Renamed child in main")
      (t/is (not= before (get-in (get-file ctx) [:data :components (thi/id :component) :modified-at])))
      (t/is (= {:head 1 :vern 0 :reload true} (:result (catch-up ctx 0)))))))

(t/deftest gaps-and-unproved-rows-never-return-partial-deltas
  (let [{:keys [file-id] :as ctx} (create-file)
        _       (add-shape ctx)
        first   (save-rename ctx 1 "First")
        second  (binding [cf/flags (disj cf/flags :file-catch-up)]
                  (save-rename ctx 2 "Second"))
        reload  {:head 3 :vern 0 :reload true}]
    (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
      (t/is (= reload (:result (catch-up ctx 1))))
      (t/is (= reload (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id second))))
      (th/db-delete! :file-change {:file-id file-id :id first})
      (t/is (= reload (:result (catch-up ctx 1)))))))

(t/deftest rejects-duplicates-even-when-only-one-row-is-eligible
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        _      (save-rename ctx 1 "Remote")
        own-id (save-rename ctx 2 "Own")]
    (th/db-insert! :file-change {:file-id file-id :revn 3 :kind 1 :label "Snapshot"})
    (t/is (= {:revn 3 :id own-id}
             (get-in (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id)
                     [:result :confirmed-row])))
    (th/db-insert! :file-change {:file-id file-id :revn 3})
    (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
      (t/is (= {:head 3 :vern 0 :reload true}
               (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id))))
      (t/is (= {:head 3 :vern 0 :reload true} (:result (catch-up ctx 1)))))
    (t/is (= [2] (mapv :revn (get-in (catch-up ctx 1 :until 2) [:result :rows]))))))

(t/deftest row-expiry-and-payload-eligibility-are-checked-independently
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        row-id (save-rename ctx 1 "Renamed")
        where  {:file-id file-id :id row-id}
        reload {:head 2 :vern 0 :reload true}]
    (t/is (= [2] (mapv :revn (get-in (catch-up ctx 1) [:result :rows]))))

    (th/db-update! :file-change {:deleted-at (ct/minus (ct/now) (ct/duration "1h"))} where)
    (t/is (= reload (:result (catch-up ctx 1))))
    (th/db-update! :file-change {:deleted-at nil} where)

    (th/db-update! :file-data {:deleted-at (ct/minus (ct/now) (ct/duration "1h"))} where)
    (t/is (= reload (:result (catch-up ctx 1))))
    (th/db-update! :file-data {:deleted-at nil :type "snapshot"} where)
    (t/is (= reload (:result (catch-up ctx 1))))
    (th/db-delete! :file-data where)
    (t/is (= reload (:result (catch-up ctx 1))))))

(t/deftest proof-does-not-cover-replaced-redo-bytes
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        row-id (save-rename ctx 1 "Proved")
        redo   (blob/encode (rename-shape ctx "Not proved"))]
    (th/db-update! :file-change {:changes redo} {:file-id file-id :id row-id})
    (t/is (= {:head 2 :vern 0 :reload true} (:result (catch-up ctx 1))))))

(t/deftest storage-backed-payload-proves-eligibility-without-loading-undo
  (let [{:keys [file-id] :as ctx} (create-file)
        _      (add-shape ctx)
        row-id (binding [cf/config (assoc cf/config :file-data-backend "storage")]
                 (save-rename ctx 1 "Stored outside the database"))
        row    (th/db-get :file-data {:file-id file-id :id row-id} {::db/remove-deleted false})]
    (t/is (= "storage" (:backend row)))
    (t/is (uuid? (:storage-object-id row)))
    (t/is (nil? (:data row)))
    (with-redefs [fdata/resolve-file-data (fn [& _] (throw (ex-info "Unexpected payload read" {})))]
      (t/is (= [row-id] (mapv :id (get-in (catch-up ctx 1) [:result :rows])))))))

(t/deftest concurrent-gc-does-not-change-the-snapshot-of-the-range
  (let [{:keys [file-id] :as ctx} (create-file)
        _            (add-shape ctx)
        row-id       (save-rename ctx 1 "Renamed")
        where        {:file-id file-id :id row-id}
        create-array db/create-array]
    (with-redefs [db/create-array
                  (fn [conn type values]
                    ;; The reader already checked the candidates and is about
                    ;; to read their redo. Delete the row from another connection.
                    (th/db-delete! :file-change where)
                    (th/db-delete! :file-data where)
                    (create-array conn type values))]
      (t/is (= [row-id] (mapv :id (get-in (catch-up ctx 1) [:result :rows])))))
    (t/is (= {:head 2 :vern 0 :reload true} (:result (catch-up ctx 1))))))

(t/deftest revision-budget-includes-the-confirmed-row
  (let [{:keys [file-id] :as ctx} (create-file)
        _        (add-shape ctx)
        _        (save-rename ctx 1 "Latest")
        original (th/db-get :file-change {:file-id file-id :revn 2} {::db/remove-deleted false})
        payload  (th/db-get :file-data {:file-id file-id :id (:id original)} {::db/remove-deleted false})
        last-id  (uuid/next)]
    (doseq [revn (range 3 131)]
      (let [id (if (= 130 revn) last-id (uuid/next))]
        (th/db-insert! :file-change (assoc original :id id :revn revn))
        (th/db-insert! :file-data (assoc payload :id id))))
    (th/db-update! :file {:revn 130} {:id file-id})
    (t/is (= 128 (count (get-in (catch-up ctx 1 :until 129) [:result :rows]))))
    (t/is (= {:head 130 :vern 0 :reload true}
             (:result (catch-up ctx 1 :until 129 :confirm-revn 130 :confirm-id last-id))))
    (t/is (= {:head 130 :vern 0 :reload true} (:result (catch-up ctx 1))))))

(defn- noisy-name
  [seed]
  (let [data (byte-array (* 2 1024 1024))]
    (.nextBytes (Random. (long seed)) data)
    (.encodeToString (Base64/getEncoder) data)))

(defn- get-compressed-redo-size
  [file-id remote-id own-id]
  (:size (th/db-exec-one! ["SELECT sum(octet_length(changes)) AS size
                             FROM file_change WHERE file_id = ? AND id IN (?, ?)"
                           file-id remote-id own-id])))

(defn- assert-single-rename
  [ctx revn id name]
  (t/testing (str "revision " revn " fits alone")
    (let [{:keys [rows] :as result} (:result (catch-up ctx (dec revn) :until revn))]
      (t/is (= {:head 3 :vern 0 :through revn} (dissoc result :rows)))
      (t/is (= [{:revn revn :id id :session-id (:session-id ctx)}]
               (mapv #(dissoc % :changes) rows)))
      ;; Avoid printing multi-MiB names on failure.
      (t/is (true? (= (rename-shape ctx name) (:changes (first rows))))))))

(t/deftest the-compressed-budget-applies-to-all-rows-before-loading-redo
  (let [{:keys [file-id] :as ctx} (create-file)
        remote-name (noisy-name 1)
        own-name    (noisy-name 2)
        reload      {:head 3 :vern 0 :reload true}
        [remote-id own-id]
        (binding [cf/config (assoc cf/config :default-blob-version 5)]
          (add-shape ctx)
          [(save-rename ctx 1 remote-name) (save-rename ctx 2 own-name)])]
    (t/is (< (* 4 1024 1024) (get-compressed-redo-size file-id remote-id own-id)))
    (assert-single-rename ctx 2 remote-id remote-name)
    (assert-single-rename ctx 3 own-id own-name)
    (with-redefs [db/create-array (fn [& _] (throw (ex-info "Unexpected redo transfer" {})))]
      (t/is (= reload (:result (catch-up ctx 1))))
      (t/testing "the confirmation counts even though its delta is not returned"
        (t/is (= reload (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id))))))))

(t/deftest v5-uncompressed-budget-is-cumulative
  (let [{:keys [file-id] :as ctx} (create-file)
        ;; Repetitive names fit the SQL budget but use more than 16 MiB
        ;; when both LZ4 frames are inflated.
        size        (* 9 1024 1024)
        remote-name (.repeat "a" size)
        own-name    (.repeat "b" size)
        reload      {:head 3 :vern 0 :reload true}
        [remote-id own-id]
        (binding [cf/config (assoc cf/config :default-blob-version 5)]
          (add-shape ctx)
          [(save-rename ctx 1 remote-name) (save-rename ctx 2 own-name)])]
    (t/is (<= (get-compressed-redo-size file-id remote-id own-id) (* 4 1024 1024)))
    (assert-single-rename ctx 2 remote-id remote-name)
    (assert-single-rename ctx 3 own-id own-name)
    (t/is (= reload (:result (catch-up ctx 1))))
    (t/testing "the confirmation counts even though its delta is not returned"
      (t/is (= reload (:result (catch-up ctx 1 :until 2 :confirm-revn 3 :confirm-id own-id)))))))
