;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.import-terminal-rpc-test
  (:require
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private real-audit-submit audit/submit)

(defn- create-import-job
  [profile]
  (let [id (th/create-job* :import-binfile {:profile-id (:id profile)})]
    (th/db-update! :job {:status "new"
                         :params (db/json {:project-id (:default-project-id profile)
                                           :name "imported"
                                           :version 3
                                           :generated-by "test"})}
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
