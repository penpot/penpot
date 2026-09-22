;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.nitrate-test
  (:require
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.data.nitrate-audit :as nitrate-audit]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dt]
   [app.main.store :as st]
   [app.main.ui.auth.verify-token :as verify-token]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest account-age-days-test
  (with-redefs [ct/now (constantly (ct/inst "2026-07-27T12:00:00Z"))]
    (t/testing "returns the number of complete days since account creation"
      (t/is (= 10
               (dnt/account-age-days
                {:created-at (ct/inst "2026-07-17T00:00:00Z")}))))

    (t/testing "does not return negative ages"
      (t/is (= 0
               (dnt/account-age-days
                {:created-at (ct/inst "2026-07-28T00:00:00Z")}))))

    (t/testing "returns nil when the creation date is not an instant"
      (t/is (nil? (dnt/account-age-days {})))
      (t/is (nil? (dnt/account-age-days {:created-at "invalid"}))))))

(t/deftest add-team-to-organization-audit-event-test
  (with-redefs [ct/now (constantly (ct/inst "2026-07-27T12:00:00Z"))]
    (t/testing "reports creating the first team in an organization"
      (let [event @(nitrate-audit/add-team-to-organization-event
                    {:organization-id "organization-1"
                     :organization-team-count-before 0
                     :team-previous-organization-status "no-organization"
                     :add-method "create-team-in-organization"
                     :subscription-status "trialing"})]
        (t/is (= "add-team-to-organization" (::ev/name event)))
        (t/is (= "dashboard:create-team-in-organization" (::ev/origin event)))
        (t/is (= {:is-your-penpot false
                  :add-method "create-team-in-organization"
                  :organization-id "organization-1"
                  :organization-team-count-before 0
                  :team-previous-organization-status "no-organization"
                  :is-first-team-in-organization true
                  :subscription-status "trialing"}
                 (dissoc event ::ev/name ::ev/origin)))))

    (t/testing "reports moving an older team from another organization"
      (let [event @(nitrate-audit/add-team-to-organization-event
                    {:team {:id "team-2"
                            :created-at (ct/inst "2026-07-17T12:00:00Z")}
                     :organization-id "organization-2"
                     :organization-team-count-before 3
                     :team-previous-organization-status "other-organization"
                     :add-method "move-existing-team-to-organization"
                     :subscription-status "active"})]
        (t/is (= "dashboard:move-team-to-organization" (::ev/origin event)))
        (t/is (= 10 (:team-age-days event)))
        (t/is (false? (:is-first-team-in-organization event)))))))

(t/deftest organization-team-count-test
  (let [organization-id "organization-1"
        teams [{:id "default"
                :is-default true
                :organization-id organization-id}
               {:id "team-1"
                :is-default false
                :organization-id organization-id}
               {:id "team-2"
                :is-default false
                :organization {:id organization-id}}
               {:id "other-team"
                :is-default false
                :organization-id "organization-2"}]]
    (t/is (= 2
             (nitrate-audit/organization-team-count
              teams
              organization-id)))))

(t/deftest delete-organization-member-audit-event-test
  (with-redefs [ct/now (constantly (ct/inst "2026-07-27T12:00:00Z"))]
    (let [event @(nitrate-audit/delete-organization-member-event
                  {:organization-id "organization-1"
                   :user-id "profile-1"
                   :user-who-delete-member "profile-1"
                   :deleted-by-role "organization-member"
                   :member-added-at (ct/inst "2026-07-17T12:00:00Z")
                   :organization-member-count-before 4
                   :subscription-status "trial"})]
      (t/is (= "delete-organization-member" (::ev/name event)))
      (t/is (nil? (::ev/origin event)))
      (t/is (= {:organization-id "organization-1"
                :user-id "profile-1"
                :user-who-delete-member "profile-1"
                :deleted-by-role "organization-member"
                :days-since-member-added 10
                :organization-member-count-before 4
                :subscription-status "trial"}
               (dissoc event ::ev/name))))

    (t/testing "keeps a null age when the membership date is unavailable"
      (let [event @(nitrate-audit/delete-organization-member-event
                    {:organization-id "organization-1"
                     :user-id "profile-1"
                     :user-who-delete-member "profile-1"
                     :deleted-by-role "organization-member"
                     :organization-member-count-before 4
                     :subscription-status "active"})]
        (t/is (contains? event :days-since-member-added))
        (t/is (nil? (:days-since-member-added event)))))))

