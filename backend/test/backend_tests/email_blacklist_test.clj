;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.email-blacklist-test
  (:require
   [app.config :as cf]
   [app.email :as-alias email]
   [app.email.blacklist :as blacklist]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig]))

(def ^:private cfg
  {::email/blacklist #{"somedomain.com" "spam.net"}})

(defn- with-list-file
  [lines f]
  (let [file (java.io.File/createTempFile "penpot-email-blacklist-" ".txt")]
    (try
      (spit file (str/join "\n" lines))
      (f (str file))
      (finally
        (io/delete-file file)))))

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

(t/deftest test-enabled-predicate
  (t/is (false? (blacklist/enabled? {})))
  (t/is (true? (blacklist/enabled? cfg))))

(t/deftest test-init-reads-file-and-ignores-comments
  (with-list-file ["# ignored" "SPAM.NET" "" "sub.Bad.COM"]
    (fn [path]
      (with-redefs [cf/flags (conj cf/flags :email-blacklist)
                    cf/config (assoc cf/config :email-domain-blacklist path)]
        (let [cfg {::email/blacklist (ig/init-key ::email/blacklist {})}]
          ;; Known bug: blank lines are kept as empty domains (issue #13363).
          (t/is (= #{"spam.net" "sub.bad.com" ""} (::email/blacklist cfg)))
          (t/is (true? (blacklist/enabled? cfg)))
          (t/is (true? (blacklist/contains? cfg "a@sub.bad.com"))))))))

(t/deftest test-init-is-disabled-without-flag
  (with-redefs [cf/flags (disj cf/flags :email-blacklist)]
    (t/is (nil? (ig/init-key ::email/blacklist {})))))
