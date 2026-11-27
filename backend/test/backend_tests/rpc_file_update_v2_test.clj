;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-update-v2-test
  "The `update-file-v2` request contract: one commit list where every commit
  carries only its forward changes, and the identity of the command
  it replaces (audit event name and limits)."
  (:require
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-changes :as fchanges]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files-update :as files-update]
   [app.rpc.rlimit :as-alias rlimit]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(defn- with-validation
  [next]
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (next)))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset with-validation)

(defn- create-file
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]
    {:profile-id (:id profile)
     :file-id    (:id file)
     :page-id    (-> file :data :pages first)}))

(defn- params
  [{:keys [profile-id file-id]} type values]
  (merge {::th/type type
          ::rpc/profile-id profile-id
          :id file-id
          :session-id (uuid/next)
          :revn 0
          :vern 0
          :features cfeat/supported-features}
         values))

(defn- save
  ([ctx values] (save ctx :update-file-v2 values))
  ([ctx type values]
   (th/command! (params ctx type values))))

(defn- commit
  [changes]
  {:commit-id (uuid/next) :changes changes})

(defn- break-parent
  "A change that leaves the shape pointing to a parent that does not exist."
  [page-id id]
  {:type :mod-obj
   :page-id page-id
   :id id
   :operations [{:type :set :attr :parent-id :val (uuid/next)}]})