(t/deftest accept-organization-invitation-audit-event-test
  (let [emitted (atom [])]
    (with-redefs [st/emit! (fn
                             ([event]
                              (swap! emitted conj event))
                             ([event & events]
                              (swap! emitted into (cons event events))))]
      (t/testing "accepting a team invitation that adds an organization member"
        (verify-token/handle-token
         {:iss :team-invitation
          :state :created
          :team-id "team-1"
          :organization-id "organization-1"
          :role :editor
          :invitation-id "invitation-1"
          :user-id "invitee-1"
          :user-who-send-invitation "inviter-1"
          :organization-member-count-before 4})

        (t/is (= {::ev/name "accept-organization-invitation"
                  ::ev/origin "team-invitation-acceptance"
                  :team-id "team-1"
                  :organization-id "organization-1"
                  :role :editor
                  :invitation-id "invitation-1"
                  :user-id "invitee-1"
                  :user-who-send-invitation "inviter-1"
                  :organization-member-add-source "team-invitation"
                  :belongs-to-team-on-add true
                  :organization-member-count-before 4}
                 @(first @emitted))))

      (reset! emitted [])
      (t/testing "accepting an invitation directly to the organization"
        (verify-token/handle-token
         {:iss :team-invitation
          :state :created
          :organization-id "organization-2"
          :organization-team-id "team-default"
          :role :viewer
          :invitation-id "invitation-2"
          :user-id "invitee-2"
          :user-who-send-invitation "inviter-2"
          :organization-member-count-before 0})

        (t/is (= {::ev/name "accept-organization-invitation"
                  ::ev/origin "organization-invitation-acceptance"
                  :organization-id "organization-2"
                  :role :viewer
                  :invitation-id "invitation-2"
                  :user-id "invitee-2"
                  :user-who-send-invitation "inviter-2"
                  :organization-member-add-source "direct-organization-invitation"
                  :belongs-to-team-on-add false
                  :organization-member-count-before 0}
                 @(first @emitted))))

      (reset! emitted [])
      (t/testing "does not audit a team invitation for an existing organization member"
        (verify-token/handle-token
         {:iss :team-invitation
          :state :created
          :team-id "team-3"
          :organization-id "organization-3"
          :role :editor})

        (t/is (= 3 (count @emitted)))))))

(t/deftest build-admin-console-url-preserves-public-uri-subpath
  (t/testing "builds admin console routes below the configured Penpot subpath"
    (let [public-uri (u/uri "https://example.com/penpot/")]
      (t/is (= "https://example.com/penpot/admin-console/"
               (dnt/build-admin-console-url public-uri "" nil)))
      (t/is (= "https://example.com/penpot/admin-console/organization/my-organization/organization-id/people/"
               (dnt/build-admin-console-url
                public-uri
                "organization/my-organization/organization-id/people/"
                nil)))
      (t/is (= {:action "create-organization"
                :origin "dashboard:organization-switcher"}
               (-> (dnt/build-admin-console-url
                    public-uri
                    ""
                    {:action "create-organization"
                     :origin "dashboard:organization-switcher"})
                   u/uri
                   :query
                   u/query-string->map))))))

(t/deftest build-nitrate-callback-urls-preserves-hash-query
  (t/testing "appends subscription to an existing query inside the hash route"
    (let [base-url  "https://localhost:3449/#/dashboard/recent?team-id=e6666530-0216-81c8-8007-f17d6087b74f"
          callbacks (dnt/build-nitrate-callback-urls base-url base-url)]
      (t/is (= "https://localhost:3449/#/dashboard/recent?team-id=e6666530-0216-81c8-8007-f17d6087b74f&subscription=subscribed-to-penpot-nitrate"
               (:success-callback callbacks)))
      (t/is (= "https://localhost:3449/#/dashboard/recent?team-id=e6666530-0216-81c8-8007-f17d6087b74f&subscription=nitrate-checkout-error"
               (:error-callback callbacks)))
      (t/is (= "https://localhost:3449/#/dashboard/recent?team-id=e6666530-0216-81c8-8007-f17d6087b74f&subscription=nitrate-checkout-finish-error"
               (:finish-error-callback callbacks)))
      (t/is (= "https://localhost:3449/#/dashboard/recent?team-id=e6666530-0216-81c8-8007-f17d6087b74f&subscription=nitrate-checkout-cancelled"
               (:cancel-callback callbacks))))))

