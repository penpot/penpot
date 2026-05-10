;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.nitrate-test
  (:require
   [app.common.uri :as u]
   [app.main.data.nitrate :as dnt]
   [cljs.test :as t :include-macros true]))

(t/deftest build-nitrate-callback-urls-preserves-hash-query
  (t/testing "appends subscription to an existing query inside the hash route"
    (let [callbacks (dnt/build-nitrate-callback-urls
                     "https://localhost:3449/#/dashboard/recent?team-id=e6666530-0216-81c8-8007-f17d6087b74f")]
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
    (let [callbacks (dnt/build-nitrate-callback-urls
                     "https://localhost:3449/#/settings/subscriptions")]
      (t/is (= "https://localhost:3449/#/settings/subscriptions?subscription=subscribed-to-penpot-nitrate"
               (:success-callback callbacks))))))

(t/deftest build-nitrate-callback-urls-adds-regular-query-without-hash
  (t/testing "falls back to the regular URL query when there is no hash route"
    (let [callbacks (dnt/build-nitrate-callback-urls
                     "https://localhost:3449/control-center/licenses/billing?foo=bar")]
      (t/is (= "https://localhost:3449/control-center/licenses/billing?foo=bar&subscription=subscribed-to-penpot-nitrate"
               (:success-callback callbacks))))))

(t/deftest build-nitrate-callback-urls-accepts-uri-object
  (t/testing "accepts a URI object as base url (used by the nitrate-form modal)"
    (let [callbacks (dnt/build-nitrate-callback-urls
                     (u/uri "https://localhost:3449/#/settings/subscriptions"))]
      (t/is (= "https://localhost:3449/#/settings/subscriptions?subscription=nitrate-checkout-error"
               (:error-callback callbacks))))))
