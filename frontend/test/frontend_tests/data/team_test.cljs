;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns frontend-tests.data.team-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.team :as dtm]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.ui.dashboard.team :as dtui]
   [app.util.dom :as dom]
   [app.util.session-state :as ss]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- fetch-invitations-events
  "Runs the watch of `fetch-invitations` with `rp/cmd!` answering with
  `response` and resolves to the team id and the events it emits."
  [response]
  (let [team-id (uuid/next)
        state   {:current-team-id team-id}
        emitted (atom [])]
    (-> (mock/with-mocks*
          {rp/cmd! (mock/stub (fn [_cmd _params] response))}
          (await (async/observe (ptk/watch (dtm/fetch-invitations) state nil)
                                :on-next #(swap! emitted conj %))))
        (.then (fn [_] {:team-id team-id :events @emitted})))))

(t/deftest ^:async check-and-create-team-redirects-to-sso-and-remembers-the-action
  (let [team-id         "00000000-0000-4000-8000-000000000001"
        organization-id "00000000-0000-4000-8000-000000000002"
        profile-id      "00000000-0000-4000-8000-000000000003"
        dashboard-url   "https://penpot.example/#/dashboard-recent?team-id=current-team"
        callback-url    (str dashboard-url "&pending-action-id=pending-id")
        team            {:id team-id
                         :organization {:id organization-id
                                        :name "Product"
                                        :owner-id profile-id}}
        requests        (atom [])
        saved-actions   (atom [])
        emitted         (atom [])]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [command params]
                   (swap! requests conj [command params])
                   (case command
                     :get-teams (rx/of [team])
                     :check-nitrate-sso
                     (rx/of {:authorized false
                             :redirect-uri "https://sso.example.test/login"}))))
        rt/get-current-href (mock/stub (constantly dashboard-url))
        dom/append-query-param (mock/stub (fn [_url _key _value] callback-url))
        uuid/next (mock/stub (constantly "pending-id"))
        ss/save-pending-action! (mock/stub (fn [& args]
                                             (swap! saved-actions conj args)))
        rt/nav-raw (fn [& args] {:nav-raw args})
        modal/show (mock/stub (fn [& args] {:modal-show args}))}
       (await (async/observe
               (ptk/watch (dtm/check-and-create-team team-id)
                          {:profile {:id profile-id}}
                          nil)
               :on-next #(swap! emitted conj %)))))

    (t/is (= [[:get-teams nil]
              [:check-nitrate-sso {:team-id team-id :url callback-url}]]
             @requests))
    (t/is (= [["pending-id" {:type :create-team-in-organization
                             :team-id team-id}]]
             @saved-actions))
    (t/is (= {:nav-raw [:uri "https://sso.example.test/login"]}
             (last @emitted)))
    (t/is (not-any? #(contains? % :modal-show) @emitted))))

(t/deftest ^:async check-and-create-team-shows-form-after-sso-is-authorized
  (let [team-id         "00000000-0000-4000-8000-000000000001"
        organization-id "00000000-0000-4000-8000-000000000002"
        profile-id      "00000000-0000-4000-8000-000000000003"
        dashboard-url   "https://penpot.example/#/dashboard-recent?team-id=current-team"
        callback-url    (str dashboard-url "&pending-action-id=pending-id")
        team            {:id team-id
                         :organization {:id organization-id
                                        :name "Product"
                                        :owner-id profile-id}}
        requests        (atom [])
        emitted         (atom [])]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub
                 (fn [command params]
                   (swap! requests conj [command params])
                   (case command
                     :get-teams (rx/of [team])
                     :check-nitrate-sso (rx/of {:authorized true}))))
        rt/get-current-href (mock/stub (constantly dashboard-url))
        dom/append-query-param (mock/stub (fn [_url _key _value] callback-url))
        uuid/next (mock/stub (constantly "pending-id"))
        modal/show (mock/stub (fn [& args] {:modal-show args}))}
       (await (async/observe
               (ptk/watch (dtm/check-and-create-team team-id)
                          {:profile {:id profile-id}}
                          nil)
               :on-next #(swap! emitted conj %)))))

    (t/is (= [:check-nitrate-sso {:team-id team-id :url callback-url}]
             (last @requests)))
    (t/is (= {:modal-show [:team-form {:organization-id organization-id
                                       :organization-name "Product"}]}
             (last @emitted)))
    (t/is (= 2 (count @emitted)))))

(t/deftest ^:async fetch-invitations-clears-the-list-on-permission-error
  ;; The pending invitations are an admin only list: a member that cannot
  ;; invite gets an insufficient permissions error. That must not become an
  ;; error toast, and the cached list must be dropped so the stale emails do
  ;; not stay around.
  (let [{:keys [team-id events]}
        (await (fetch-invitations-events
                (rx/throw (ex-info "insufficient permissions"
                                   {:type :validation
                                    :code :insufficient-permissions}))))
        event  (first events)
        state  (ptk/update event {:teams {team-id {:id team-id
                                                   :invitations [{:email "stale@example.com"
                                                                  :role :editor}]}}})]
    (t/is (= 1 (count events)))
    (t/is (= ::dtm/invitations-fetched (ptk/type event)))
    (t/is (= [] (get-in state [:teams team-id :invitations])))))

(t/deftest ^:async fetch-invitations-propagates-other-errors
  ;; Only the permission failure is swallowed: any other error is a real
  ;; problem and must reach the error reporting.
  (let [outcome (await (mock/with-mocks*
                         {rp/cmd! (mock/stub (fn [_cmd _params]
                                               (rx/throw (ex-info "boom" {:type :server}))))}
                         (try
                           (await (async/observe (ptk/watch (dtm/fetch-invitations)
                                                            {:current-team-id (uuid/next)}
                                                            nil)))
                           :resolved
                           (catch js/Error _ :rejected))))]
    (t/is (= :rejected outcome))))

(t/deftest ^:async fetch-invitations-emits-the-update-event
  (let [{:keys [events]} (await (fetch-invitations-events
                                 (rx/of [{:email "invited@example.com" :role :editor}])))]
    (t/is (= 1 (count events)))
    (t/is (= ::dtm/invitations-fetched (ptk/type (first events))))))

(t/deftest invitations-section-shows-the-list-only-to-the-team-admins
  ;; The list is admin only, and the backend normalizes `:is-admin` to
  ;; include the team owner. The organization invite policy does not gate it.
  (t/is (= :list (dtui/invitations-section-content {:is-owner false :is-admin true})))
  (t/is (= :list (dtui/invitations-section-content {:is-owner true :is-admin true})))
  (t/is (= :no-permission (dtui/invitations-section-content {:is-owner false :is-admin false}))))

(t/deftest owner-invitation-link-is-copyable-only-by-the-team-owner
  ;; An admin cannot create nor promote an owner invitation, so the backend
  ;; does not mint its token for them: the action is not offered either.
  (t/is (true? (dtui/invitation-link-copyable? true :owner)))
  (t/is (false? (dtui/invitation-link-copyable? false :owner)))
  (t/is (true? (dtui/invitation-link-copyable? false :editor)))
  (t/is (true? (dtui/invitation-link-copyable? false :viewer))))