(t/deftest build-nitrate-callback-urls-adds-hash-query-when-missing
  (t/testing "adds a hash query when the route has no query string yet"
    (let [base-url  "https://localhost:3449/#/settings/subscriptions"
          callbacks (dnt/build-nitrate-callback-urls base-url base-url)]
      (t/is (= "https://localhost:3449/#/settings/subscriptions?subscription=subscribed-to-penpot-nitrate"
               (:success-callback callbacks))))))

(t/deftest build-nitrate-callback-urls-adds-regular-query-without-hash
  (t/testing "falls back to the regular URL query when there is no hash route"
    (let [base-url  "https://localhost:3449/admin-console/licenses/billing?foo=bar"
          callbacks (dnt/build-nitrate-callback-urls base-url base-url)]
      (t/is (= "https://localhost:3449/admin-console/licenses/billing?foo=bar&subscription=subscribed-to-penpot-nitrate"
               (:success-callback callbacks))))))

(t/deftest build-nitrate-callback-urls-accepts-uri-object
  (t/testing "accepts a URI object as base url (used by the nitrate-form modal)"
    (let [base-url  (u/uri "https://localhost:3449/#/settings/subscriptions")
          callbacks (dnt/build-nitrate-callback-urls base-url base-url)]
      (t/is (= "https://localhost:3449/#/settings/subscriptions?subscription=nitrate-checkout-error"
               (:error-callback callbacks))))))

(t/deftest build-nitrate-callback-urls-uses-separate-error-base
  (t/testing "error callbacks use base-error-url while success/cancel use base-url"
    (let [callbacks (dnt/build-nitrate-callback-urls
                     "https://localhost:3449/#/dashboard/recent"
                     "https://localhost:3449/#/settings/subscriptions")]
      (t/is (= "https://localhost:3449/#/dashboard/recent?subscription=subscribed-to-penpot-nitrate"
               (:success-callback callbacks)))
      (t/is (= "https://localhost:3449/#/settings/subscriptions?subscription=nitrate-checkout-error"
               (:error-callback callbacks)))
      (t/is (= "https://localhost:3449/#/settings/subscriptions?subscription=nitrate-checkout-finish-error"
               (:finish-error-callback callbacks)))
      (t/is (= "https://localhost:3449/#/dashboard/recent?subscription=nitrate-checkout-cancelled"
               (:cancel-callback callbacks))))))

(t/deftest go-to-subscription-url-is-a-string
  (t/testing "must be a string so licenses/billing?callback=... survives query encoding"
    (t/is (string? dnt/go-to-subscription-url))
    (t/is (not (u/uri? dnt/go-to-subscription-url)))))

(t/deftest organization-teams-filters-by-organization-id
  (let [teams {"t1" {:id "t1" :organization {:id "org-a"}}
               "t2" {:id "t2" :organization {:id "org-b"}}
               "t3" {:id "t3" :is-default true}
               "t4" {:id "t4" :organization {:id "org-a"}}}]
    (t/is (= ["t1" "t4"] (map :id (dnt/organization-teams teams "org-a"))))
    (t/is (= [] (dnt/organization-teams teams "org-c")))))

(t/deftest organization-leave-info-splits-owned-and-not-owned-teams
  (let [org-teams [{:id "default" :is-default true}
                   {:id "owned-1" :permissions {:is-owner true}}
                   {:id "owned-2" :permissions {:is-owner true}}
                   {:id "member-1" :permissions {:is-owner false}}]
        info (dnt/organization-leave-info org-teams)]
    (t/is (= "default" (:default-team-id info)))
    (t/is (= ["owned-1" "owned-2"] (map :id (:owned-teams info))))
    (t/is (= ["member-1"] (map :id (:not-owned-teams info))))))

(t/deftest transferable-teams-boundary-at-one-member
  (let [owned-teams [{:id "solo" :members [{:id "m1"}]}
                     {:id "pair" :members [{:id "m1"} {:id "m2"}]}
                     {:id "empty" :members []}]]
    (t/is (= ["pair"] (map :id (dnt/transferable-teams owned-teams))))))

