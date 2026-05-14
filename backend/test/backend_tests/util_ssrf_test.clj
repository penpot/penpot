;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.util-ssrf-test
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.http.client :as http]
   [app.util.ssrf :as ssrf]
   [clojure.test :as t]))

(t/deftest validate-url-allows-public-https
  (t/is (true? (ssrf/safe-url? "https://example.com/foo")))
  (t/is (true? (ssrf/safe-url? "https://example.com:8080/path?q=1"))))

(t/deftest validate-url-allows-public-http
  (t/is (true? (ssrf/safe-url? "http://example.com/foo"))))

(t/deftest validate-url-blocks-disallowed-schemes
  (t/is (false? (ssrf/safe-url? "file:///etc/passwd")))
  (t/is (false? (ssrf/safe-url? "gopher://example.com")))
  (t/is (false? (ssrf/safe-url? "ftp://example.com")))
  (t/is (false? (ssrf/safe-url? "dict://example.com")))
  (t/is (false? (ssrf/safe-url? "data:text/html,<h1>hi</h1>")))
  (t/is (false? (ssrf/safe-url? "jar:http://example.com!/foo")))
  (t/is (false? (ssrf/safe-url? "javascript:alert(1)"))))

(t/deftest validate-url-blocks-loopback
  (t/is (false? (ssrf/safe-url? "http://127.0.0.1/foo")))
  (t/is (false? (ssrf/safe-url? "http://127.0.0.2/foo")))
  (t/is (false? (ssrf/safe-url? "http://[::1]/foo"))))

(t/deftest validate-url-blocks-any-local
  (t/is (false? (ssrf/safe-url? "http://0.0.0.0/foo")))
  (t/is (false? (ssrf/safe-url? "http://[::]/foo"))))

(t/deftest validate-url-blocks-link-local
  (t/is (false? (ssrf/safe-url? "http://169.254.169.254/latest/meta-data/")))
  (t/is (false? (ssrf/safe-url? "http://169.254.1.1/foo")))
  (t/is (false? (ssrf/safe-url? "http://[fe80::1]/foo"))))

(t/deftest validate-url-blocks-site-local
  (t/is (false? (ssrf/safe-url? "http://10.0.0.1/foo")))
  (t/is (false? (ssrf/safe-url? "http://172.16.0.1/foo")))
  (t/is (false? (ssrf/safe-url? "http://192.168.1.1/foo"))))

(t/deftest validate-url-blocks-cloud-metadata
  (t/is (false? (ssrf/safe-url? "http://169.254.169.254/latest/meta-data/iam/security-credentials/role")))
  (t/is (false? (ssrf/safe-url? "http://[fd00:ec2::254]/foo"))))

(t/deftest validate-url-blocks-carrier-grade-nat
  (t/is (false? (ssrf/safe-url? "http://100.64.0.1/foo")))
  (t/is (false? (ssrf/safe-url? "http://100.127.255.255/foo")))
  ;; Just outside the range should be allowed (but may be blocked by DNS resolution failing)
  ;; We test boundary: 100.63.255.255 is outside 100.64.0.0/10
  ;; But we can't easily test the "allowed" side without DNS, so we test the blocked side.

  ;; Test RFC reserved ranges
  (t/is (false? (ssrf/safe-url? "http://240.0.0.1/foo")))
  (t/is (false? (ssrf/safe-url? "http://255.255.255.255/foo"))))

(t/deftest validate-url-blocks-ipv6-ula
  (t/is (false? (ssrf/safe-url? "http://[fd00::1]/foo")))
  (t/is (false? (ssrf/safe-url? "http://[fc00::1]/foo"))))

(t/deftest validate-url-blocks-encoded-loopback
  ;; Decimal encoding of 127.0.0.1 = 2130706433
  ;; InetAddress normalizes this to 127.0.0.1
  (t/is (false? (ssrf/safe-url? "http://2130706433/foo")))
  ;; Hex encoding 0x7f000001
  (t/is (false? (ssrf/safe-url? "http://0x7f000001/foo"))))

(t/deftest validate-url-blocks-ipv4-mapped-loopback
  (t/is (false? (ssrf/safe-url? "http://[::ffff:127.0.0.1]/foo"))))

(t/deftest validate-url-blocks-multicast
  (t/is (false? (ssrf/safe-url? "http://224.0.0.1/foo"))))

(t/deftest validate-url-blocks-missing-scheme
  (t/is (false? (ssrf/safe-url? "example.com/foo")))
  (t/is (false? (ssrf/safe-url? ""))))

