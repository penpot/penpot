;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-auth-test
  (:require
   [app.auth.ldap :as ldap]
   [app.auth.login-lockout :as lol]
   [app.common.flags :as flags]
   [app.common.generic-pool :as gpool]
   [app.common.uuid :as uuid]
   [app.http.session :as session]
   [app.redis :as rds]
   [app.rpc.commands.ldap :as ldap-cmd]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as yres])
  (:import
   java.lang.AutoCloseable))

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

(defn- cleanup-redis
  [profile-id]
  (let [pool (get-in th/*system* [:app.redis/pool])
        conn (gpool/get pool)]
    (try
      (rds/del @conn (str "penpot.login-lockout." profile-id))
      (finally
        (.close ^AutoCloseable conn)))))

(t/deftest login-with-password-locks-after-max-attempts
  (with-redefs [app.config/flags (flags/parse flags/default [:enable-account-lockout])]
    (let [profile (th/create-profile* 200 {:is-active true})]
      (cleanup-redis (:id profile))
      ;; 4 failed attempts — not yet locked
      (dotimes [i 4]
        (let [data {::th/type :login-with-password
                    :email (:email profile)
                    :password "wrongpassword"}
              out  (th/command! data)]
          (t/is (th/ex-of-code? (:error out) :wrong-credentials))))
      ;; 5th attempt — should trigger lockout
      (let [data {::th/type :login-with-password
                  :email (:email profile)
                  :password "wrongpassword"}
            out  (th/command! data)]
        (t/is (th/ex-of-type? (:error out) :rate-limit))
        (t/is (th/ex-of-code? (:error out) :account-locked))))))

(t/deftest login-with-password-returns-rate-limit-when-locked
  (with-redefs [app.config/flags (flags/parse flags/default [:enable-account-lockout])]
    (let [profile (th/create-profile* 201 {:is-active true})]
      (cleanup-redis (:id profile))
      ;; Trigger lockout
      (dotimes [i 5]
        (th/command! {::th/type :login-with-password
                      :email (:email profile)
                      :password "wrongpassword"}))
      ;; Next attempt with correct password — still blocked
      (let [data {::th/type :login-with-password
                  :email (:email profile)
                  :password "Test123!"}
            out  (th/command! data)]
        (t/is (th/ex-of-type? (:error out) :rate-limit))
        (t/is (th/ex-of-code? (:error out) :account-locked))
        (let [ttl (:ttl (ex-data (:error out)))]
          (t/is (some? ttl) "ttl should be present in ex-data")
          (t/is (pos? ttl) "ttl should be a positive number"))))))

(t/deftest successful-login-clears-failed-attempts
  (with-redefs [app.config/flags (flags/parse flags/default [:enable-account-lockout])]
    (let [profile (th/create-profile* 202 {:is-active true})]
      (cleanup-redis (:id profile))
      ;; 3 failed attempts
      (dotimes [i 3]
        (th/command! {::th/type :login-with-password
                      :email (:email profile)
                      :password "wrongpassword"}))
      ;; Successful login
      (let [data {::th/type :login-with-password
                  :email (:email profile)
                  :password "Test123!"}
            out  (th/command! data)]
        (t/is (nil? (:error out))))
      ;; 4 more failed attempts should NOT trigger lockout (counter was reset)
      (dotimes [i 4]
        (let [data {::th/type :login-with-password
                    :email (:email profile)
                    :password "wrongpassword"}
              out  (th/command! data)]
          (t/is (th/ex-of-code? (:error out) :wrong-credentials)))))))

(t/deftest lockout-does-not-apply-when-flag-disabled
  (with-redefs [app.config/flags (flags/parse flags/default [])]
    (let [profile (th/create-profile* 203 {:is-active true})]
      (cleanup-redis (:id profile))
      ;; 10 failed attempts — no lockout without the flag
      (dotimes [i 10]
        (let [data {::th/type :login-with-password
                    :email (:email profile)
                    :password "wrongpassword"}
              out  (th/command! data)]
          (t/is (th/ex-of-code? (:error out) :wrong-credentials)))))))

(t/deftest login-with-ldap-returns-rate-limit-when-locked
  "Verify that login-with-ldap short-circuits when account is locked,
   without attempting LDAP authentication. Calls the actual command
   function with a mock provider to test the full flow."
  (with-redefs [app.config/flags (flags/parse flags/default [:enable-account-lockout :login-with-ldap])
                ldap/authenticate (constantly nil)]
    (let [profile (th/create-profile* 204 {:is-active true})
          cfg (assoc th/*system* ::ldap/provider {})]
      (cleanup-redis (:id profile))
      ;; Lock account with 5 failed attempts
      (dotimes [i 5]
        (lol/record-failed-attempt! cfg (:id profile)))
      ;; Call actual command function — should be blocked without calling authenticate
      (let [out (th/try-on! (#'ldap-cmd/sm$login-with-ldap
                             cfg
                             {:email (:email profile)
                              :password "wrongpassword"}))]
        (t/is (th/ex-of-type? (:error out) :rate-limit))
        (t/is (th/ex-of-code? (:error out) :account-locked))))))

(t/deftest recover-profile-clears-lockout
  "Verify that clearing attempts (e.g. after password reset) unlocks the account."
  (with-redefs [app.config/flags (flags/parse flags/default [:enable-account-lockout])]
    (let [profile (th/create-profile* 205 {:is-active true})
          cfg th/*system*]
      (cleanup-redis (:id profile))
      ;; Lock the account
      (dotimes [i 5]
        (lol/record-failed-attempt! cfg (:id profile)))
      (t/is (:locked? (lol/locked? cfg (:id profile))))
      ;; Simulate password reset clearing
      (lol/clear-attempts! cfg (:id profile))
      ;; Verify unlocked
      (t/is (false? (:locked? (lol/locked? cfg (:id profile))))))))


