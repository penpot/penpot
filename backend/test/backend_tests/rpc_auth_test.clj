;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-auth-test
  (:require
   [app.common.uuid :as uuid]
   [app.http.session :as session]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest logout-invalidates-current-session
  (let [prof    (th/create-profile* 1)
        manager (::session/manager th/*system*)
        sid     (uuid/random)
        _       (th/db-exec-one! ["INSERT INTO http_session_v2 (id, profile_id, user_agent) VALUES (?, ?, ?)"
                                  sid (:id prof) "test-agent"])
        session (session/read-session manager sid)]

    ;; Arrange: session exists before logout
    (t/is (some? session) "session should exist before logout")
    (t/is (= sid (:id session)))

    ;; Act: simulate Ring request as produced by wrap-authz (has ::session/session)
    ;; delete-fn is used as response transform via rph/with-transform in auth/logout
    (let [request  {::session/session session}
          response {}
          delete-fn (session/delete-fn th/*system*)
          result    (delete-fn request response)]

      ;; Assert: server-side session is deleted (CWE-613)
      (t/is (nil? (session/read-session manager sid))
            "session must be deleted server-side after logout (GHSA-mj9f-5cwq-7p3q)")

      ;; Assert: cookie is cleared
      (t/is (= "" (get-in result [::yres/cookies "auth-token" :value]))
            "auth-token cookie should be cleared")
      (t/is (= 0 (get-in result [::yres/cookies "auth-token" :max-age]))
            "auth-token cookie max-age should be 0"))))

(t/deftest logout-clears-cookie-even-when-session-missing
  (let [manager (::session/manager th/*system*)
        sid     (uuid/random)
        ;; No session inserted, read should be nil
        _       (t/is (nil? (session/read-session manager sid)))
        request {}
        response {}
        delete-fn (session/delete-fn th/*system*)
        result    (delete-fn request response)]

    ;; Should still clear cookie (idempotent)
    (t/is (= "" (get-in result [::yres/cookies "auth-token" :value])))
    (t/is (= 0 (get-in result [::yres/cookies "auth-token" :max-age])))))

(t/deftest logout-does-not-invalidate-other-sessions
  (let [prof    (th/create-profile* 1)
        manager (::session/manager th/*system*)
        sid1    (uuid/random)
        sid2    (uuid/random)
        _       (th/db-exec-one! ["INSERT INTO http_session_v2 (id, profile_id, user_agent) VALUES (?, ?, ?)"
                                  sid1 (:id prof) "agent-1"])
        _       (th/db-exec-one! ["INSERT INTO http_session_v2 (id, profile_id, user_agent) VALUES (?, ?, ?)"
                                  sid2 (:id prof) "agent-2"])
        s1      (session/read-session manager sid1)
        s2      (session/read-session manager sid2)]

    (t/is (some? s1))
    (t/is (some? s2))

    ;; Logout only sid1
    (let [request  {::session/session s1}
          response {}
          delete-fn (session/delete-fn th/*system*)]
      (delete-fn request response))

    ;; sid1 deleted, sid2 intact
    (t/is (nil? (session/read-session manager sid1)) "current session should be deleted")
    (t/is (some? (session/read-session manager sid2)) "other sessions should remain")))

(t/deftest replay-after-logout-cannot-authenticate
  (let [prof    (th/create-profile* 1)
        manager (::session/manager th/*system*)
        sid     (uuid/random)
        _       (th/db-exec-one! ["INSERT INTO http_session_v2 (id, profile_id, user_agent) VALUES (?, ?, ?)"
                                  sid (:id prof) "test-agent"])
        session (session/read-session manager sid)]

    (t/is (some? session) "session exists before logout")

    ;; Simulate logout
    (let [request  {::session/session session}
          response {}
          delete-fn (session/delete-fn th/*system*)]
      (delete-fn request response))

    ;; Replay: attempt to read session with same sid should fail (no profile attached)
    (t/is (nil? (session/read-session manager sid))
          "replayed token must not resolve to a valid session after logout")))