(t/deftest validate-url-blocks-missing-host
  (t/is (false? (ssrf/safe-url? "http:///path")))
  (t/is (false? (ssrf/safe-url? "http://"))))

(t/deftest validate-url-resolves-dns
  ;; DNS-resolved internal: we use with-redefs to simulate
  (let [original ssrf/resolve-host]
    (with-redefs [ssrf/resolve-host
                  (fn [hostname]
                    (if (= hostname "evil.internal")
                      (into-array java.net.InetAddress
                                  [(java.net.InetAddress/getByName "127.0.0.1")])
                      (original hostname)))]
      (t/is (false? (ssrf/safe-url? "http://evil.internal/foo")))
      ;; A hostname that fails DNS resolution
      (t/is (false? (ssrf/safe-url? "http://nonexistent.invalid/foo"))))))

(t/deftest validate-url-dns-all-addresses-must-be-safe
  ;; If a hostname resolves to both a public and a private IP, it must be blocked
  (let [original ssrf/resolve-host]
    (with-redefs [ssrf/resolve-host
                  (fn [hostname]
                    (if (= hostname "split-brain.example")
                      (into-array java.net.InetAddress
                                  [(java.net.InetAddress/getByName "1.1.1.1")
                                   (java.net.InetAddress/getByName "127.0.0.1")])
                      (original hostname)))]
      (t/is (false? (ssrf/safe-url? "http://split-brain.example/foo"))))))

(t/deftest validate-url-allowlist-override
  (let [original-get cf/get]
    (with-redefs [cf/get (fn [key & args]
                           (if (= key :ssrf-allowed-hosts)
                             #{"localhost"}
                             (apply original-get key args)))]
      ;; localhost resolves to 127.0.0.1 which would normally be blocked
      (t/is (true? (ssrf/safe-url? "http://localhost:6060/foo"))))))

(t/deftest validate-url-extra-cidrs
  (binding [ssrf/extra-blocked-cidrs #{(ssrf/parse-cidr "203.0.113.0/24")}]
    (t/is (false? (ssrf/safe-url? "http://203.0.113.1/foo")))))

(t/deftest validate-url-throw-on-blocked
  (try
    (ssrf/validate-uri "http://127.0.0.1/foo")
    (t/is false "should have thrown")
    (catch Exception e
      (t/is (= :validation (:type (ex-data e))))
      (t/is (= :ssrf-blocked-target (:code (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; http/req automatic SSRF validation
;; ---------------------------------------------------------------------------

(t/deftest http-req-validates-ssrf-by-default
  ;; `http/req` should invoke ssrf/validate-uri before sending the request.
  ;; We verify this by checking that a blocked URI raises an SSRF error
  ;; without ever reaching the network (validate-uri throws first).
  (try
    (http/req {} {:method :get :uri "http://127.0.0.1/secret"})
    (t/is false "should have thrown an SSRF error")
    (catch Exception e
      (t/is (= :ssrf-blocked-target (:code (ex-data e)))))))

(t/deftest http-req-skip-ssrf-check-bypasses-validation
  ;; When :skip-ssrf-check? true is passed, ssrf/validate-uri must NOT be
  ;; called. We verify by patching validate-uri to record whether it was called.
  (let [called? (atom false)]
    (with-redefs [ssrf/validate-uri (fn [_] (reset! called? true))]
      ;; The request will fail at the network level (no real server), but that's
      ;; fine — we only care that validate-uri was not called beforehand.
      (try
        (http/req {} {:method :get :uri "http://127.0.0.1/secret"} {:skip-ssrf-check? true})
        (catch Exception _))
      (t/is (false? @called?) "validate-uri should not be called when :skip-ssrf-check? is true"))))

(t/deftest http-req-with-redirects-validates-ssrf-by-default
  ;; req-with-redirects must also validate the initial URI automatically.
  (try
    (http/req-with-redirects {} {:method :get :uri "http://10.0.0.1/internal"})
    (t/is false "should have thrown an SSRF error")
    (catch Exception e
      (t/is (= :ssrf-blocked-target (:code (ex-data e)))))))

(t/deftest http-req-with-redirects-skip-ssrf-check-bypasses-validation
  (let [called? (atom false)]
    (with-redefs [ssrf/validate-uri (fn [_] (reset! called? true))]
      (try
        (http/req-with-redirects {} {:method :get :uri "http://10.0.0.1/internal"} {:skip-ssrf-check? true})
        (catch Exception _))
      (t/is (false? @called?) "validate-uri should not be called when :skip-ssrf-check? is true"))))
