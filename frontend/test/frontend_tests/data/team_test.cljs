;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns frontend-tests.data.team-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.team :as dtm]
   [app.main.repo :as rp]
   [app.main.ui.dashboard.team :as dtui]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- fetch-invitations-events
  "Runs the watch of `fetch-invitations` with `rp/cmd!` answering with
  `response` and resolves to the events it emits."
  [response]
  (let [team-id (uuid/next)
        state   {:current-team-id team-id}
        emitted (atom [])]
    (-> (mock/with-mocks*
          {rp/cmd! (mock/stub (fn [_cmd _params] response))}
          (await (async/observe (ptk/watch (dtm/fetch-invitations) state nil)
                                :on-next #(swap! emitted conj %))))
        (.then (fn [_] @emitted)))))

(t/deftest ^:async fetch-invitations-ignores-permission-error
  ;; The pending invitations are an admin only list: a member that cannot
  ;; invite gets an insufficient permissions error, and that must not
  ;; become an error toast.
  (let [events (await (fetch-invitations-events
                       (rx/throw (ex-info "insufficient permissions"
                                          {:type :validation
                                           :code :insufficient-permissions}))))]
    (t/is (empty? events))))

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
  (let [events (await (fetch-invitations-events
                       (rx/of [{:email "invited@example.com" :role :editor}])))]
    (t/is (= 1 (count events)))
    (t/is (= ::dtm/invitations-fetched (ptk/type (first events))))))

(t/deftest invitations-section-shows-the-list-only-to-the-ones-that-can-invite
  (t/is (= :list (dtui/invitations-section-content true)))
  (t/is (= :no-permission (dtui/invitations-section-content false))))

(t/deftest owner-invitation-link-is-copyable-only-by-the-team-owner
  ;; An admin cannot create nor promote an owner invitation, so the backend
  ;; does not mint its token for them: the action is not offered either.
  (t/is (true? (dtui/invitation-link-copyable? true :owner)))
  (t/is (false? (dtui/invitation-link-copyable? false :owner)))
  (t/is (true? (dtui/invitation-link-copyable? false :editor)))
  (t/is (true? (dtui/invitation-link-copyable? false :viewer))))
