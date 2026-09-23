;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.config-test
  (:require
   [app.config :as cf]
   [clojure.test :as t]))

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
