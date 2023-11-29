;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.http-middleware-access-token-test
  (:require
   [app.db :as db]
   [app.http.access-token]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.access-token]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest soft-auth-middleware
  (db/with-atomic [conn (::db/pool th/*system*)]
    (let [profile (th/create-profile* 1)
          system  (-> th/*system*
                      (assoc ::db/conn conn)
                      (assoc ::main/props (:app.setup/props th/*system*)))

          token   (app.rpc.commands.access-token/create-access-token
                   system (:id profile) "test" nil)

          request (volatile! nil)
          handler (#'app.http.access-token/wrap-soft-auth
                   (fn [req] (vreset! request req))
                   system)]

      (with-mocks [m1 {:target 'app.http.access-token/get-token
                       :return nil}]
        (handler {})
        (t/is (= {} @request)))

      (with-mocks [m1 {:target 'app.http.access-token/get-token
                       :return (:token token)}]
        (handler {})

        (let [token-id (get @request :app.http.access-token/id)]
          (t/is (= token-id (:id token))))))))

(t/deftest authz-middleware
  (let [profile (th/create-profile* 1)
        system  (assoc th/*system* ::main/props (:app.setup/props th/*system*))

        token   (db/with-atomic [conn (::db/pool th/*system*)]
                  (let [system (assoc system ::db/conn conn)]
                    (app.rpc.commands.access-token/create-access-token
                     system (:id profile) "test" nil)))

        request (volatile! {})
        handler (#'app.http.access-token/wrap-authz
                 (fn [req] (vreset! request req))
                 system)]

    (handler nil)
    (t/is (nil? @request))

    (handler {:app.http.access-token/id (:id token)})
    (t/is (= #{} (:app.http.access-token/perms @request)))
    (t/is (= (:id profile) (:app.http.access-token/profile-id @request)))))

