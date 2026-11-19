;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.email-whitelist-test
  (:require
   [app.config :as cf]
   [app.email :as-alias email]
   [app.email.whitelist :as whitelist]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig]))

(defn- with-list-file
  [lines f]
  (let [file (java.io.File/createTempFile "penpot-email-whitelist-" ".txt")]
    (try
      (spit file (str/join "\n" lines))
      (f (str file))
      (finally
        (io/delete-file file)))))

(defn- init-whitelist
  []
  (ig/init-key ::email/whitelist {}))

(t/deftest test-enabled-predicate
  (t/is (false? (whitelist/enabled? {})))
  (t/is (true? (whitelist/enabled? {::email/whitelist #{"allowed.com"}}))))

(t/deftest test-init-reads-file-and-ignores-comments
  (with-list-file ["# ignored" "  ALLOWED.COM  " "" "Other.com"]
    (fn [path]
      (with-redefs [cf/flags (conj cf/flags :email-whitelist)
                    cf/config (assoc cf/config
                                     :registration-domain-whitelist #{}
                                     :email-domain-whitelist path)]
        (let [cfg {::email/whitelist (init-whitelist)}]
          ;; Blank lines are normalized to "" and kept by the provider.
          (t/is (= #{"allowed.com" "other.com" ""} (::email/whitelist cfg)))
          (t/is (true? (whitelist/enabled? cfg)))
          (t/is (true? (whitelist/contains? cfg "user@ALLOWED.com")))
          (t/is (false? (whitelist/contains? cfg "user@ignored"))))))))

(t/deftest test-init-merges-config-and-file
  (with-list-file ["FromFile.COM"]
    (fn [path]
      (with-redefs [cf/flags (conj cf/flags :email-whitelist)
                    cf/config (assoc cf/config
                                     :registration-domain-whitelist #{"from-config.com"}
                                     :email-domain-whitelist path)]
        (t/is (= #{"from-config.com" "fromfile.com"}
                 (::email/whitelist {::email/whitelist (init-whitelist)})))))))

(t/deftest test-init-without-flag-uses-config-only
  (with-list-file ["FromFile.COM"]
    (fn [path]
      (with-redefs [cf/flags (disj cf/flags :email-whitelist)
                    cf/config (assoc cf/config
                                     :registration-domain-whitelist #{"from-config.com"}
                                     :email-domain-whitelist path)]
        (t/is (= #{"from-config.com"}
                 (::email/whitelist {::email/whitelist (init-whitelist)})))))))

(t/deftest test-init-with-missing-file
  (with-redefs [cf/flags (conj cf/flags :email-whitelist)
                cf/config (assoc cf/config
                                 :registration-domain-whitelist #{}
                                 :email-domain-whitelist
                                 (str (System/getProperty "java.io.tmpdir")
                                      "/penpot-missing-"
                                      (random-uuid)))]
    (let [cfg {::email/whitelist (init-whitelist)}]
      (t/is (nil? (::email/whitelist cfg)))
      (t/is (false? (whitelist/enabled? cfg))))))
