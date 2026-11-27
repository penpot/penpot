;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-change-refs-consumer-test
  (:require
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-changes :as fchanges]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumers :as consumers]
   [app.loggers.audit.consumers.file-change :as consumer]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; The system fixture stubs audit submission for ordinary RPC tests.
(def ^:private submit-audit audit/submit)

(defn- insert-event
  ([name source initiator props]
   (insert-event name source initiator props (ct/now)))
  ([name source initiator props created-at]
   (th/db-exec-one!
    ["INSERT INTO audit_log (id, name, type, source, profile_id, ip_addr, props, context, created_at)
      VALUES (?, ?, 'action', ?, ?, '127.0.0.1', ?, ?, ?)"
     (uuid/next) name source uuid/zero (db/tjson props)
     (db/tjson {:initiator initiator}) created-at])))

(defn- pending
  [name]
  (mapv (comp db/decode-json-pgobject :params)
        (th/db-exec! ["SELECT params FROM job WHERE name = ? ORDER BY created_at" name])))

(t/deftest all-rows-count-but-only-relevant-events-are-decoded
  (let [file-id  (uuid/next)
        decode   db/decode-transit-pgobject
        ignored  (db/tjson {})
        save     {:name "update-file"
                  :source "backend"
                  :props (db/tjson {:id file-id :outcome "saved"})
                  :context (db/tjson {:initiator "app"})}
        snapshot (assoc save :name "create-file-snapshot"
                        :props (db/tjson {:file-id file-id}))]
    (binding [cf/flags (disj cf/flags :feature-fdata-pointer-map)]
      (with-redefs [db/decode-transit-pgobject
                    (fn [value]
                      (when (identical? ignored value)
                        (throw (ex-info "unrelated row was decoded" {})))
                      (decode value))]
        (t/is (= {:processed 6}
                 ((:process consumer/consumer) th/*system*
                                               [save
                                                (assoc save :name "get-file" :props ignored)
                                                (assoc save :props (db/tjson {:id (str (uuid/next)) :outcome "saved"}))
                                                (assoc save :name "create-file-snapshot" :props (db/tjson {}))
                                                snapshot
                                                save])))))
    (t/is (= [{:file-id (str file-id)}] (pending "index-file-change-refs-scheduler")))))

(t/deftest repeated-mixed-events-share-pending-jobs-for-each-affected-file
  (let [saved-id    (uuid/next)
        snapshot-id (uuid/next)
        event       {:source "backend" :context {:initiator "app"}}
        save        (assoc event :name "update-file" :props {:id saved-id :outcome "saved"})
        snapshot    (assoc event :name "create-file-snapshot" :props {:file-id snapshot-id})
        rows        [save save snapshot (assoc-in snapshot [:props :file-id] saved-id)]
        process     (:process consumer/consumer)]
    (binding [cf/flags #{:feature-fdata-pointer-map}]
      (process th/*system* rows)
      (process th/*system* rows))
    (t/is (= 2 (count (pending "index-file-change-refs-scheduler"))))
    (t/is (= 2 (count (pending "file-fragments-gc"))))))

(t/deftest events-for-the-same-file-share-one-pending-index-scheduler
  (let [file-id (uuid/next)]
    (binding [cf/flags (disj cf/flags :feature-fdata-pointer-map)]
      (insert-event "update-file" "backend" "app"
                    {:id file-id :outcome "partial"})
      (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= [{:file-id (str file-id)}] (pending "index-file-change-refs-scheduler")))
      (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= [{:file-id (str file-id)}] (pending "index-file-change-refs-scheduler")))
      (t/is (empty? (pending "file-fragments-gc")))
      (t/is (pos? (:seq (th/db-exec-one!
                         ["SELECT seq FROM audit_log_consumer_cursor WHERE consumer = 5"])))))))

(t/deftest an-event-during-an-index-scan-can-enqueue-a-successor
  (let [file-id (uuid/next)]
    (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
    (consumers/run-pass th/*system* consumer/consumer)
    (let [{:keys [id scheduled-at]} (th/db-get :job {:name "index-file-change-refs-scheduler"})]
      (th/db-update! :job {:status "scheduled"} {:id id})
      (t/is (= 1 (jobs/claim th/*system* id scheduled-at)))
      ;; The running job may already have checked the last page.
      (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= #{"running" "new"}
               (into #{} (map :status) (th/db-query :job {:name "index-file-change-refs-scheduler"})))))))

(t/deftest existing-rpc-events-index-history-without-external-destinations
  (th/with-audit-config #{:auto-file-snapshot} [audit/submit submit-audit]
    (let [profile    (th/create-profile* 1)
          profile-id (:id profile)
          file       (th/create-file* 1 {:profile-id profile-id
                                         :project-id (:default-project-id profile)})
          file-id    (:id file)
          page-id    (first (get-in file [:data :pages]))
          older-id   (uuid/next)
          save       (th/command! {::th/type :update-file
                                   ::rpc/profile-id profile-id
                                   :id file-id
                                   :session-id (uuid/next)
                                   :vern 0
                                   :revn 0
                                   :features cfeat/supported-features
                                   :changes [(th/add-rect-change page-id (uuid/next))]})
          snapshot   (th/command! {::th/type :create-file-snapshot
                                   ::rpc/profile-id profile-id
                                   :file-id file-id
                                   :label "manual"})]
      (t/is (th/success? save))
      (t/is (th/success? snapshot))
      (t/is (= [file-id]
               (mapv (comp :id db/decode-transit-pgobject :props)
                     (th/db-query :audit-log {:name "update-file"}))))
      (t/is (= [file-id]
               (mapv (comp :file-id db/decode-transit-pgobject :props)
                     (th/db-query :audit-log {:name "create-file-snapshot"}))))
      (th/db-insert! :file-change {:file-id file-id :id older-id :revn 0
                                   :kind fchanges/revision-kind})
      (let [changes (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})]
        (t/is (= 4 (count changes)))
        (t/is (empty? (th/db-query :audit-log {:name "file-change-created"})))
        (consumers/run-pass th/*system* consumer/consumer)
        (let [scans (pending "index-file-change-refs-scheduler")]
          (t/is (= [{:file-id (str file-id)}] scans))
          (t/is (empty? (pending "index-file-change-refs")))
          (th/run-task! :index-file-change-refs-scheduler (first scans)))
        (doseq [params (pending "index-file-change-refs")]
          (th/run-task! :index-file-change-refs params))
        (t/is (= (into #{} (map :id) changes)
                 (into #{} (map :file-change-id) (th/db-query :file-change-refs {:file-id file-id}))))
        (t/is (empty? (pending "file-fragments-gc")))))))

(t/deftest effective-backend-saves-and-snapshots-activate-fragment-marking
  (let [file-id     (uuid/next)
        snapshot    (uuid/next)
        rejected    (uuid/next)
        untrusted   (uuid/next)
        change-props (fn [file-id outcome]
                       {:id file-id :outcome outcome})]
    (binding [cf/flags #{:feature-fdata-pointer-map :tiered-file-data-storage}]
      (jobs/submit th/*system* {::jobs/name :offload-file-data
                                ::jobs/params {:file-id file-id}})
      (insert-event "update-file" "backend" "app" (change-props file-id "saved"))
      (insert-event "update-file" "backend" "app" (change-props file-id "partial"))
      (insert-event "update-file" "backend" "app" (change-props rejected "rejected"))
      (insert-event "update-file" "frontend" "app" (change-props untrusted "saved"))
      (insert-event "update-file" "telemetry:backend" "app" (change-props untrusted "saved"))
      (insert-event "update-file" "backend" "admin-console" (change-props untrusted "saved"))
      (insert-event "create-file-snapshot" "backend" "admin-console" {:file-id untrusted})
      (insert-event "create-file-snapshot" "backend" "app" {:file-id snapshot})
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= #{(str file-id) (str snapshot)}
               (into #{} (map :file-id) (pending "file-fragments-gc"))))
      (t/is (= #{(str file-id) (str snapshot)}
               (into #{} (map :file-id) (pending "index-file-change-refs-scheduler")))))))

(t/deftest saves-refresh-pending-gc-from-the-enqueue-time
  (let [now     (ct/inst "2026-11-24T12:00:00Z")
        later   (ct/plus now "2h")
        file-id (uuid/next)
        where   {:name "file-fragments-gc" :label (str file-id)}
        delay   (ct/duration "36h")]
    (binding [cf/flags #{:feature-fdata-pointer-map}
              cf/config (assoc cf/config :file-clean-delay delay)]
      (binding [ct/*clock* (ct/fixed-clock now)]
        (insert-event "update-file" "backend" "app"
                      {:id file-id :outcome "saved"} (ct/minus now "4h"))
        (consumers/run-pass th/*system* consumer/consumer))
      (let [job (th/db-get :job where)]
        (t/is (= (ct/plus now delay) (:scheduled-at job)))
        (binding [ct/*clock* (ct/fixed-clock later)]
          (insert-event "update-file" "backend" "app" {:id file-id :outcome "partial"})
          (insert-event "update-file" "backend" "app" {:id file-id :outcome "saved"})
          (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
          (consumers/run-pass th/*system* consumer/consumer))
        (let [refreshed (th/db-get :job where)]
          (t/is (= (:id job) (:id refreshed)))
          (t/is (= (ct/plus later delay) (:scheduled-at refreshed)))
          (binding [ct/*clock* (ct/fixed-clock (ct/plus later "1h"))]
            (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
            (consumers/run-pass th/*system* consumer/consumer))
          (t/is (= [(:scheduled-at refreshed)]
                   (mapv :scheduled-at (th/db-query :job where)))))))))

(t/deftest a-delayed-save-event-still-queues-gc-with-the-full-delay
  (let [now     (ct/inst "2026-11-24T12:00:00Z")
        file-id (uuid/next)]
    (binding [ct/*clock* (ct/fixed-clock now)
              cf/flags #{:feature-fdata-pointer-map}
              cf/config (assoc cf/config :file-clean-delay (ct/duration "48h"))]
      (insert-event "update-file" "backend" "app"
                    {:id file-id :outcome "saved"} (ct/minus now "72h"))
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= (ct/plus now "48h")
               (:scheduled-at (th/db-get :job {:name "file-fragments-gc"
                                               :label (str file-id)})))))))

(t/deftest snapshots-queue-gc-without-refreshing-a-pending-job
  (let [now     (ct/inst "2026-11-24T12:00:00Z")
        file-id (uuid/next)
        where   {:name "file-fragments-gc" :label (str file-id)}]
    (binding [cf/flags #{:feature-fdata-pointer-map}]
      (binding [ct/*clock* (ct/fixed-clock now)]
        (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
        (consumers/run-pass th/*system* consumer/consumer))
      (let [job (th/db-get :job where)]
        (t/is (= (ct/plus now "10m") (:scheduled-at job)))
        (binding [ct/*clock* (ct/fixed-clock (ct/plus now "5m"))]
          (insert-event "create-file-snapshot" "backend" "app" {:file-id file-id})
          (consumers/run-pass th/*system* consumer/consumer))
        (t/is (= [(select-keys job [:id :scheduled-at])]
                 (mapv #(select-keys % [:id :scheduled-at]) (th/db-query :job where))))))))

(t/deftest audit-cursor-and-index-submission-roll-back-together
  (let [file-id (uuid/next)
        submit  jobs/submit]
    (consumers/ensure-cursor th/*system* consumers/file-change-refs)
    (insert-event "update-file" "backend" "app"
                  {:id file-id :outcome "saved"})
    (with-redefs [jobs/submit (fn [cfg options]
                                (submit cfg options)
                                (throw (ex-info "abort consumer chunk" {})))]
      (t/is (thrown-with-msg? Exception #"abort consumer chunk"
                              (consumers/run-pass th/*system* consumer/consumer))))
    (t/is (empty? (pending "index-file-change-refs-scheduler")))
    (t/is (= 0 (:seq (th/db-exec-one!
                      ["SELECT seq FROM audit_log_consumer_cursor WHERE consumer = 5"]))))
    (consumers/run-pass th/*system* consumer/consumer)
    (t/is (= [{:file-id (str file-id)}]
             (pending "index-file-change-refs-scheduler")))))
