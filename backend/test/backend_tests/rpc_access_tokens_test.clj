;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-access-tokens-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.storage :as sto]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest access-tokens-crud
  (let [prof    (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id prof)
        proj-id (:default-project-id prof)
        atoken  (atom nil)]

    (t/testing "create access token"
      (let [params {::th/type :create-access-token
                    ::rpc/profile-id (:id prof)
                    :name "token 1"
                    :perms ["get-profile"]}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (reset! atoken result)
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (contains? result :token))
          (t/is (contains? result :perms)))))

    (t/testing "get access token"
      (let [params {::th/type :get-access-tokens
                    ::rpc/profile-id (:id prof)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [[result :as results] (:result out)]
          (t/is (= 1 (count results)))
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (contains? result :token))
          (t/is (contains? result :perms))
          (t/is (= @atoken result)))))

    (t/testing "delete access token"
      (let [params {::th/type :delete-access-token
                    ::rpc/profile-id (:id prof)
                    :id (:id @atoken)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out)))))

    (t/testing "get access token after delete"
      (let [params {::th/type :get-access-tokens
                    ::rpc/profile-id (:id prof)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [results (:result out)]
          (t/is (= 0 (count results))))))
    ))
