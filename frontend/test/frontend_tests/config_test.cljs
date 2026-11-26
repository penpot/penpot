;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.config-test
  (:require
   [app.common.flags :as flags]
   [app.config :as cf]
   [cljs.test :as t :include-macros true]))

(def ^:private apply-devenv-defaults @#'cf/apply-devenv-defaults)
(def ^:private resolve-public-uri @#'cf/resolve-public-uri)

(t/deftest public-uri-uses-only-a-configured-alternate-origin
  (let [base    "https://primary.example:8443/penpot"
        origins " https://other.example,\n HTTPS://Alias.Example:443 https://alias.example:9443 "
        resolve (fn [origin]
                  (str (resolve-public-uri base origin (js/btoa origins))))]
    (t/is (= "https://alias.example/penpot/" (resolve "https://alias.example")))
    (t/is (= "https://alias.example:9443/penpot/" (resolve "https://alias.example:9443")))
    (t/is (= "https://primary.example:8443/penpot/" (resolve "https://alias.example:8443")))
    (t/is (= "https://primary.example:8443/penpot/" (resolve "https://primary.example:8443")))
    (t/is (= "https://primary.example:8443/penpot/" (resolve "https://unlisted.example")))))

(t/deftest public-uri-supports-http-and-ipv6-aliases
  (let [base    "https://primary.example/penpot/"
        origins (js/btoa "http://localhost:80 https://[::1]:9443")]
    (t/is (= "http://localhost/penpot/"
             (str (resolve-public-uri base "http://localhost" origins))))
    (t/is (= "https://[::1]:9443/penpot/"
             (str (resolve-public-uri base "https://[::1]:9443" origins))))))

(t/deftest public-uri-ignores-malformed-alternate-origins
  (let [base "https://primary.example/penpot/"]
    (doseq [[origin value] [["https://alias.example" "https://alias.example/"]
                            ["https://alias.example" "https://alias.example?"]
                            ["https://alias.example" "https://alias.example#"]
                            ["https://alias.example" "https://user@alias.example"]
                            ["https://alias.example" "https://alias.example:"]
                            ["https://alias.example" "https://alias.example:65536"]
                            ["https://alias.example" "https://*.example"]
                            ["https://alias.example" "https://alias.example\\"]
                            ["https://alias.example" "https://%61lias.example"]
                            ["https://a.-example" "https://a.-example"]
                            ["https://a.1example" "https://a.1example"]
                            ["http://127.0.0.1" "http://127.1"]
                            ["http://127.0.0.1" "http://0x7f000001"]]]
      (t/is (= base (str (resolve-public-uri base origin (js/btoa value)))) value))
    (t/is (= base (str (resolve-public-uri base "https://alias.example" "%"))))
    (t/is (= base (str (resolve-public-uri base "https://alias.example" nil))))
    (t/is (= "http://localhost:9001/"
             (str (resolve-public-uri nil "http://localhost:9001" nil))))))

(t/deftest devenv-default-disables-admin-console
  (t/testing "in devenv, without user flags, the admin-console is disabled by default"
    (t/is (not (contains? (flags/parse flags/default (apply-devenv-defaults true []))
                          :admin-console))))
  (t/testing "in devenv, the user can re-enable it with enable-admin-console"
    (t/is (contains? (flags/parse flags/default (apply-devenv-defaults true [:enable-admin-console]))
                     :admin-console)))
  (t/testing "in devenv, the user's disable-admin-console is idempotent"
    (t/is (not (contains? (flags/parse flags/default (apply-devenv-defaults true [:disable-admin-console]))
                          :admin-console))))
  (t/testing "outside devenv, no default is injected and the flags defaults apply"
    (t/is (contains? (flags/parse flags/default (apply-devenv-defaults false []))
                     :admin-console))))
