;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.email-blacklist-test
  (:require
   [app.email :as-alias email]
   [app.email.blacklist :as blacklist]
   [clojure.test :as t]))

(def ^:private cfg
  {::email/blacklist #{"somedomain.com" "spam.net"}})

(t/deftest test-exact-domain-match
  (t/is (true?  (blacklist/contains? cfg "user@somedomain.com")))
  (t/is (true?  (blacklist/contains? cfg "user@spam.net")))
  (t/is (false? (blacklist/contains? cfg "user@legit.com"))))

(t/deftest test-subdomain-match
  (t/is (true?  (blacklist/contains? cfg "user@sub.somedomain.com")))
  (t/is (true?  (blacklist/contains? cfg "user@a.b.somedomain.com")))
  ;; A domain that merely contains the blacklisted string but is not a
  ;; subdomain must NOT be rejected.
  (t/is (false? (blacklist/contains? cfg "user@notsomedomain.com"))))

(t/deftest test-case-insensitive
  (t/is (true? (blacklist/contains? cfg "user@SOMEDOMAIN.COM")))
  (t/is (true? (blacklist/contains? cfg "user@Sub.SomeDomain.Com"))))

(t/deftest test-non-blacklisted-domain
  (t/is (false? (blacklist/contains? cfg "user@example.com")))
  (t/is (false? (blacklist/contains? cfg "user@sub.legit.com"))))
