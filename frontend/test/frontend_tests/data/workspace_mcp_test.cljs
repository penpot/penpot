;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-mcp-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.plugins :as dp]
   [app.main.data.profile :as du]
   [app.main.data.workspace.mcp :as mcp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as a]
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
    (let [state  {:mcp {:connection-requested true :connection-status "disconnected"}}
          result (ptk/update (mcp/update-mcp-connection-status "connected") state)]
      (t/is (= "connected" (get-in result [:mcp :connection-status])))))

  (t/testing "sets connection status to disconnected"
    (let [state  {:mcp {:connection-requested true :connection-status "connected"}}
          result (ptk/update (mcp/update-mcp-connection-status "disconnected") state)]
      (t/is (= "disconnected" (get-in result [:mcp :connection-status]))))))

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

(t/deftest ^:async test-enable-does-not-connect
  (let [events (atom [])]
    (await (a/observe (ptk/watch (mcp/update-mcp-status true) {} (rx/empty))
                      :on-next #(swap! events conj %)))
    (t/is (empty? @events))))

(t/deftest ^:async test-connect-is-local
  (let [event  (mcp/connect-mcp)
        state  (ptk/update event {:mcp {:enabled true :token-valid true}})
        events (atom [])]
    (t/is (true? (get-in state [:mcp :connection-requested])))
    (await (a/observe (ptk/watch event state (rx/empty))
                      :on-next #(swap! events conj (ptk/type %))))
    (t/is (= [:app.main.data.workspace.mcp/connect] @events))))

(t/deftest test-disconnect-clears-connection-intent
  (let [state  {:mcp {:connection-requested true
                      :connection-status "connecting"
                      :session-id "pq3gxqddgj"}}
        result (ptk/update (mcp/user-disconnect-mcp) state)]
    (t/is (false? (get-in result [:mcp :connection-requested])))
    (t/is (= "disconnected" (get-in result [:mcp :connection-status])))
    (t/is (nil? (get-in result [:mcp :session-id])))))

(t/deftest test-init-clears-previous-file-connection
  (let [state  {:profile {:props {:mcp-enabled true}}
                :mcp {:connection-requested true
                      :connection-status "connected"
                      :session-id "pq3gxqddgj"}}
        result (ptk/update (mcp/init) state)]
    (t/is (false? (get-in result [:mcp :connection-requested])))
    (t/is (= "disconnected" (get-in result [:mcp :connection-status])))
    (t/is (nil? (get-in result [:mcp :session-id])))))

(t/deftest test-late-status-cannot-reconnect
  (let [state {:mcp {:connection-requested false :connection-status "disconnected"}}]
    (t/is (= state (ptk/update (mcp/update-mcp-connection-status "connected") state)))))

(t/deftest test-plugin-callbacks-stop-with-workspace
  (doseq [stop-event [:app.main.data.workspace/finalize-workspace
                      :app.main.data.workspace.mcp/init]]
    (let [stream    (rx/subject)
          extension (atom nil)
          calls     (atom 0)
          closed    (atom 0)]
      (with-redefs [dp/start-plugin! (fn [_ extensions]
                                       (reset! extension (.-mcp extensions)))
                    dp/close-plugin! (fn [_] (swap! closed inc))]
        (ptk/effect (#'mcp/init-mcp-plugin {:token "test-token"}) nil stream)
        (.on @extension "connect" #(swap! calls inc))
        (rx/push! stream (ptk/data-event :app.main.data.workspace.mcp/connect))
        (t/is (= 1 @calls))
        (rx/push! stream (ptk/data-event stop-event))
        (t/is (= 1 @closed))
        (t/is (false? (.isConnectionRequested @extension)))
        (.on @extension "connect" #(swap! calls inc))
        (rx/push! stream (ptk/data-event :app.main.data.workspace.mcp/connect))
        (t/is (= 1 @calls))
        (rx/end! stream)))))
