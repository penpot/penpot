;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.system-events-test
  (:require
   [app.main.data.event :as ev]
   [app.main.store :as st]
   [app.plugins.register :as r]
   [app.plugins.system-events :as se]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- set-state
  [file-id]
  (set! st/state
        (atom {:current-file-id file-id
               :profile {:props {:plugins {:data {plugin-id {:name "Plugin"
                                                             :url "https://plugin.test"}}}}}})))

(t/deftest mcp-event-carries-file
  (let [file-id (random-uuid)]
    (set-state file-id)
    (let [data (deref (se/event r/mcp-plugin-id "create-version" :file-id file-id))]
      (t/is (= "mcp" (::ev/origin data)))
      (t/is (= {:file-id file-id} (::ev/context data)))
      (t/is (= file-id (:file-id data))))))

(t/deftest plugin-event-carries-file-and-plugin
  (let [file-id (random-uuid)]
    (set-state file-id)
    (t/is (= {:file-id file-id
              :plugin-name "Plugin"
              :plugin-url "https://plugin.test"}
             (::ev/context (deref (se/event plugin-id "add-flow")))))))

(t/deftest mcp-add-event-carries-file
  (let [file-id (random-uuid)]
    (set-state file-id)
    (t/is (= {::ev/origin "mcp"
              ::ev/context {:file-id file-id}}
             (meta (se/add-event (ptk/reify ::test) r/mcp-plugin-id))))))

(t/deftest event-has-no-file-on-the-dashboard
  (set-state nil)
  (t/is (nil? (-> (deref (se/event r/mcp-plugin-id "create-project"))
                  ::ev/context
                  :file-id))))
