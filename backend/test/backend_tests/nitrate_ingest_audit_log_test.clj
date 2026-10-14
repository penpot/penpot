;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.nitrate-ingest-audit-log-test
  (:require
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.http.client :as-alias http]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)

(t/deftest ingest-audit-log-posts-events-batch
  (binding [cf/flags  (conj cf/flags :admin-console)
            cf/config (assoc cf/config :admin-console-uri "http://ac.example/admin-console/")]
    (with-mocks [http-mock {:target 'app.http.client/req
                            :return {:status 204}}]
      (let [client  (ig/init-key :app.nitrate/client
                                 {::http/client       (Object.)
                                  ::setup/shared-keys {:admin-console "test-shared-key"}})
            cfg     {:app.nitrate/client client}
            p1      (uuid/next)
            p2      (uuid/next)
            e1      (uuid/next)
            e2      (uuid/next)
            team-id (uuid/next)
            now     (ct/now)]
        (nitrate/call cfg :ingest-audit-log
                      {::rpc/profile-id p1
                       :events [{:id e1
                                 :name "create-project"
                                 :type "action"
                                 :profile-id p1
                                 :props {:team-id team-id}
                                 :context {}
                                 :created-at now
                                 :tracked-at now
                                 :source "backend"
                                 :ip-addr "127.0.0.1"}
                                {:id e2
                                 :name "create-file"
                                 :type "action"
                                 :profile-id p2
                                 :props {}
                                 :context {}
                                 :created-at now
                                 :tracked-at now
                                 :source "backend"
                                 :ip-addr "10.0.0.1"}]})
        (t/is (= 1 (:call-count @http-mock)))
        (let [[_ req] (:call-args @http-mock)
              body    (json/decode (:body req) :key-fn json/read-kebab-key)
              e1'     (first (:events body))
              e2'     (second (:events body))]
          (t/is (= :post (:method req)))
          (t/is (str/ends-with? (str (:uri req)) "api/audit-log"))
          (t/is (= 2 (count (:events body))))
          (t/is (= "create-project" (:name e1')))
          (t/is (= "create-file" (:name e2')))
          (t/is (= (str e1) (str (:id e1'))))
          (t/is (= (str e2) (str (:id e2'))))
          (t/is (= (str p1) (str (:profile-id e1'))))
          (t/is (= (str p2) (str (:profile-id e2'))))
          (t/is (= "127.0.0.1" (:ip-addr e1')))
          (t/is (= "10.0.0.1" (:ip-addr e2')))
          (t/is (string? (:created-at e1')))
          (t/is (string? (:tracked-at e1')))
          (t/is (= (str now) (:created-at e1')))
          (t/is (= (str team-id) (str (get-in e1' [:props :team-id])))))))))
