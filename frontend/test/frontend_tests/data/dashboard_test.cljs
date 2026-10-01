;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.dashboard-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.websocket :as dws]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
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

(defn- sent-messages
  "Runs the watch of `event` against `stream` with `dws/send` stubbed
  and resolves to the messages it sends over the websocket."
  [event state stream]
  (let [sent (atom [])]
    (-> (mock/with-mocks*
          {dws/send (mock/stub (fn [msg] (swap! sent conj msg) msg))}
          (await (async/observe (ptk/watch event state stream))))
        (.then (fn [_] @sent)))))

(t/deftest ^:async dashboard-initialize-subscribes-to-team
  (let [team-id (uuid/next)
        state   {:profile-id (uuid/next)
                 :teams {team-id {:id team-id :organization {:id (uuid/next)}}}}
        sent    (await (sent-messages (dd/initialize team-id) state (rx/empty)))]
    (t/is (= [{:type :subscribe-team :team-id team-id}] sent))))

(t/deftest ^:async dashboard-initialize-resubscribes-on-reconnect
  (let [team-id (uuid/next)
        state   {:profile-id (uuid/next)
                 :teams {team-id {:id team-id}}}
        stream  (rx/of (ptk/data-event ::dws/opened {})
                       (ptk/data-event ::dws/opened {}))
        sent    (await (sent-messages (dd/initialize team-id) state stream))]
    (t/is (= 3 (count sent)))
    (t/is (every? #(= {:type :subscribe-team :team-id team-id} %) sent))))

(t/deftest ^:async dashboard-initialize-accepts-team-organization-messages
  (let [team-id   (uuid/next)
        org-id    (uuid/next)
        state     {:profile-id (uuid/next)
                   :teams {team-id {:id team-id :organization {:id org-id}}}}
        message   (fn [topic]
                    (ptk/data-event ::dws/message
                                    {:type :organization-change-sso
                                     :topic topic
                                     :organization-id org-id}))
        stream    (rx/of (message org-id) (message (uuid/next)))
        processed (atom [])]
    (await (mock/with-mocks*
             {dws/send (mock/stub identity)}
             (await (async/observe (ptk/watch (dd/initialize team-id) state stream)
                                   :on-next #(swap! processed conj (ptk/type %))))))
    (t/is (= 1 (count (filter #{::dcm/handle-organization-change-sso} @processed))))))
