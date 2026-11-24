;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.import-terminal-rpc-test
  (:require
   [app.common.transit :as transit]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.storage :as js]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.management :as management]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.java.io :as jio]
   [clojure.test :as t]
   [datoteka.io :as io]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial th/database-reset th/clean-storage))

(def ^:private real-audit-submit audit/submit)

(defn- create-import-job
  [profile & {:keys [team-id]}]
  (let [id (th/create-job* :import-binfile {:profile-id (:id profile)})]
    (th/db-update! :job {:status "new"
                         :params (db/json (cond-> {:project-id (:default-project-id profile)
                                                   :name "imported"
                                                   :version 3
                                                   :generated-by "test"}
                                            team-id (assoc :team-id team-id)))}
                   {:id id})
    id))

(defn- get-import-events
  []
  (->> (th/db-exec! ["SELECT * FROM audit_log WHERE name = 'import-binfile' AND source = 'backend'"])
       (mapv #(update % :props db/decode-transit-pgobject))))

(t/deftest cancelling-a-queued-import-through-rpc-persists-one-terminal-event
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true]
    (let [profile (th/create-profile* 1)
          job-id  (create-import-job profile)
          cancel  #(th/command! {::th/type :cancel-job
                                 ::rpc/profile-id (:id profile)
                                 :id job-id})]
      (t/is (th/success? (cancel)))
      (t/is (th/success? (cancel)))
      (t/is (= "cancelled" (:status (jobs/get-job th/*system* job-id))))
      (let [events (get-import-events)
            event  (first events)
            props  (:props event)]
        (t/is (= 1 (count events)))
        (t/is (= (:id profile) (:profile-id event)))
        (t/is (= job-id (:job-id props)))
        (t/is (= (:default-team-id profile) (:team-id props)))
        (t/is (= "cancelled" (:status props)))
        (t/is (= 3 (:version props)))
        (t/is (= "test" (:generated-by props)))
        (t/is (not (contains? props :file-ids)))
        (t/is (not (contains? props :file-count)))))))

(t/deftest management-terminal-rpcs-run-the-registered-import-hook
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true]
    (let [profile      (th/create-profile* 1)
          completed-id (create-import-job profile)
          failed-id    (create-import-job profile)
          result       {:file-ids [] :name "imported" :version 3}
          error        {:type :internal :code :processing-error :hint "failed"}]
      (doseq [id [completed-id failed-id]]
        (t/is (pos? (jobs/claim th/*system* id (:scheduled-at (jobs/get-job th/*system* id))))))
      (let [completed (th/management-command! {::th/type :complete-job
                                               :job-id completed-id
                                               :result result})
            failed    (th/management-command! {::th/type :fail-job
                                               :job-id failed-id
                                               :error error})
            events    (get-import-events)
            by-job    (into {} (map (juxt #(get-in % [:props :job-id]) identity)) events)]
        (t/is (nil? (:error completed)))
        (t/is (nil? (:error failed)))
        (t/is (= "completed" (:status (jobs/get-job th/*system* completed-id))))
        (t/is (= "failed" (:status (jobs/get-job th/*system* failed-id))))
        (t/is (= 2 (count events)))
        (t/is (= "completed" (get-in by-job [completed-id :props :status])))
        (t/is (= 0 (get-in by-job [completed-id :props :file-count])))
        (t/is (= "failed" (get-in by-job [failed-id :props :status])))
        (t/is (not (contains? (get-in by-job [failed-id :props]) :file-ids)))))))

(t/deftest cancelling-a-moved-import-routes-to-its-frozen-team
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/webhooks-enabled? true]
    (let [profile     (th/create-profile* 1)
          original-id (:default-team-id profile)
          destination (th/create-team* 1 {:profile-id (:id profile)})
          original    (th/create-webhook* {:team-id original-id})
          _           (th/create-webhook* {:team-id (:id destination)})
          job-id      (create-import-job profile :team-id original-id)]
      (th/db-update! :project {:team-id (:id destination)}
                     {:id (:default-project-id profile)})
      (t/is (th/success? (th/command! {::th/type :cancel-job
                                       ::rpc/profile-id (:id profile)
                                       :id job-id})))
      (let [events (get-import-events)
            event  (first events)
            rows   (th/db-exec! ["SELECT * FROM job WHERE name = 'process-webhook-event'"])
            blobs  (map #(db/decode-json-pgobject (:params %)) rows)
            blob   (first (filter #(= "import-binfile"
                                      (:name (transit/decode-str (:event-blob %))))
                                  blobs))]
        (t/is (= 1 (count events)))
        (t/is (= original-id (get-in event [:props :team-id])))
        (t/is (some? blob))
        (when blob
          (webhooks/process-event-impl th/*system* blob)
          (let [deliveries (th/db-exec! ["SELECT * FROM job WHERE name = 'run-webhook'"])
                params     (map #(db/decode-json-pgobject (:params %)) deliveries)
                delivery   (first params)
                payload    (some-> delivery :event transit/decode-str)]
            (t/is (= 1 (count deliveries)))
            (t/is (= (str (:id original)) (get-in delivery [:config :id])))
            (t/is (= original-id (get-in payload [:props :team-id])))
            (t/is (= job-id (get-in payload [:props :job-id])))))))))

(t/deftest failed-import-after-move-routes-to-its-frozen-team
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/webhooks-enabled? true]
    (let [profile     (th/create-profile* 1)
          original-id (:default-team-id profile)
          project     (th/create-project* 1 {:profile-id (:id profile)
                                             :team-id original-id})
          project-id  (:id project)
          destination (th/create-team* 1 {:profile-id (:id profile)})
          original    (th/create-webhook* {:team-id original-id})
          _           (th/create-webhook* {:team-id (:id destination)})
          path        (-> "backend_tests/test_files/svg-attrs-camel-case.penpot"
                          io/resource jio/file)
          staged      (js/put-resource th/*system* (:id profile)
                                       {:content (sto/content path)
                                        :filename "package.penpot"
                                        :mtype "application/zip"})
          job-id      (th/create-job* :import-binfile {:profile-id (:id profile)
                                                       :resource-id (:resource-id staged)})
          params      {:project-id project-id :team-id original-id
                       :name "imported" :version 3}]
      (th/db-update! :job {:params (db/json params)} {:id job-id})
      (db/tx-run! th/*system*
                  #(management/move-project % {:profile-id (:id profile)
                                               :project-id project-id
                                               :team-id (:id destination)}))
      (let [error (try
                    (jobs/invoke (assoc th/*system*
                                        ::jobs/name :import-binfile
                                        ::jobs/params params
                                        ::jobs/context (jobs/make-context (jobs/get-job th/*system* job-id))))
                    (catch Throwable cause cause))]
        (t/is (= :import-team-changed (-> error ex-data :code)))
        (t/is (= 1 (jobs/fail th/*system* job-id
                              {:type :validation :code :import-team-changed
                               :hint "the destination project changed teams"}))))
      (t/is (= "failed" (:status (jobs/get-job th/*system* job-id))))
      (t/is (empty? (th/db-exec! ["SELECT id FROM file WHERE project_id = ? AND deleted_at IS NULL"
                                  project-id])))
      (let [events (get-import-events)
            blobs  (map #(db/decode-json-pgobject (:params %))
                        (th/db-exec! ["SELECT * FROM job WHERE name = 'process-webhook-event'"]))
            blob   (first (filter #(= "import-binfile"
                                      (:name (transit/decode-str (:event-blob %))))
                                  blobs))]
        (t/is (= 1 (count events)))
        (t/is (= "failed" (get-in (first events) [:props :status])))
        (t/is (= original-id (get-in (first events) [:props :team-id])))
        (t/is (some? blob))
        (when blob
          (webhooks/process-event-impl th/*system* blob)
          (let [deliveries (th/db-exec! ["SELECT * FROM job WHERE name = 'run-webhook'"])
                delivery   (some-> deliveries first :params db/decode-json-pgobject)
                payload    (some-> delivery :event transit/decode-str)]
            (t/is (= 1 (count deliveries)))
            (t/is (= (str (:id original)) (get-in delivery [:config :id])))
            (t/is (= original-id (get-in payload [:props :team-id])))
            (t/is (= job-id (get-in payload [:props :job-id])))))))))

(t/deftest cancelling-a-legacy-moved-import-uses-the-current-team
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true]
    (let [profile     (th/create-profile* 1)
          destination (th/create-team* 1 {:profile-id (:id profile)})
          job-id      (create-import-job profile)]
      (th/db-update! :project {:team-id (:id destination)}
                     {:id (:default-project-id profile)})
      (t/is (th/success? (th/command! {::th/type :cancel-job
                                       ::rpc/profile-id (:id profile)
                                       :id job-id})))
      (let [events (get-import-events)]
        (t/is (= 1 (count events)))
        (t/is (= (:id destination) (get-in (first events) [:props :team-id])))))))

(t/deftest cancelling-a-moved-import-does-not-fall-back-to-destination-webhooks
  (with-redefs [audit/submit real-audit-submit
                cf/audit-persist? true
                cf/webhooks-enabled? true]
    (let [profile     (th/create-profile* 1)
          destination (th/create-team* 1 {:profile-id (:id profile)})
          _           (th/create-webhook* {:team-id (:id destination)})
          job-id      (create-import-job profile :team-id (:default-team-id profile))]
      (th/db-update! :project {:team-id (:id destination)}
                     {:id (:default-project-id profile)})
      (t/is (th/success? (th/command! {::th/type :cancel-job
                                       ::rpc/profile-id (:id profile)
                                       :id job-id})))
      (let [rows  (th/db-exec! ["SELECT * FROM job WHERE name = 'process-webhook-event'"])
            blobs (map #(db/decode-json-pgobject (:params %)) rows)
            blob  (first (filter #(= "import-binfile"
                                     (:name (transit/decode-str (:event-blob %))))
                                 blobs))]
        (t/is (some? blob))
        (when blob
          (webhooks/process-event-impl th/*system* blob)
          (t/is (empty? (th/db-exec! ["SELECT id FROM job WHERE name = 'run-webhook'"]))))))))
