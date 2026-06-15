;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-mcp-test
  (:require
   [app.main.data.workspace.mcp :as mcp]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest test-set-mcp-active
  (t/testing "sets :active to true"
    (let [state  {:mcp {:active false}}
          result (ptk/update (mcp/set-mcp-active true) state)]
      (t/is (true? (get-in result [:mcp :active])))))

  (t/testing "sets :active to false"
    (let [state  {:mcp {:active true}}
          result (ptk/update (mcp/set-mcp-active false) state)]
      (t/is (false? (get-in result [:mcp :active]))))))

(t/deftest test-update-mcp-status
  (t/testing "enables MCP in profile props"
    (let [state  {:profile {:props {:mcp-enabled false}}}
          result (ptk/update (mcp/update-mcp-status true) state)]
      (t/is (true? (get-in result [:profile :props :mcp-enabled])))))

  (t/testing "disables MCP in profile props"
    (let [state  {:profile {:props {:mcp-enabled true}}}
          result (ptk/update (mcp/update-mcp-status false) state)]
      (t/is (false? (get-in result [:profile :props :mcp-enabled]))))))

(t/deftest test-update-mcp-connection-status
  (t/testing "sets connection status to connected"
    (let [state  {:mcp {:connection-status "disconnected"}}
          result (ptk/update (mcp/update-mcp-connection-status "connected") state)]
      (t/is (= "connected" (get-in result [:mcp :connection-status])))))

  (t/testing "sets connection status to disconnected"
    (let [state  {:mcp {:connection-status "connected"}}
          result (ptk/update (mcp/update-mcp-connection-status "disconnected") state)]
      (t/is (= "disconnected" (get-in result [:mcp :connection-status]))))))

(t/deftest test-init-sets-active
  (t/testing "init sets :mcp :active to true"
    (let [state  {:mcp {:active false}}
          result (ptk/update (mcp/init) state)]
      (t/is (true? (get-in result [:mcp :active]))))))
