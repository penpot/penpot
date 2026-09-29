;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-mcp-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.profile :as du]
   [app.main.data.workspace.mcp :as mcp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest test-update-mcp-status
  (t/testing "enables MCP in profile props and mcp state"
    (let [state  {:profile {:props {:mcp-enabled false}} :mcp {}}
          result (ptk/update (mcp/update-mcp-status true) state)]
      (t/is (true? (get-in result [:profile :props :mcp-enabled])))
      (t/is (true? (get-in result [:mcp :enabled])))))

  (t/testing "disables MCP in profile props and mcp state"
    (let [state  {:profile {:props {:mcp-enabled true}} :mcp {:enabled true}}
          result (ptk/update (mcp/update-mcp-status false) state)]
      (t/is (false? (get-in result [:profile :props :mcp-enabled])))
      (t/is (false? (get-in result [:mcp :enabled]))))))

(t/deftest test-update-mcp-connection-status
  (t/testing "sets connection status to connected"
    (let [state  {:mcp {:connection-status "disconnected"}}
          result (ptk/update (mcp/update-mcp-connection-status "connected") state)]
      (t/is (= "connected" (get-in result [:mcp :connection-status])))))

  (t/testing "sets connection status to disconnected"
    (let [state  {:mcp {:connection-status "connected"}}
          result (ptk/update (mcp/update-mcp-connection-status "disconnected") state)]
      (t/is (= "disconnected" (get-in result [:mcp :connection-status]))))))

(t/deftest test-mcp-connect-does-not-disconnect-other-tabs
  (t/testing "connecting a tab emits only its local plugin-connect event"
    (let [result (atom [])]
      (when-let [out (ptk/watch (mcp/connect-mcp) {} (rx/empty))]
        (->> out (rx/subs! (fn [e] (swap! result conj e)))))
      (t/is (= 1 (count @result)))
      (t/is (not= :mcp/force-disconnect (:type (first @result))))))

  (t/testing "a connected status emits no cross-tab disconnect"
    (let [result (atom [])]
      ;; watch returns nil when the event emits nothing — that itself proves
      ;; no force-disconnect is broadcast to sibling tabs.
      (when-let [out (ptk/watch (mcp/update-mcp-connection-status "connected") {} nil)]
        (->> out (rx/subs! (fn [e] (swap! result conj e)))))
      (t/is (empty? @result)))))

(t/deftest test-init-sets-enabled
  (t/testing "init sets :mcp :enabled to true when profile has mcp-enabled"
    (let [state  {:mcp {} :profile {:props {:mcp-enabled true}}}
          result (ptk/update (mcp/init) state)]
      (t/is (true? (get-in result [:mcp :enabled])))))

  (t/testing "init sets :mcp :enabled to false when profile has mcp-disabled"
    (let [state  {:mcp {:enabled true} :profile {:props {:mcp-enabled false}}}
          result (ptk/update (mcp/init) state)]
      (t/is (false? (get-in result [:mcp :enabled])))))

  (t/testing "init sets :mcp :enabled to false when profile has no mcp-enabled prop"
    (let [state  {:mcp {:enabled true} :profile {:props {}}}
          result (ptk/update (mcp/init) state)]
      (t/is (false? (get-in result [:mcp :enabled]))))))

(t/deftest test-init-mcp-state
  (let [token-id (uuid/next)]
    (t/testing "with valid MCP token (future expiration)"
      (let [future-date (ct/plus (ct/now) #js {:hours 24})
            tokens      [{:id token-id :type "mcp" :token "abc123" :expires-at future-date}]
            event       (#'mcp/init-mcp-state tokens)
            state       {:mcp {}}
            result      (ptk/update event state)]
        (t/is (= "abc123" (get-in result [:mcp :token])))
        (t/is (= token-id (get-in result [:mcp :token-id])))
        (t/is (true? (get-in result [:mcp :token-valid])))
        ;; deref should return the token when valid
        (t/is (some? @event))))

    (t/testing "with MCP token with no expiration"
      (let [tokens [{:id token-id :type "mcp" :token "abc123" :expires-at nil}]
            event  (#'mcp/init-mcp-state tokens)
            state  {:mcp {}}
            result (ptk/update event state)]
        (t/is (= "abc123" (get-in result [:mcp :token])))
        (t/is (true? (get-in result [:mcp :token-valid])))
        (t/is (some? @event))))

    (t/testing "with expired MCP token"
      (let [past-date (ct/minus (ct/now) #js {:hours 24})
            tokens    [{:id token-id :type "mcp" :token "abc123" :expires-at past-date}]
            event     (#'mcp/init-mcp-state tokens)
            state     {:mcp {}}
            result    (ptk/update event state)]
        (t/is (= "abc123" (get-in result [:mcp :token])))
        (t/is (false? (get-in result [:mcp :token-valid])))
        ;; deref should return nil when token is expired
        (t/is (nil? @event))))

    (t/testing "with no MCP token"
      (let [tokens [{:id token-id :type nil :token "regular-token"}]
            event  (#'mcp/init-mcp-state tokens)
            state  {:mcp {:existing "data"}}
            result (ptk/update event state)]
        ;; state should be unchanged when no MCP token exists
        (t/is (= {:mcp {:existing "data"}} result))
        (t/is (nil? @event))))

    (t/testing "with mixed tokens finds MCP token"
      (let [regular-id (uuid/next)
            mcp-id     (uuid/next)
            tokens     [{:id regular-id :type nil :token "regular"}
                        {:id mcp-id :type "mcp" :token "mcp-token" :expires-at nil}]
            event      (#'mcp/init-mcp-state tokens)
            result     (ptk/update event {:mcp {}})]
        (t/is (= "mcp-token" (get-in result [:mcp :token])))
        (t/is (= mcp-id (get-in result [:mcp :token-id])))))))

(t/deftest test-delete-access-token-optimistic-update
  (let [token-1 {:id (uuid/next) :name "token-1"}
        token-2 {:id (uuid/next) :name "token-2"}
        token-3 {:id (uuid/next) :name "token-3"}]

    (t/testing "removes token from :access-tokens optimistically"
      (let [state  {:access-tokens [token-1 token-2 token-3]}
            event  (du/delete-access-token {:id (:id token-2)})
            result (ptk/update event state)]
        (t/is (= 2 (count (:access-tokens result))))
        (t/is (= [token-1 token-3] (:access-tokens result)))))

    (t/testing "state unchanged when token id not found"
      (let [state  {:access-tokens [token-1 token-2]}
            event  (du/delete-access-token {:id (uuid/next)})
            result (ptk/update event state)]
        (t/is (= 2 (count (:access-tokens result))))))))
