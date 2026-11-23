;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.event-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.repo :as rp]
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- with-event-callbacks
  "Install a fresh penpotEventCallbacks Map for the body, then restore."
  [setup-fn body-fn]
  (let [prev-map (obj/get global "penpotEventCallbacks")
        m        (js/Map.)]
    (setup-fn m)
    (obj/set! global "penpotEventCallbacks" m)
    (try
      (body-fn)
      (finally
        (if (some? prev-map)
          (obj/set! global "penpotEventCallbacks" prev-map)
          (obj/unset! global "penpotEventCallbacks"))))))

(t/deftest run-event-callbacks-invokes-matching-name
  (let [calls (atom [])]
    (with-event-callbacks
      (fn [m]
        (.set m "disable-webgl-rendering"
              #js [(fn [e] (swap! calls conj e))]))
      (fn []
        (#'ev/run-event-callbacks {:name "disable-webgl-rendering"
                                   :type "action"
                                   :props {:origin "settings"}})
        (t/is (= 1 (count @calls)))
        (let [payload (first @calls)]
          (t/is (= "disable-webgl-rendering" (unchecked-get payload "name")))
          (t/is (= "action" (unchecked-get payload "type")))
          (t/is (= "settings" (unchecked-get (unchecked-get payload "props") "origin"))))))))

(t/deftest run-event-callbacks-ignores-unknown-name
  (let [calls (atom [])]
    (with-event-callbacks
      (fn [m]
        (.set m "disable-webgl-rendering"
              #js [(fn [e] (swap! calls conj e))]))
      (fn []
        (#'ev/run-event-callbacks {:name "enable-webgl-rendering"
                                   :type "action"
                                   :props {}})
        (t/is (empty? @calls))))))

(t/deftest run-event-callbacks-swallows-callback-errors
  (let [calls (atom [])]
    (with-event-callbacks
      (fn [m]
        (.set m "disable-webgl-rendering"
              #js [(fn [_] (throw (js/Error. "host boom")))
                   (fn [e] (swap! calls conj e))]))
      (fn []
        (#'ev/run-event-callbacks {:name "disable-webgl-rendering"
                                   :type "action"
                                   :props {}})
        (t/is (= 1 (count @calls)))
        (t/is (= "disable-webgl-rendering"
                 (unchecked-get (first @calls) "name")))))))

(t/deftest add-event-callback-registers-on-global-map
  (with-event-callbacks
    (fn [_m])
    (fn []
      (let [calls (atom [])]
        (cf/add-event-callback "navigate" (fn [e] (swap! calls conj e)))
        (#'ev/run-event-callbacks {:name "navigate" :type "action" :props {}})
        (t/is (= 1 (count @calls)))
        (t/is (= "navigate" (unchecked-get (first @calls) "name")))))))

(t/deftest events-enabled?-truth-table
  (t/testing "enabled when audit-log is present"
    (t/is (true? (ev/events-enabled? #{:audit-log}))))
  (t/testing "enabled when telemetry is present"
    (t/is (true? (ev/events-enabled? #{:telemetry}))))
  (t/testing "enabled when both are present"
    (t/is (true? (ev/events-enabled? #{:audit-log :telemetry}))))
  (t/testing "disabled when no event flag is present"
    (t/is (false? (ev/events-enabled? #{})))
    (t/is (false? (ev/events-enabled? #{:graph})))))

(t/deftest ^:async fetch-environment-data-uses-get-environment-data
  (let [data (await
              (mock/with-mocks*
                {rp/cmd! (mock/stub
                          (fn [cmd]
                            (t/is (= :get-environment-data cmd))
                            (rx/of {:deployment "saas"
                                    :flags #{:audit-log}})))}
                (await (async/->promise (ev/fetch-environment-data)))))]
    (t/is (= {:deployment "saas" :flags #{:audit-log}} data))))

(t/deftest ^:async fetch-environment-data-falls-back-to-telemetry-on-error
  (let [data (await
              (mock/with-mocks*
                {rp/cmd! (mock/stub (fn [_cmd] (rx/throw (ex-info "boom" {}))))}
                (await (async/->promise (ev/fetch-environment-data)))))]
    (t/is (= {:flags #{:telemetry}} data))))

(t/deftest snapshot-performance-info-counts-libraries
  (let [file-id  (uuid/next)
        lib-id   (uuid/next)
        other-id (uuid/next)
        unlinked (uuid/next)
        sized    (fn [n] (zipmap (repeatedly n uuid/next) (repeat {})))
        data     (fn [components colors typographies]
                   {:components   (sized components)
                    :colors       (sized colors)
                    :typographies (sized typographies)})
        state    {:files
                  {file-id  {:id file-id
                             :data (-> (data 1 1 1)
                                       (assoc :pages-index (zipmap (repeatedly 2 uuid/next)
                                                                   [{:objects (sized 4)}
                                                                    {:objects (sized 2)}])))}
                   lib-id   {:id lib-id
                             :library-of file-id
                             :data (data 2 3 1)}
                   other-id {:id other-id
                             :library-of file-id
                             :data (data 1 2 2)}
                   unlinked {:id unlinked
                             :library-of (uuid/next)
                             :data (data 3 3 3)}}}
        state'   (ptk/update (#'ev/snapshot-performance-info {:file-id file-id}) state)
        counters (get-in state' [:performance-info :counters])]

    (t/testing "counts shapes of the current file"
      (t/is (= 6 (:total-shapes counters))))

    (t/testing "sums the data of the file and its libraries, not other files"
      (t/is (= 4 (:total-components counters)))
      (t/is (= 6 (:total-colors counters)))
      (t/is (= 4 (:total-typographies counters))))))
