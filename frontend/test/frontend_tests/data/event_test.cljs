;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.event-test
  (:require
   [app.main.data.event :as ev]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]))

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