(defn- get-file-changes
  [{:keys [file-id]}]
  (->> (th/db-query :file-change {:file-id file-id} {:order-by [:revn]})
       (filter :changes)
       (mapv #(update % :changes blob/decode))))

(defn- mdata-of
  [type]
  (first (get-in th/*system* [:app.rpc/methods type])))

(t/deftest rejects-the-shape-of-the-old-contract
  (let [ctx  (create-file)
        redo [{:type :add-page :id (uuid/next) :name "page"}]]
    (doseq [[key value] {:changes redo
                         :changes-with-metadata [{:changes redo}]
                         :undo-changes []
                         :origin :local}]
      (t/testing (str "rejects " key)
        (let [out (save ctx (assoc {:commits [(commit redo)]} key value))]
          (t/is (th/ex-of-type? (:error out) :validation))
          (t/is (th/ex-of-code? (:error out) :params-validation)))))

    (t/testing "nothing was written"
      (t/is (empty? (get-file-changes ctx)))
      (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)})))))))

(t/deftest rejects-legacy-mixed-and-invalid-commit-groups
  (let [ctx     (create-file)
        changes [{:type :add-page :id (uuid/next) :name "page"}]
        group   (commit changes)]
    (doseq [[label invalid-group] [["legacy redo and undo"
                                    {:commit-id (:commit-id group) :redo-changes changes :undo-changes []}]
                                   ["extra redo" (assoc group :redo-changes changes)]
                                   ["extra undo" (assoc group :undo-changes [])]
                                   ["unknown key" (assoc group :origin :local)]
                                   ["missing commit ID" (dissoc group :commit-id)]
                                   ["invalid commit ID" (assoc group :commit-id 1)]
                                   ["missing changes" (dissoc group :changes)]
                                   ["changes must be a vector" (assoc group :changes (first changes))]
                                   ["invalid change" (assoc group :changes [{:type :mod-obj}])]]]
      (t/testing label
        (let [out (save ctx {:commits [invalid-group]})]
          (t/is (th/ex-of-type? (:error out) :validation))
          (t/is (th/ex-of-code? (:error out) :params-validation)))))

    (t/testing "nothing was written"
      (t/is (empty? (get-file-changes ctx)))
      (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)})))))))

(t/deftest rejects-a-save-without-commits
  (let [ctx (create-file)
        out (save ctx {:commits []})]
    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (th/ex-of-code? (:error out) :params-validation))))

(t/deftest confirms-an-ordinary-save-without-returning-deltas
  (let [{:keys [page-id] :as ctx} (create-file)
        redo   [(th/add-rect-change page-id (uuid/next))]
        group  (commit redo)
        exec   db/exec!
        out    (with-redefs [db/exec! (fn [conn [sql :as query] & opts]
                                        (when (= sql @#'files-update/sql:save-predecessors)
                                          (throw (ex-info "Unexpected predecessor query" {})))
                                        (apply exec conn query opts))
                             files-update/get-lagged-changes
                             (fn [& _] (throw (ex-info "Unexpected lagged changes" {})))]
                 (save ctx {:commits [group]}))
        row    (first (get-file-changes ctx))]
    (t/is (nil? (:error out)))
    (t/is (= {:revn 0 :vern 0
              :written [{:revn 1 :kind :edit :id (:id row)}]
              :recovery {:mode :none}
              :saved-revn 1
              :edit-matches-request true}
             (:result out)))
    (t/is (uuid? (:id row)))
    (t/is (not= (:id row) (:commit-id group)))
    (t/is (= redo (:changes row)))))

(t/deftest reports-when-the-written-edit-differs-from-the-request
  (let [{:keys [page-id] :as ctx} (create-file)
        group   (commit [{:type :mod-page :id page-id :name "Requested"}])
        written [{:type :mod-page :id page-id :name "Normalized by the server"}]
        process @#'files-update/process-changes-and-validate
        out     (with-redefs [files-update/process-changes-and-validate
                              (fn [cfg file _ commits skip-validate]
                                (process cfg file written commits skip-validate))]
                  (save ctx {:commits [group]}))]
    (t/is (nil? (:error out)))
    (t/is (false? (get-in out [:result :edit-matches-request])))
    (t/is (= {:mode :none} (get-in out [:result :recovery])))
    (t/is (= written (:changes (first (get-file-changes ctx)))))))

(t/deftest v2-proves-recent-predecessors-without-reading-their-redo
  (let [{:keys [file-id page-id] :as ctx} (create-file)
        group   (fn [name] (commit [{:type :mod-page :id page-id :name name}]))
        first   (:result (save ctx {:commits [(group "First")]}))
        row     (-> first :written peek (select-keys [:id :revn]))
        recent  {:since 0 :until 1 :required [row]}]
    (th/db-insert! :file-change {:file-id file-id :revn 1 :kind fchanges/snapshot-kind :label "Snapshot"})
    (th/db-update! :file-change {:changes (byte-array 10)} {:file-id file-id :id (:id row)})
    (with-redefs [files-update/get-lagged-changes
                  (fn [& _] (throw (ex-info "Unexpected lagged changes" {})))]
      (let [out (binding [cf/flags (disj cf/flags :file-catch-up)]
                  (save ctx {:commits [(group "Second")]}))]
        (t/is (nil? (:error out)))
        (t/is (= (assoc recent :mode :wait) (get-in out [:result :recovery])))
        (t/is (not (contains? (:result out) :lagged)))
        (let [second-row (-> out :result :written peek (select-keys [:id :revn]))
              out        (binding [cf/flags (conj cf/flags :file-catch-up)]
                           (save ctx {:revn 1 :repair-in-response true :commits [(group "Third")]}))]
          (t/is (nil? (:error out)))
          (t/is (= {:mode :catch-up :since 1 :until 2 :required [second-row]}
                   (get-in out [:result :recovery])))
          (t/is (= 3 (get-in out [:result :saved-revn])))
          (t/is (not (contains? (:result out) :lagged))))))))

(t/deftest identifies-all-predecessors-even-when-one-is-too-old-for-catch-up
  (let [{:keys [file-id page-id] :as ctx} (create-file)
        group  (fn [name] (commit [{:type :mod-page :id page-id :name name}]))
        now    (ct/inst (inst-ms (ct/now)))
        cutoff (ct/minus now (ct/duration "24h"))]
    (binding [cf/flags (conj cf/flags :file-catch-up :file-change-retention)
              ct/*clock* (ct/fixed-clock now)]
      (let [a (-> (save ctx {:commits [(group "First")]}) :result :written peek)
            b (-> (save ctx {:revn 1 :commits [(group "Second")]}) :result :written peek)]
        (th/db-update! :file-change {:created-at cutoff} {:file-id file-id :id (:id a)})
        (let [out (save ctx {:commits [(group "Third")]})
              c   (-> out :result :written peek)]
          (t/is (= {:mode :catch-up :since 0 :until 2
                    :required (mapv #(select-keys % [:id :revn]) [a b])}
                   (get-in out [:result :recovery])))
          ;; The earliest timestamp isn't on the first logical revision.
          (th/db-update! :file-change {:created-at now} {:file-id file-id :id (:id a)})
          (th/db-update! :file-change {:created-at (ct/minus cutoff 1)} {:file-id file-id :id (:id b)})
          (let [out (save ctx {:commits [(group "Fourth")]})]
            (t/is (= {:mode :reload :since 0 :until 3
                      :required (mapv #(select-keys % [:id :revn]) [a b c])}
                     (get-in out [:result :recovery])))
            (t/is (= 4 (get-in out [:result :saved-revn])))))))))

(t/deftest does-not-return-part-of-an-ambiguous-or-incomplete-proof
  (let [{:keys [file-id page-id] :as ctx} (create-file)
        group     (fn [name] (commit [{:type :mod-page :id page-id :name name}]))
        first     (-> (save ctx {:commits [(group "First")]}) :result :written peek)
        duplicate (:id (th/db-insert! :file-change {:file-id file-id :revn 1}))]
    (t/testing "an unlabeled legacy duplicate invalidates the range"
      (let [out (save ctx {:commits [(group "Second")]})]
        (t/is (= {:mode :reload} (get-in out [:result :recovery])))
        (t/is (= 2 (get-in out [:result :saved-revn])))))
    (th/db-delete! :file-change {:file-id file-id :id duplicate})
    (th/db-delete! :file-change {:file-id file-id :id (:id first)})
    (t/testing "a later row cannot substitute for a missing predecessor"
      (let [out (save ctx {:commits [(group "Third")]})]
        (t/is (= {:mode :reload} (get-in out [:result :recovery])))
        (t/is (= 3 (get-in out [:result :saved-revn])))))))

(t/deftest oversized-backlog-does-not-read-history-in-v2-but-v1-still-returns-it
  (let [{:keys [file-id page-id] :as ctx} (create-file)
        rename-page (fn [name] [{:type :mod-page :id page-id :name name}])
        old-first   (rename-page "Historical first revision")
        old-second  (rename-page "Historical second revision")
        old         (rename-page "Historical")
        v2-redo     (rename-page "Saved by v2")
        v1-redo     (rename-page "Saved by v1")
        total       600
        now         (ct/now)
        exec        db/exec!
        ;; Revision 2 predates revision 1; the rest follow revision order.
        history     (th/db-exec!
                     ["INSERT INTO file_change (file_id, session_id, revn, created_at, kind, retained, changes)
                       SELECT ?, ?, n, now() - interval '48 hours'
                                      + (CASE n WHEN 1 THEN 2 WHEN 2 THEN 1 ELSE n END) * interval '1 millisecond',
                              ?, true, CASE n WHEN 1 THEN ?::bytea WHEN 2 THEN ?::bytea ELSE ?::bytea END
                         FROM generate_series(1, ?) AS n
                        RETURNING id, revn"
                      file-id (uuid/next) fchanges/revision-kind
                      (blob/encode old-first) (blob/encode old-second) (blob/encode old) total])
        history-ids (into {} (map (juxt :revn :id)) history)]
    (th/db-update! :file {:revn total} {:id file-id})
    (binding [cf/flags (-> cf/flags (disj :file-catch-up) (conj :file-change-retention))]
      (let [v2-out (binding [ct/*clock* (ct/fixed-clock now)]
                     (with-redefs [db/exec! (fn [conn [sql :as query] & opts]
                                              (when (= sql @#'files-update/sql:save-predecessors)
                                                (throw (ex-info "Unexpected predecessor query" {})))
                                              (apply exec conn query opts))
                                   files-update/get-lagged-changes
                                   (fn [& _] (throw (ex-info "Unexpected lagged changes" {})))]
                       (save ctx {:revn 0 :commits [(commit v2-redo)]})))
            v2     (:result v2-out)]
        (t/is (nil? (:error v2-out)))
        (t/is (= {:mode :reload} (:recovery v2)))
        (t/is (= (inc total) (:saved-revn v2)))
        (t/is (not (contains? v2 :lagged)))

        (let [out  (binding [ct/*clock* (ct/fixed-clock (ct/plus now 1))]
                     (save ctx :update-file {:revn 0 :changes v1-redo}))
              rows (get-in out [:result :lagged])]
          (t/is (nil? (:error out)))
          (t/is (= #{:revn :lagged} (set (keys (:result out)))))
          (t/is (= (concat [2 1] (range 3 (+ total 3))) (map :revn rows)))
          (t/is (= [[(history-ids 2) old-second] [(history-ids 1) old-first]]
                   (mapv (juxt :id :changes) (take 2 rows))))
          (t/is (= (repeat (- total 2) old) (map :changes (take (- total 2) (drop 2 rows)))))
          (t/is (= [v2-redo v1-redo] (mapv :changes (drop total rows))))
          (t/is (= [{:id (get-in rows [total :id]) :revn (inc total) :kind :edit}]
                   (:written v2))))))))

(t/deftest with-128-predecessors-the-post-proves-the-range-but-does-not-offer-catch-up
  (let [{:keys [file-id page-id] :as ctx} (create-file)]
    (th/db-exec! ["INSERT INTO file_change (file_id, session_id, revn, kind)
                   SELECT ?, ?, n, ? FROM generate_series(1, 128) AS n"
                  file-id (uuid/next) fchanges/revision-kind])
    (th/db-update! :file {:revn 128} {:id file-id})
    (let [out (binding [cf/flags (conj cf/flags :file-catch-up)]
                (save ctx {:commits [(commit [{:type :mod-page :id page-id :name "Saved"}])]}))
          recovery (get-in out [:result :recovery])]
      (t/is (= 129 (get-in out [:result :saved-revn])))
      (t/is (= {:mode :reload :since 0 :until 128} (dissoc recovery :required)))
      (t/is (= (range 1 129) (map :revn (:required recovery))))
      (t/is (every? uuid? (map :id (:required recovery)))))))

(t/deftest saves-the-commits-before-the-first-invalid-one
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a  (uuid/next)
        shape-c  (uuid/next)
        commit-1 (commit [(th/add-rect-change page-id shape-a)])
        commit-2 (commit [(break-parent page-id shape-a)])
        commit-3 (commit [(th/add-rect-change page-id shape-c)])
        out      (save ctx {:commits [commit-1 commit-2 commit-3]})]

    (t/is (nil? (:error out)))

    (t/testing "the response names the rejected commits and the failure"
      (let [rejected (-> out :result :rejected)]
        (t/is (= [(:commit-id commit-2) (:commit-id commit-3)] (:commit-ids rejected)))
        (t/is (= (:commit-id commit-2) (:failed-commit-id rejected)))
        (t/is (= :validation (-> rejected :error :type)))
        (t/is (= :referential-integrity (-> rejected :error :code)))))

    (t/testing "the change log holds the accepted commit only"
      (let [rows (get-file-changes ctx)]
        (t/is (= 1 (count rows)))
        (t/is (= {:revn 0 :vern 0 :saved-revn 1 :recovery {:mode :none}
                  :written [{:id (:id (first rows)) :revn 1 :kind :edit}]}
                 (dissoc (:result out) :rejected)))
        (t/is (= 1 (:revn (first rows))))
        (t/is (= (:changes commit-1) (:changes (first rows))))))))

(t/deftest an-accepted-prefix-without-redo-writes-no-row
  (let [{:keys [file-id page-id] :as ctx} (create-file)
        shape-id (uuid/next)
        first-id (-> (save ctx {:commits [(commit [(th/add-rect-change page-id shape-id)])]})
                     :result :written peek :id)
        good     (commit [])
        bad      (commit [(break-parent page-id shape-id)])
        later    (commit [{:type :add-page :id (uuid/next) :name "Not saved"}])
        out      (binding [cf/flags (conj cf/flags :file-catch-up)]
                   (save ctx {:commits [good bad later]}))
        result   (:result out)]
    (t/is (nil? (:error out)))
    (t/is (= {:revn 1 :vern 0 :written []
              :recovery {:mode :wait :since 0 :until 1 :required [{:revn 1 :id first-id}]}}
             (dissoc result :rejected)))
    (t/is (= [(:commit-id bad) (:commit-id later)] (get-in result [:rejected :commit-ids])))
    (t/is (= (:commit-id bad) (get-in result [:rejected :failed-commit-id])))
    (t/is (= 1 (:revn (th/db-get :file {:id file-id}))))
    (t/is (= [first-id] (mapv :id (get-file-changes ctx))))))

(t/deftest keeps-the-audit-event-name-of-the-command-it-replaces
  (let [{:keys [page-id] :as ctx} (create-file)
        redo   [(th/add-rect-change page-id (uuid/next))]
        result (th/command-raw!
                (params ctx :update-file-v2
                        {:commits [(commit redo)]}))]

    (t/testing "the audit log, the webhooks and the telemetry see one event"
      (t/is (= "update-file" (::audit/name (meta result)))))

    (t/testing "the event keeps the props the command replaces"
      (t/is (some? (::audit/replace-props (meta result)))))))

(t/deftest declares-the-same-limits-as-the-old-command
  (t/testing "the concurrency limiter is shared, so both commands queue together"
    (t/is (= (::climit/id (mdata-of :update-file))
             (::climit/id (mdata-of :update-file-v2)))))

  (t/testing "the rate limiter keeps one bucket and one counter per profile"
    (t/is (= "update-file" (::rlimit/method (mdata-of :update-file-v2))))))
