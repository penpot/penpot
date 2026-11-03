;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.mcp-oauth-state-test
  (:require
   [app.config :as cf]
   [app.mcp.oauth-state :as state]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)

(t/deftest authorization-state
  (let [cfg    th/*system*
        handle (state/create! cfg :code {:client-id "test"} 60)
        record (state/read! cfg :code handle)]
    (t/is (= {:client-id "test"} (:value record)))
    (t/is (nil? (state/read! cfg :request handle)))
    (t/is (false? (state/consume! cfg :code handle (assoc record :raw "wrong"))))
    (t/is (= record (state/read! cfg :code handle)))
    (let [results (mapv deref (repeatedly 8 #(future (state/consume! cfg :code handle record))))]
      (t/is (= 1 (count (filter true? results)))))
    (t/is (nil? (state/read! cfg :code handle)))))

(t/deftest bind-pending-request-once
  (let [cfg    th/*system*
        handle (state/create! cfg :request {:client-id "test"} 60)
        record (state/read! cfg :request handle)]
    (t/is (true? (state/replace! cfg :request handle record {:session-id "first"})))
    (t/is (false? (state/replace! cfg :request handle record {:session-id "second"})))
    (t/is (= {:session-id "first"} (:value (state/read! cfg :request handle))))))

(t/deftest pkce-and-secrets
  ;; RFC 7636 appendix B
  (t/is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
           (state/digest "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))))

(t/deftest expiry-and-tenant-isolation
  (let [cfg    th/*system*
        handle (state/create! cfg :request {:test true} 1)]
    (binding [cf/config (assoc cf/config :tenant "another-tenant")]
      (t/is (nil? (state/read! cfg :request handle))))
    (Thread/sleep 1100)
    (t/is (nil? (state/read! cfg :request handle)))))

(t/deftest state-storage-fails-closed
  (with-mocks [store {:target 'app.redis/set :return nil}]
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (state/create! th/*system* :request {:test true} 60)))))
