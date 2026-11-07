;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.error-boundary-test
  (:require
   [app.main.ui.error-boundary :as eb]
   [cljs.test :as t :include-macros true]))

(defn- match
  "A route match like the one `refs/route` holds, with `screen` as the
  screen name and `params` as the query map. `rt/match` builds a fresh
  keyword for the screen on every navigation, so the caller gets a
  different object each time — like the real one."
  [screen params]
  {:data {:name (keyword (name screen))}
   :params {:path {} :query params}
   :query-params params})

(t/deftest reset-keys-is-a-real-js-array
  ;; `react-error-boundary` reads `resetKeys` with `.length` and `.some`
  ;; inside `componentDidUpdate`. A ClojureScript vector has neither, so
  ;; passing one throws `some is not a function` the first time the
  ;; boundary is in error state and the route changes.
  (t/is (array? (eb/reset-keys nil)))
  (t/is (array? (eb/reset-keys (match :workspace {})))))

(t/deftest reset-keys-holds-the-screen-name
  (t/is (= "workspace"
           (aget (eb/reset-keys (match :workspace {:team-id "team-1"})) 0)))
  ;; `app` renders `page*` only once a route exists, so the first render
  ;; has no match yet. The boundary has to get through that and reset
  ;; when the first screen arrives.
  (t/is (nil? (aget (eb/reset-keys nil) 0))))

(t/deftest reset-keys-ignores-params-of-the-same-screen
  ;; A param change on the same screen is not a new screen. Replaying
  ;; the screen that just crashed is the one thing to avoid, so the
  ;; entries the library compares with `Object.is` must stay put when
  ;; only params move — even though the route map, and the keyword in
  ;; it, are fresh objects on every navigation.
  (let [a (eb/reset-keys (match :workspace {:team-id "team-1"}))
        b (eb/reset-keys (match :workspace {:team-id "team-2"}))]
    (t/is (= (alength a) (alength b)))
    (t/is (identical? (aget a 0) (aget b 0)))))

(t/deftest reset-keys-changes-when-the-screen-changes
  ;; This is what lets every exit from the error page work: each of them
  ;; only navigates, and none of them reaches `reset-error-boundary`.
  (let [a (eb/reset-keys (match :workspace {:team-id "team-1"}))
        b (eb/reset-keys (match :dashboard-recent {}))]
    (t/is (= (alength a) (alength b)))
    (t/is (not (identical? (aget a 0) (aget b 0))))))
