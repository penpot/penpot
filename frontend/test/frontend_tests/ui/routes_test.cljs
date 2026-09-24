;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.routes-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.routes :as routes]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(defn- workspace-match
  [team-id]
  {:data {:name :workspace}
   :params {:path {}}
   :query-params {:team-id (str team-id)}})

(t/deftest sso-check-is-cached-for-five-minutes
  (t/async done
    (let [team-id   (uuid/next)
          match     (workspace-match team-id)
          now       (atom (ct/inst "2026-08-11T10:00:00Z"))
          rpc-calls (atom 0)
          events    (atom [])]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         ct/now   (mock/stub (fn [] @now))
         rp/cmd!  (mock/stub
                   (fn [command params]
                     (t/is (= :check-nitrate-sso command))
                     (t/is (= team-id (:team-id params)))
                     (swap! rpc-calls inc)
                     (rx/of {:authorized true})))
         st/emit! (mock/stub
                   (fn [& emitted]
                     (swap! events into emitted)))}
        (fn [done']
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (reset! now (ct/plus @now #js {:minutes 4 :seconds 59}))
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (t/is (= 1 @rpc-calls))
          (t/is (= 2 (count @events)))
          (done'))
        done))))

(t/deftest sso-check-is-refreshed-after-five-minutes
  (t/async done
    (let [team-id   (uuid/next)
          match     (workspace-match team-id)
          now       (atom (ct/inst "2026-08-11T10:00:00Z"))
          rpc-calls (atom 0)]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         ct/now   (mock/stub (fn [] @now))
         rp/cmd!  (mock/stub
                   (fn [_ _]
                     (swap! rpc-calls inc)
                     (rx/of {:authorized true})))
         st/emit! mock/noop}
        (fn [done']
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (reset! now (ct/plus @now #js {:minutes 5}))
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (t/is (= 2 @rpc-calls))
          (done'))
        done))))

(t/deftest sso-redirect-result-is-not-cached
  (t/async done
    (let [team-id   (uuid/next)
          match     (workspace-match team-id)
          rpc-calls (atom 0)
          events    (atom [])]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         rp/cmd!  (mock/stub
                   (fn [_ _]
                     (swap! rpc-calls inc)
                     (rx/of {:authorized false
                             :redirect-uri "https://idp.example.com/authorize"})))
         st/emit! (mock/stub
                   (fn [& emitted]
                     (swap! events into emitted)))}
        (fn [done']
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (t/is (= 2 @rpc-calls))
          (t/is (= 2 (count @events)))
          (done'))
        done))))
