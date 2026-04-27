;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.workspace-mcp-test
  (:require
   [app.main.data.workspace.mcp :as mcp]
   [clojure.test :as t]
   [potok.v2.core :as ptk]))

(t/deftest record-mcp-activity-appends-and-bounds
  (let [state {}
        e1    (mcp/record-mcp-activity {:phase "received"
                                        :id "a"
                                        :task "execute_code"
                                        :code "console.log(1)"})
        s1    (ptk/update e1 state)
        e2    (mcp/record-mcp-activity {:phase "completed"
                                        :id "a"
                                        :task "execute_code"})
        s2    (ptk/update e2 s1)]
    (t/is (= 2 (count (get-in s2 [:mcp :activity]))))
    ;; Newest entries are first
    (t/is (= :completed (get-in s2 [:mcp :activity 0 :phase])))
    (t/is (= :received (get-in s2 [:mcp :activity 1 :phase])))
    (t/is (string? (get-in s2 [:mcp :activity 1 :code-preview])))))

(t/deftest record-mcp-activity-respects-max-length
  (let [final-state
        (reduce (fn [s i]
                  (ptk/update (mcp/record-mcp-activity {:phase "received"
                                                        :id (str "id-" i)
                                                        :task "execute_code"})
                              s))
                {}
                (range 110))]
    (t/is (= 100 (count (get-in final-state [:mcp :activity]))))))

(t/deftest clear-mcp-activity-removes-log
  (let [state {:mcp {:activity [{:id 1}] :connection-status "connected"}}
        e     (mcp/clear-mcp-activity)
        out   (ptk/update e state)]
    (t/is (nil? (get-in out [:mcp :activity])))
    (t/is (= "connected" (get-in out [:mcp :connection-status])))))
