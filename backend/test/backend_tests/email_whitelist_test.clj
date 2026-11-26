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
          (t/is (= #{"allowed.com" "other.com"} (::email/whitelist cfg)))
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

(t/deftest test-file-ignores-blank-lines
  (let [file (java.io.File/createTempFile "whitelist" ".txt")]
    (try
      (spit file "\n  \t\n# comment\n EXAMPLE.COM \nexample.com\n Other.NET\t\n")
      (with-redefs [cf/flags #{:email-whitelist}
                    cf/get   (fn [k]
                               (case k
                                 :email-domain-whitelist (.getPath file)
                                 :registration-domain-whitelist #{}))]
        (let [domains (ig/init-key ::email/whitelist nil)
              cfg     {::email/whitelist domains}]
          (t/is (= #{"example.com" "other.net"} domains))
          (t/is (true? (whitelist/enabled? cfg)))
          (t/is (true? (whitelist/contains? cfg "user@EXAMPLE.COM")))
          (t/is (false? (whitelist/contains? cfg "user@sub.example.com")))))
      (finally
        (.delete file)))))

(t/deftest test-blank-only-file-disables-whitelist
  (let [file (java.io.File/createTempFile "whitelist" ".txt")]
    (try
      (spit file "\n \t\n# comment\n")
      (with-redefs [cf/flags #{:email-whitelist}
                    cf/get   (fn [k]
                               (case k
                                 :email-domain-whitelist (.getPath file)
                                 :registration-domain-whitelist #{}))]
        (let [domains (ig/init-key ::email/whitelist nil)]
          (t/is (nil? domains))
          (t/is (false? (whitelist/enabled? {::email/whitelist domains})))))
      (finally
        (.delete file)))))

(t/deftest test-blank-only-file-preserves-configured-whitelist
  (let [file (java.io.File/createTempFile "whitelist" ".txt")]
    (try
      (spit file "\n \t\n# comment\n")
      (with-redefs [cf/flags #{:email-whitelist}
                    cf/get   (fn [k]
                               (case k
                                 :email-domain-whitelist (.getPath file)
                                 :registration-domain-whitelist #{"configured.com"}))]
        (let [domains (ig/init-key ::email/whitelist nil)
              cfg     {::email/whitelist domains}]
          (t/is (= #{"configured.com"} domains))
          (t/is (true? (whitelist/enabled? cfg)))
          (t/is (true? (whitelist/contains? cfg "user@configured.com")))))
      (finally
        (.delete file)))))