(t/deftest leave-organization-fn-builds-delete-and-leave-lists
  (let [captured (atom nil)
        emitted  (atom [])
        owned-teams [{:id "solo" :members [{:id "m1"}]}
                     {:id "pair" :members [{:id "m1"} {:id "m2"}]}]
        not-owned-teams [{:id "member-1" :name "extra"}]
        leave-fn (dnt/leave-organization-fn {:organization {:id "org-1" :name "Acme"}
                                             :default-team-id "default"
                                             :owned-teams owned-teams
                                             :not-owned-teams not-owned-teams
                                             :on-error :on-error-fn})]
    (with-redefs [dnt/leave-organization (fn [params] (reset! captured params) ::leave-event)
                  st/emit! (fn
                             ([event] (swap! emitted conj event))
                             ([event & events] (swap! emitted into (cons event events))))]

      (t/testing "with no teams offered for transfer"
        (leave-fn {:teams-to-transfer nil
                   :member-added-at "2026-07-17T00:00:00Z"
                   :organization-member-count-before 3})

        (t/is (= [::leave-event] @emitted))
        (t/is (= {:id "org-1"
                  :name "Acme"
                  :default-team-id "default"
                  :teams-to-delete ["solo"]
                  :teams-to-leave [{:id "member-1"}]
                  :member-added-at "2026-07-17T00:00:00Z"
                  :organization-member-count-before 3
                  :on-error :on-error-fn}
                 @captured)))

      (t/testing "folds transferred teams into teams-to-leave, ahead of the rest"
        (leave-fn {:teams-to-transfer [{:id "pair" :reassign-to "new-owner"}]
                   :member-added-at "2026-07-17T00:00:00Z"
                   :organization-member-count-before 3})

        (t/is (= [{:id "pair" :reassign-to "new-owner"}
                  {:id "member-1"}]
                 (:teams-to-leave @captured)))))))

(t/deftest team-leave-on-error-matrix
  (t/testing "known error code shows a translated notification"
    (let [emitted (atom [])]
      (->> (dnt/team-leave-on-error (ex-info "boom" {:code :owner-cant-leave-team}))
           (rx/subs! #(swap! emitted conj %)))
      (t/is (= 1 (count @emitted)))
      (t/is (= :visible (:status (:notification (ptk/update (first @emitted) {})))))))

  (t/testing "unknown error code rethrows the original error"
    (let [error (ex-info "boom" {:code :something-unmapped})
          rejected (atom [])]
      (->> (dnt/team-leave-on-error error)
           (rx/subs! (fn [_]) #(swap! rejected conj %)))
      (t/is (= [error] @rejected)))))

(t/deftest org-leave-on-error-matrix
  (t/testing "known error code refetches teams, hides the modal, and notifies"
    (let [emitted (atom [])]
      (->> (dnt/org-leave-on-error (ex-info "boom" {:code :not-valid-teams}))
           (rx/subs! #(swap! emitted conj %)))
      (t/is (= 3 (count @emitted)))
      (t/is (some (ptk/type? ::dt/fetch-teams) @emitted))
      (t/is (some (ptk/type? ::modal/hide-modal) @emitted))
      (let [notification-event (first (filter (ptk/type? ::ntf/show) @emitted))]
        (t/is (some? notification-event))
        (t/is (= :visible (:status (:notification (ptk/update notification-event {}))))))))

  (t/testing "unknown error code rethrows the original error"
    (let [error (ex-info "boom" {:code :something-unmapped})
          rejected (atom [])]
      (->> (dnt/org-leave-on-error error)
           (rx/subs! (fn [_]) #(swap! rejected conj %)))
      (t/is (= [error] @rejected)))))

(t/deftest build-admin-console-billing-url-encodes-string-callback
  (t/testing "billing callback query param round-trips as a real URL string"
    (let [public-uri (u/uri "https://localhost:3449/")
          callback   "https://localhost:3449/#/settings/subscriptions"
          href       (dnt/build-admin-console-url
                      public-uri
                      "licenses/billing"
                      {:callback callback})
          parsed     (-> href u/uri :query u/query-string->map :callback)]
      (t/is (= callback parsed)))))
