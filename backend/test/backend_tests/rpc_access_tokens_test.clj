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
  (let [prof                     (th/create-profile* 1 {:is-active true})
        team-id                  (:default-team-id prof)
        proj-id                  (:default-project-id prof)
        atoken-no-expiration     (atom nil)
        atoken-future-expiration (atom nil)
        atoken-past-expiration   (atom nil)]

    (t/testing "create access token without expiration date"
      (let [params {::th/type :create-access-token
                    ::rpc/profile-id (:id prof)
                    :name "token 1"
                    :perms ["get-profile"]}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (reset! atoken-no-expiration result)
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (contains? result :token)))))

    (t/testing "create access token with expiration date in the future"
      (let [params {::th/type :create-access-token
                    ::rpc/profile-id (:id prof)
                    :name "token 1"
                    :perms ["get-profile"]
                    :expiration "130h"}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (reset! atoken-past-expiration result)
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (contains? result :expires-at))
          (t/is (contains? result :token)))))

    (t/testing "create access token with expiration date in the past"
      (let [params {::th/type :create-access-token
                    ::rpc/profile-id (:id prof)
                    :name "token 1"
                    :perms ["get-profile"]
                    :expiration "-130h"}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (reset! atoken-future-expiration result)
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (contains? result :expires-at))
          (t/is (contains? result :token)))))

    (t/testing "get access tokens"
      (let [params {::th/type :get-access-tokens
                    ::rpc/profile-id (:id prof)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [[result :as results] (:result out)]
          (t/is (= 3 (count results)))
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (not (contains? result :token))))))

    (t/testing "delete access token"
      (let [params {::th/type :delete-access-token
                    ::rpc/profile-id (:id prof)
                    :id (:id @atoken-no-expiration)}
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
          (t/is (= 2 (count results))))))))
