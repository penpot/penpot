;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-audit-test
  (:require
   [app.common.pprint :as pp]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.time :as dt]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn decode-row
  [{:keys [props context] :as row}]
  (cond-> row
    (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props))
    (db/pgobject? context) (assoc :context (db/decode-transit-pgobject context))))

(def http-request
  (reify
    yetti.request/Request
    (get-header [_ name]
      (case name
        "x-forwarded-for" "127.0.0.44"))))

(t/deftest push-events-1
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id proj-id
                                     :team-id team-id
                                     :route "dashboard-files"}
                             :context {:engine "blink"}
                             :profile-id (:id prof)
                             :timestamp (dt/now)
                             :type "action"}]}
          params  (with-meta params
                    {:app.http/request http-request})

          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows)))
        (t/is (= (:id prof) (:profile-id row)))
        (t/is (= "navigate" (:name row)))
        (t/is (= "frontend" (:source row)))))))

(t/deftest push-events-2
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id proj-id
                                     :team-id team-id
                                     :route "dashboard-files"}
                             :context {:engine "blink"}
                             :profile-id uuid/zero
                             :timestamp (dt/now)
                             :type "action"}]}
          params  (with-meta params
                    {:app.http/request http-request})
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows)))
        (t/is (= (:id prof) (:profile-id row)))
        (t/is (= "navigate" (:name row)))
        (t/is (= "frontend" (:source row)))))))


