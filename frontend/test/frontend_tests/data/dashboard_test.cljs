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
   [app.main.data.project :as dpj]
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

(t/deftest never-answering-recent-files-fetch-resolves-placeholder-to-failure
  (t/async done
    (let [team-id (uuid/next)
          state   {:current-team-id team-id}]
      (mock/with-mocks
        {rp/fetch-timeout-ms 50
         rp/cmd! (mock/stub (fn [_ _] (rx/subject)))}
        (fn [done']
          (->> (ptk/watch (dd/fetch-recent-files team-id) state nil)
               (rx/reduce (fn [state event] (ptk/update event state)) state)
               ;; The guard fails the test instead of spinning when no
               ;; failure state arrives within the bound.
               (rx/timeout 1000 (rx/throw (ex-info "no failure state within the bound" {})))
               (rx/subs!
                (fn [state]
                  (t/is (contains? (:dashboard-fetch-failures state) ::dd/fetch-recent-files)
                        "the placeholder resolves into a failure state"))
                (fn [cause]
                  (t/is false (str "unexpected error: " cause))
                  (done'))
                (fn []
                  (done')))))
        done))))

(t/deftest never-answering-project-files-fetch-resolves-placeholder-to-failure
  (t/async done
    (let [project-id (uuid/next)
          state      {}]
      (mock/with-mocks
        {rp/fetch-timeout-ms 50
         rp/cmd! (mock/stub (fn [_ _] (rx/subject)))}
        (fn [done']
          (->> (ptk/watch (dpj/fetch-files project-id) state nil)
               (rx/reduce (fn [state event] (ptk/update event state)) state)
               (rx/timeout 1000 (rx/throw (ex-info "no failure state within the bound" {})))
               (rx/subs!
                (fn [state]
                  (t/is (contains? (:dashboard-fetch-failures state) ::dpj/fetch-files)
                        "the placeholder resolves into a failure state"))
                (fn [cause]
                  (t/is false (str "unexpected error: " cause))
                  (done'))
                (fn []
                  (done')))))
        done))))

(t/deftest websocket-reconnect-refetches-placeholder-data
  (t/async done
    (let [team-id    (uuid/next)
          project-id (uuid/next)
          calls      (atom [])
          state      {:current-team-id team-id
                      :route {:params {:query {:project-id (str project-id)}}}}
          store      (ptk/store {:state state
                                 :on-error (fn [cause] (t/is false (str cause)))})]
      (mock/with-mocks
        {rp/cmd! (mock/stub (fn [cmd params]
                              (swap! calls conj [cmd params])
                              (rx/of nil)))}
        (fn [done']
          (ptk/emit! store (dd/initialize team-id))
          (ptk/emit! store (ptk/data-event ::dws/opened {}))
          (t/is (= 2 (count (filter #(= :get-projects (first %)) @calls)))
                "the reconnect refetches the team projects exactly once")
          (t/is (= 1 (count (filter #(= :get-team-recent-files (first %)) @calls)))
                "the reconnect refetches the recent files exactly once")
          (t/is (= 1 (count (filter #(= :get-project-files (first %)) @calls)))
                "the reconnect refetches the project files exactly once")
          (t/is (= [{:project-id project-id}]
                   (into [] (comp (filter #(= :get-project-files (first %)))
                                  (map second))
                         @calls))
                "the refetched project files are the ones the placeholder waits for")
          (rx/dispose! store)
          (done'))
        done))))
