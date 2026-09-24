;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-auth-override-test
  (:require
   [app.common.time :as ct]
   [app.http :as-alias http]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; --- RPC: client params cannot override the server auth context
;;
;; Exercises the real wrapped :get-profile method. The handler only
;; reads ::rpc/profile-id, and its params schema ([:map]) accepts
;; anything, so before the fix a session authenticated as A asking
;; for B's profile-id received B's profile (id + email).

(t/deftest get-profile-ignores-client-supplied-profile-id
  (let [attacker      (th/create-profile* 1)
        victim        (th/create-profile* 2)
        [_ method-fn] (get-in th/*system* [:app.rpc/methods :get-profile])
        ;; Simulates what wrap-parse-request leaves in
        ;; (:params request) after a body carrying a qualified key.
        body          {:app.rpc/profile-id (:id victim)}
        params        (with-meta {::rpc/profile-id (:id attacker)
                                  ::rpc/request-at (ct/now)}
                        {::http/request {:params body}})
        result        (method-fn params)]
    (t/is (= (:id attacker) (:id result)))
    (t/is (= (:email attacker) (:email result)))))
