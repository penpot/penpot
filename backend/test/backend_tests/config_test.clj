;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.config-test
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [clojure.test :as t]))

(t/deftest tenant-is-normalized-to-lowercase
  (t/testing "an uppercase tenant names the same tenant"
    (t/is (= "acme" (:tenant (#'cf/normalize-tenant {:tenant "ACME"}))))
    (t/is (= "acme" (:tenant (#'cf/normalize-tenant {:tenant "acme"})))))
  (t/testing "a config without a tenant is left untouched"
    (t/is (= {:x 1} (#'cf/normalize-tenant {:x 1}))))
  (t/testing "the validated form is lowercase-only"
    (t/is (not (sm/validate cf/schema:tenant "ACME")))
    (t/is (sm/validate cf/schema:tenant "acme"))))

(t/deftest get-public-uri-normalizes-base
  (t/testing "trailing slash is ensured with and without subpath"
    (doseq [[base expected] [["http://localhost:3449" "http://localhost:3449/"]
                             ["http://localhost:3449/" "http://localhost:3449/"]
                             ["https://example.com/penpot" "https://example.com/penpot/"]
                             ["https://example.com/penpot/" "https://example.com/penpot/"]]]
      (t/testing (str "base " base)
        (with-redefs [cf/config (assoc cf/config :public-uri base)]
          (t/is (= expected (cf/get-public-uri))))))))

(t/deftest get-public-uri-preserves-subpath
  (t/testing "joined segments keep the subpath"
    (with-redefs [cf/config (assoc cf/config :public-uri "https://example.com/penpot")]
      (t/is (= "https://example.com/penpot/assets/by-id/123"
               (cf/get-public-uri "assets/by-id/123")))
      (t/is (= "https://example.com/penpot/api/main/doc"
               (cf/get-public-uri "api/main/doc"))))))

(t/deftest join-uri-joins-arbitrary-base
  (t/testing "segments join onto any base with trailing slash normalization"
    (t/is (= "https://nitrate.example.com/api/teams/123"
             (cf/join-uri "https://nitrate.example.com" "api/teams/123")))
    (t/is (= "https://nitrate.example.com/api/teams/123"
             (cf/join-uri "https://nitrate.example.com/" "api/teams/123")))))

(t/deftest join-uri-rejects-leading-slash
  (t/testing "a leading slash would drop the subpath, so it fails fast"
    (t/is (thrown? AssertionError
                   (cf/join-uri "https://example.com/penpot" "/assets/by-id/123")))))

(t/deftest tenant-allows-only-alphanumeric
  (t/testing "default and plain alphanumeric tenants validate"
    (doseq [tenant ["default" "acme42" "my-company"]]
      (t/is (cf/validate-config (assoc cf/config :tenant tenant)))))
  (t/testing "LIKE wildcards and separators are rejected"
    (doseq [tenant ["my_tenant" "a%b" "a:b" "a b" "" "acme.example.com"]]
      (t/is (not (cf/validate-config (assoc cf/config :tenant tenant)))))))

(t/deftest worker-parallelism-keys
  (t/testing "schema decodes all runner parallelism keys (env strings to int)"
    (let [decoded (cf/decode-config {:worker-default-parallelism "4"
                                     :worker-webhook-parallelism "2"
                                     :worker-cron-parallelism "3"
                                     :worker-binfile-parallelism "5"})]
      (t/is (= 4 (:worker-default-parallelism decoded)))
      (t/is (= 2 (:worker-webhook-parallelism decoded)))
      (t/is (= 3 (:worker-cron-parallelism decoded)))
      (t/is (= 5 (:worker-binfile-parallelism decoded)))))
  (t/testing "lookups resolve configured values (unnamespaced keys, as main uses)"
    (with-redefs [cf/config (assoc cf/config
                                   :worker-default-parallelism 4)]
      (t/is (= 4 (cf/get :worker-default-parallelism 1))))))

(t/deftest jobs-user-ttl
  (t/testing "the env value is decoded as a duration"
    (let [decoded (cf/decode-config {:jobs-user-ttl "72h"})]
      (t/is (= (ct/duration {:days 3}) (:jobs-user-ttl decoded)))))

  (t/testing "the getter resolves the configured value"
    (with-redefs [cf/config (assoc cf/config :jobs-user-ttl (ct/duration {:days 1}))]
      (t/is (= (ct/duration {:days 1}) (cf/get-jobs-user-ttl)))))

  (t/testing "and falls back to a week"
    (with-redefs [cf/config (dissoc cf/config :jobs-user-ttl)]
      (t/is (= (ct/duration {:days 7}) (cf/get-jobs-user-ttl))))))

(t/deftest trusted-origins-decodes-from-env-string
  (t/testing "comma and whitespace separated origins become a set"
    (let [config (cf/decode-config {:tenant "default"
                                    :host "localhost"
                                    :public-uri "http://localhost:3449"
                                    :trusted-origins "https://a.example.com, https://b.example.com https://c.example.com"})]
      (t/is (= #{"https://a.example.com" "https://b.example.com" "https://c.example.com"}
               (:trusted-origins config))))))

(t/deftest trusted-origin?-checks-normalized-allowlist
  (with-redefs [cf/config (assoc cf/config :trusted-origins #{"https://alt.example.com"})]
    (t/testing "exact and normalized matches"
      (t/is (cf/trusted-origin? "https://alt.example.com"))
      (t/is (cf/trusted-origin? "  HTTPS://Alt.Example.com  ")))
    (t/testing "nil, blank, unlisted, subdomain and path variants are rejected"
      (t/is (not (cf/trusted-origin? nil)))
      (t/is (not (cf/trusted-origin? "")))
      (t/is (not (cf/trusted-origin? "https://other.example.com")))
      (t/is (not (cf/trusted-origin? "https://sub.alt.example.com")))
      (t/is (not (cf/trusted-origin? "https://alt.example.com/penpot"))))))

(t/deftest trusted-origin?-without-configuration
  (with-redefs [cf/config (assoc cf/config :trusted-origins nil)]
    (t/is (not (cf/trusted-origin? "https://alt.example.com")))))

(t/deftest with-public-uri-overrides-base
  (let [config {:public-uri "http://localhost:3449" :host "localhost"}]
    (t/is (= "https://alt.example.com"
             (:public-uri (cf/with-public-uri "https://alt.example.com" config))))
    (t/is (= config (cf/with-public-uri nil config)))))

(t/deftest valid-origin?-checks-bare-origin-shape
  (t/testing "accepts scheme://host[:port], including IPv6"
    (doseq [origin ["http://localhost:3449"
                    "https://alt.example.com"
                    "https://alt.example.com:8443"
                    "https://[::1]:3449"
                    "HTTPS://Alt.Example.com"]]
      (t/is (cf/valid-origin? origin) origin)))

  (t/testing "rejects nil, blank, missing scheme, paths, query and trailing slash"
    (doseq [origin [nil "" "   " "alt.example.com" "localhost:3449"
                    "https://alt.example.com/" "https://alt.example.com/penpot"
                    "https://alt.example.com?x=1" "https://alt.example.com#frag"]]
      (t/is (not (cf/valid-origin? origin)) (str origin)))))
