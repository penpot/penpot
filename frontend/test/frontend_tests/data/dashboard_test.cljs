;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.dashboard-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(t/deftest moving-current-team-into-sso-organization-redirects
  (t/async done
    (let [team-id      (uuid/next)
          organization {:id (uuid/next) :name "OrgA"}
          current-url  (str "https://penpot.example.com/#/dashboard/recent?team-id=" team-id)
          redirect-url "https://idp.example.com/authorize"
          state        {:current-team-id team-id}
          event        (dcm/handle-change-team-organization
                        {:team {:id team-id :organization organization}
                         :notification nil})]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         rp/cmd! (mock/stub
                  (fn [cmd params]
                    (if (= [:check-nitrate-sso
                            {:team-id team-id :url current-url}]
                           [cmd params])
                      (rx/of {:authorized false :redirect-uri redirect-url})
                      (rx/throw (ex-info "unexpected RPC" {:cmd cmd :params params})))))
         rt/get-current-href (mock/stub (constantly current-url))}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [events]
                  (t/is (= [::rt/nav-raw] (mapv ptk/type events))))
                (fn [error]
                  (t/is false (str "unexpected error: " error))
                  (done'))
                (fn []
                  (done')))))
        done))))

(t/deftest organization-sso-activation-redirects-current-team
  (t/async done
    (let [team-id      (uuid/next)
          organization-id       (uuid/next)
          current-url  (str "https://penpot.example.com/#/workspace?team-id=" team-id)
          redirect-url "https://idp.example.com/authorize"
          state        {:current-team-id team-id
                        :teams {team-id {:id team-id
                                         :organization {:id organization-id}}}}
          event        (dcm/handle-organization-change-sso
                        {:organization-id organization-id})]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         rp/cmd! (mock/stub
                  (fn [cmd params]
                    (if (= [:check-nitrate-sso
                            {:team-id team-id :url current-url}]
                           [cmd params])
                      (rx/of {:authorized false :redirect-uri redirect-url})
                      (rx/throw (ex-info "unexpected RPC" {:cmd cmd :params params})))))
         rt/get-current-href (mock/stub (constantly current-url))}
        (fn [done']
          (->> (ptk/watch event state nil)
               (rx/reduce conj [])
               (rx/subs!
                (fn [events]
                  (t/is (= [::rt/nav-raw] (mapv ptk/type events))))
                (fn [error]
                  (t/is false (str "unexpected error: " error))
                  (done'))
                (fn []
                  (done')))))
        done))))
