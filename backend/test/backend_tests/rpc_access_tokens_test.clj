;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-access-tokens-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.http.access-token :as actoken]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.access-token :as access-token]
   [app.storage :as sto]
   [app.tokens :as tokens]
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
                    :name "token 1"}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (reset! atoken-no-expiration result)
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (contains? result :token))
          (t/is (not (contains? result :perms))))))

    (t/testing "create access token ignores client-supplied perms"
      (let [params {::th/type :create-access-token
                    ::rpc/profile-id (:id prof)
                    :name "token ignored-perms"
                    :perms ["error-reports:read"]}
            out    (th/command! params)]
        (t/is (nil? (:error out)))
        (let [result (:result out)
              row    (th/db-get :access-token {:id (:id result)})]
          (t/is (not (contains? result :perms)))
          (t/is (= [] (db/decode-pgarray (:perms row) []))))))

    (t/testing "create access token with expiration date in the future"
      (let [params {::th/type :create-access-token
                    ::rpc/profile-id (:id prof)
                    :name "token 1"
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
          (t/is (= 4 (count results)))
          (t/is (contains? result :id))
          (t/is (contains? result :created-at))
          (t/is (contains? result :updated-at))
          (t/is (not (contains? result :token)))
          (t/is (not (contains? result :perms))))))

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
          (t/is (= 3 (count results))))))

    (t/testing "get mcp token"
      (let [_ (th/command! {::th/type :create-access-token
                            ::rpc/profile-id (:id prof)
                            :type "mcp"
                            :name "token 1"})
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
                                      :name "regular token"})
            regular-token (:result regular-out)

            ;; Create an MCP token
            mcp-out (th/command! {::th/type :create-access-token
                                  ::rpc/profile-id (:id prof)
                                  :type "mcp"
                                  :name "mcp token"})
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
                                    :name "first mcp"})
            first-mcp (:result first-out)

            ;; Create second MCP token
            second-out (th/command! {::th/type :create-access-token
                                     ::rpc/profile-id (:id prof)
                                     :type "mcp"
                                     :name "second mcp"})
            second-mcp (:result second-out)

            ;; Create third MCP token
            third-out (th/command! {::th/type :create-access-token
                                    ::rpc/profile-id (:id prof)
                                    :type "mcp"
                                    :name "third mcp"})
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

(t/deftest access-token-decoder-accepts-mcp-and-personal-issuers
  (let [prof (th/create-profile* 1 {:is-active true})
        cfg  th/*system*]

    (t/testing "MCP tokens use different issuer claim"
      (let [{:keys [result]} (th/command! {::th/type :create-access-token
                                           ::rpc/profile-id (:id prof)
                                           :type "mcp"
                                           :name "mcp token"})
            mcp-token (:token result)

            decoded (actoken/decode-token cfg mcp-token)]
        (t/is (= "urn:penpot:mcp-token" (:iss decoded)))
        (t/is (= (:id prof) (:uid decoded)))))

    (t/testing "Regular access tokens use access-token issuer claim"
      (let [{:keys [result]} (th/command! {::th/type :create-access-token
                                           ::rpc/profile-id (:id prof)
                                           :name "regular token"})
            access-token (:token result)

            ;; Should decode successfully
            decoded (actoken/decode-token cfg access-token)]
        (t/is (some? decoded))
        (t/is (= (:id prof) (:uid decoded)))))))

(t/deftest oauth-mcp-token-provisioning
  (let [prof (th/create-profile* 1 {:is-active true})
        cfg th/*system*
        provision #(db/tx-run! cfg access-token/ensure-mcp-access! (:id prof))]
    (t/testing "approval enables MCP and creates a fixed token"
      (let [token (provision)
            props (-> (th/db-get :profile {:id (:id prof)})
                      :props db/decode-transit-pgobject)]
        (t/is (string? (:token token)))
        (t/is (nil? (:expires-at token)))
        (t/is (true? (:mcp-enabled props)))
        (t/is (= token (provision)))))
    (t/testing "approval preserves a token generated in settings"
      (let [{:keys [result error]}
            (th/command! {::th/type :create-access-token
                          ::rpc/profile-id (:id prof)
                          :type "mcp"
                          :name "From settings"})]
        (t/is (nil? error))
        (t/is (= (:token result) (:token (provision))))))
    (t/testing "approval replaces an expired token"
      (db/exec! th/*pool* [(str "UPDATE access_token SET expires_at = now() - interval '1 day'"
                                " WHERE profile_id = ?")
                           (:id prof)])
      (let [expired (first (db/query th/*pool* :access-token {:profile-id (:id prof) :type "mcp"}))
            token   (provision)]
        (t/is (not= (:token expired) (:token token)))
        (t/is (nil? (:expires-at token)))
        (t/is (= [(:id token)]
                 (map :id (db/query th/*pool* :access-token {:profile-id (:id prof) :type "mcp"}))))))
    (t/testing "approval replaces a token issued with the personal-token issuer"
      (db/delete! th/*pool* :access-token {:profile-id (:id prof)})
      (let [token-id (uuid/next)
            now      (ct/now)
            old      (tokens/generate cfg {:iss "access-token" :uid (:id prof)
                                           :iat now :tid token-id})]
        (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                          (db/insert! conn :access-token
                                      {:id token-id :name "MCP" :token old :type "mcp"
                                       :profile-id (:id prof) :created-at now :updated-at now
                                       :perms (db/create-array conn "text" [])})))
        (let [token (provision)]
          (t/is (not= old (:token token)))
          (t/is (= actoken/mcp-token-issuer
                   (:iss (actoken/decode-token cfg (:token token)))))
          (t/is (= [(:id token)]
                   (map :id (db/query th/*pool* :access-token
                                      {:profile-id (:id prof) :type "mcp"})))))))
    (t/testing "approval replaces a token this instance cannot decrypt"
      (db/delete! th/*pool* :access-token {:profile-id (:id prof)})
      (let [now (ct/now)]
        (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                          (db/insert! conn :access-token
                                      {:id (uuid/next) :name "MCP" :token "not-a-jwe" :type "mcp"
                                       :profile-id (:id prof) :created-at now :updated-at now
                                       :perms (db/create-array conn "text" [])})))
        (let [token (provision)]
          (t/is (not= "not-a-jwe" (:token token)))
          (t/is (= [(:id token)]
                   (map :id (db/query th/*pool* :access-token
                                      {:profile-id (:id prof) :type "mcp"})))))))
    (t/testing "concurrent approvals share one token"
      (db/delete! th/*pool* :access-token {:profile-id (:id prof)})
      (let [results (mapv deref (repeatedly 4 #(future (provision))))]
        (t/is (= 1 (count (set (map :token results)))))
        (t/is (= 1 (count (db/query th/*pool* :access-token
                                    {:profile-id (:id prof) :type "mcp"}))))))))
