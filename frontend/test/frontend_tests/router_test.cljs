;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.router-test
  (:require
   [app.main.router :as rt]
   [cljs.test :as t :include-macros true]))

(def ^:private test-routes
  #{:auth-login :auth-register :dashboard-recent :workspace})

(t/deftest resolve-target-screen-wins-over-forwarded-params
  ;; Screens forward the current query params when navigating (e.g.
  ;; login passes its params to the register link); the stale screen
  ;; they carry must never override the destination.
  (t/is (= "?screen=auth-register&foo=1"
           (rt/resolve test-routes :auth-register {:screen "auth-login"
                                                   :foo "1"}))))

(t/deftest resolve-builds-screen-token
  (t/is (= "?screen=dashboard-recent&team-id=team-1"
           (rt/resolve test-routes :dashboard-recent {:team-id "team-1"})))
  (t/is (= "?screen=auth-login"
           (rt/resolve test-routes :auth-login)))
  (t/is (nil? (rt/resolve test-routes :unknown-screen {:team-id "team-1"}))))

(t/deftest resolve-uri-builds-absolute-url
  (let [uri (rt/resolve-uri test-routes :workspace {:file-id "file-1"})]
    (t/is (string? uri))
    (t/is (re-find #"\?screen=workspace&file-id=file-1$" uri))))

(t/deftest match-resolves-screen-token
  (let [match (rt/match test-routes "?screen=workspace&team-id=team-1&file-id=file-1")]
    (t/is (= :workspace (get-in match [:data :name])))
    (t/is (= "team-1" (get-in match [:params :query :team-id])))
    (t/is (= "file-1" (get-in match [:query-params :file-id])))
    (t/is (= {} (get-in match [:params :path])))))

(t/deftest match-rejects-missing-or-unknown-screen
  (t/is (nil? (rt/match test-routes "")))
  (t/is (nil? (rt/match test-routes "?team-id=team-1")))
  (t/is (nil? (rt/match test-routes "?screen=nope&team-id=team-1"))))
