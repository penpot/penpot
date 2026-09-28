;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.auth-login-lockout-test
  (:require
   [app.auth.login-lockout :as lol]
   [app.common.flags :as flags]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once (partial th/init-system))
(t/use-fixtures :each (partial th/init-config [:enable-account-lockout]))

(t/deftest record-failed-attempt-returns-nil-when-disabled
  (with-redefs [app.config/flags (flags/parse flags/default th/default-flags)]
    (let [profile-id (uuid/next)
          result (lol/record-failed-attempt! th/*system* profile-id)]
      (t/is (nil? result)))))

(t/deftest record-failed-attempt-increments-counter
  (let [profile-id (uuid/next)
        r1 (lol/record-failed-attempt! th/*system* profile-id)
        r2 (lol/record-failed-attempt! th/*system* profile-id)
        r3 (lol/record-failed-attempt! th/*system* profile-id)]
    (t/is (map? r1))
    (t/is (= 1 (:count r1)))
    (t/is (false? (:locked? r1)))
    (t/is (= 2 (:count r2)))
    (t/is (false? (:locked? r2)))
    (t/is (= 3 (:count r3)))
    (t/is (false? (:locked? r3)))))

(t/deftest record-failed-attempt-locks-after-threshold
  (let [profile-id (uuid/next)]
    (dotimes [i 4]
      (lol/record-failed-attempt! th/*system* profile-id))
    (let [result (lol/record-failed-attempt! th/*system* profile-id)]
      (t/is (= 5 (:count result)))
      (t/is (:locked? result)))))

(t/deftest locked?-returns-false-below-threshold
  (let [profile-id (uuid/next)]
    (dotimes [i 3]
      (lol/record-failed-attempt! th/*system* profile-id))
    (t/is (false? (:locked? (lol/locked? th/*system* profile-id))))))

(t/deftest locked?-returns-true-at-threshold
  (let [profile-id (uuid/next)]
    (dotimes [i 5]
      (lol/record-failed-attempt! th/*system* profile-id))
    (t/is (:locked? (lol/locked? th/*system* profile-id)))))

(t/deftest locked?-returns-false-when-disabled
  (with-redefs [app.config/flags (flags/parse flags/default th/default-flags)]
    (let [profile-id (uuid/next)]
      (t/is (false? (:locked? (lol/locked? th/*system* profile-id)))))))

(t/deftest clear-attempts-resets-counter
  (let [profile-id (uuid/next)]
    (dotimes [i 5]
      (lol/record-failed-attempt! th/*system* profile-id))
    (t/is (:locked? (lol/locked? th/*system* profile-id)))
    (lol/clear-attempts! th/*system* profile-id)
    (t/is (false? (:locked? (lol/locked? th/*system* profile-id))))))

(t/deftest clear-attempts-is-no-op-when-disabled
  (with-redefs [app.config/flags (flags/parse flags/default th/default-flags)]
    (let [profile-id (uuid/next)]
      (t/is (nil? (lol/clear-attempts! th/*system* profile-id))))))

(t/deftest locked?-returns-false-for-unknown-profile
  (let [profile-id (uuid/next)]
    (t/is (false? (:locked? (lol/locked? th/*system* profile-id))))))

(t/deftest locked?-returns-ttl-when-locked
  (let [profile-id (uuid/next)]
    (dotimes [i 5]
      (lol/record-failed-attempt! th/*system* profile-id))
    (let [result (lol/locked? th/*system* profile-id)]
      (t/is (:locked? result))
      (t/is (pos-int? (:ttl result))
            "ttl should be a positive integer when locked"))))

(t/deftest locked?-returns-no-ttl-when-not-locked
  (let [profile-id (uuid/next)]
    (dotimes [i 2]
      (lol/record-failed-attempt! th/*system* profile-id))
    (let [result (lol/locked? th/*system* profile-id)]
      (t/is (false? (:locked? result)))
      (t/is (nil? (:ttl result))
            "ttl should be nil when not locked"))))

(t/deftest locked?-expires-after-window
  (let [profile-id (uuid/next)
        start (ct/inst "2026-01-01T00:00:00Z")]
    ;; Record 5 attempts at t=0 → locked
    (binding [ct/*clock* (ct/fixed-clock start)]
      (dotimes [i 5]
        (lol/record-failed-attempt! th/*system* profile-id)))
    ;; At t=5min → still locked
    (binding [ct/*clock* (ct/fixed-clock (ct/plus start {:minutes 5}))]
      (let [result (lol/locked? th/*system* profile-id)]
        (t/is (:locked? result) "should still be locked at 5min")
        (t/is (pos-int? (:ttl result)))))
    ;; At t=16min → expired, not locked
    (binding [ct/*clock* (ct/fixed-clock (ct/plus start {:minutes 16}))]
      (let [result (lol/locked? th/*system* profile-id)]
        (t/is (false? (:locked? result)) "should be unlocked after window expires")
        (t/is (nil? (:ttl result)))))))

(t/deftest record-failed-attempt-resets-window-on-expiry
  (let [profile-id (uuid/next)
        start (ct/inst "2026-01-01T00:00:00Z")]
    ;; Record 2 attempts at t=0
    (binding [ct/*clock* (ct/fixed-clock start)]
      (dotimes [i 2]
        (lol/record-failed-attempt! th/*system* profile-id)))
    ;; At t=20min (after 15min window), counter should reset
    (binding [ct/*clock* (ct/fixed-clock (ct/plus start {:minutes 20}))]
      (let [result (lol/record-failed-attempt! th/*system* profile-id)]
        (t/is (= 1 (:count result)) "count should reset to 1 after window expires")
        (t/is (false? (:locked? result)))))))

(t/deftest locked?-ttl-decreases-over-time
  (let [profile-id (uuid/next)
        start (ct/inst "2026-01-01T00:00:00Z")]
    ;; Record 5 attempts at t=0 → locked
    (binding [ct/*clock* (ct/fixed-clock start)]
      (dotimes [i 5]
        (lol/record-failed-attempt! th/*system* profile-id)))
    ;; At t=5min → ttl should be ~600s (10min remaining)
    (binding [ct/*clock* (ct/fixed-clock (ct/plus start {:minutes 5}))]
      (let [result (lol/locked? th/*system* profile-id)]
        (t/is (:locked? result))
        (t/is (<= 590 (:ttl result) 600) "ttl should be ~600s at 5min")))
    ;; At t=14min → ttl should be ~60s
    (binding [ct/*clock* (ct/fixed-clock (ct/plus start {:minutes 14}))]
      (let [result (lol/locked? th/*system* profile-id)]
        (t/is (:locked? result))
        (t/is (<= 50 (:ttl result) 70) "ttl should be ~60s at 14min")))))
