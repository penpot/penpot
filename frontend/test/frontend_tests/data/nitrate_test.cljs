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
   [app.main.data.nitrate :as dnt]
   [app.main.data.nitrate-audit :as nitrate-audit]
   [app.main.store :as st]
   [app.main.ui.auth.verify-token :as verify-token]
   [cljs.test :as t :include-macros true]))

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
  (let [emitted (atom [])
        props   {:team-id "team-1"
                 :organization-id "organization-1"
                 :role :editor
                 :invitation-id "invitation-1"
                 :organization-member-add-source "team-invitation"
                 :belongs-to-team-on-add true
                 :organization-member-count-before 4}]
    (with-redefs [st/emit! (fn
                             ([event]
                              (swap! emitted conj event))
                             ([event & events]
                              (swap! emitted into (cons event events))))]
      (verify-token/handle-token
       {:iss :team-invitation
        :state :created
        :team-id "team-1"
        :organization-invitation-audit
        {:origin "team-invitation-acceptance"
         :props props}}))

    (let [event @(first @emitted)]
      (t/is (= "accept-organization-invitation" (::ev/name event)))
      (t/is (= "team-invitation-acceptance" (::ev/origin event)))
      (t/is (= props (dissoc event ::ev/name ::ev/origin))))))

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
