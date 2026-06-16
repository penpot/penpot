;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-access-tokens-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
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
          (t/is (= 2 (count results))))))

    (t/testing "get mcp token"
      (let [_ (th/command! {::th/type :create-access-token
                            ::rpc/profile-id (:id prof)
                            :type "mcp"
                            :name "token 1"
                            :perms ["get-profile"]})
            {:keys [error result]}
            (th/command! {::th/type :get-current-mcp-token
                          ::rpc/profile-id (:id prof)})]
        ;; (th/print-result! result)
        (t/is (nil? error))
        (t/is (string? (:token result)))))

    (t/testing "get-access-tokens returns :token for MCP tokens but not for regular tokens"
      (let [;; Create a regular token
            regular-out (th/command! {::th/type :create-access-token
                                      ::rpc/profile-id (:id prof)
                                      :name "regular token"
                                      :perms ["get-profile"]})
            regular-token (:result regular-out)

            ;; Create an MCP token
            mcp-out (th/command! {::th/type :create-access-token
                                  ::rpc/profile-id (:id prof)
                                  :type "mcp"
                                  :name "mcp token"
                                  :perms []})
            mcp-token (:result mcp-out)

            ;; Fetch all tokens
            {:keys [error result]}
            (th/command! {::th/type :get-access-tokens
                          ::rpc/profile-id (:id prof)})]

        (t/is (nil? error))

        ;; Find our tokens in the result
        (let [regular (some #(when (= (:id %) (:id regular-token)) %) result)
              mcp     (some #(when (= (:id %) (:id mcp-token)) %) result)]

          ;; Regular tokens should NOT have :token
          (t/is (some? regular))
          (t/is (not (contains? regular :token)))

          ;; MCP tokens SHOULD have :token
          (t/is (some? mcp))
          (t/is (contains? mcp :token))
          (t/is (string? (:token mcp))))))

    (t/testing "creating MCP token removes previous MCP tokens"
      (let [;; Create first MCP token
            first-out (th/command! {::th/type :create-access-token
                                    ::rpc/profile-id (:id prof)
                                    :type "mcp"
                                    :name "first mcp"
                                    :perms []})
            first-mcp (:result first-out)

            ;; Create second MCP token
            second-out (th/command! {::th/type :create-access-token
                                     ::rpc/profile-id (:id prof)
                                     :type "mcp"
                                     :name "second mcp"
                                     :perms []})
            second-mcp (:result second-out)

            ;; Create third MCP token
            third-out (th/command! {::th/type :create-access-token
                                    ::rpc/profile-id (:id prof)
                                    :type "mcp"
                                    :name "third mcp"
                                    :perms []})
            third-mcp (:result third-out)

            ;; Fetch all tokens
            {:keys [error result]}
            (th/command! {::th/type :get-access-tokens
                          ::rpc/profile-id (:id prof)})]

        (t/is (nil? error))

        ;; Count MCP tokens - should only be 1 (the third one)
        (let [mcp-tokens (filter #(= (:type %) "mcp") result)]
          (t/is (= 1 (count mcp-tokens)))
          (t/is (= (:id third-mcp) (:id (first mcp-tokens)))))

        ;; Verify the first and second MCP tokens are gone
        (let [all-ids (set (map :id result))]
          (t/is (not (contains? all-ids (:id first-mcp))))
          (t/is (not (contains? all-ids (:id second-mcp))))
          (t/is (contains? all-ids (:id third-mcp))))))))
