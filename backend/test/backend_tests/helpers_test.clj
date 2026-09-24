;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.helpers-test
  "Focused unit tests for the shared test request constructor.

  `make-dummy-request` is called with three styles across the suite
  (no arguments, a single options map, and keyword arguments). These
  tests pin that contract so a future redefinition or reordering of
  the helper fails loudly here instead of as a flood of unrelated
  test errors."
  (:require
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.request :as yreq]))

(defn- utf8-bytes
  ^bytes
  [^String s]
  (.getBytes s "UTF-8"))

;; --- DEFAULTS (ZERO ARGUMENTS)

(t/deftest make-dummy-request-zero-args-uses-defaults
  (let [req (th/make-dummy-request)]
    (t/is (instance? backend_tests.helpers.DummyRequest req))
    (t/is (= :get (yreq/method req)))
    (t/is (= "/test" (yreq/path req)))
    (t/is (= :http (yreq/scheme req)))
    (t/is (= "HTTP/1.1" (yreq/protocol req)))
    (t/is (= "127.0.0.1" (yreq/remote-addr req)))
    (t/is (= "test" (yreq/server-name req)))
    (t/is (= 0 (yreq/server-port req)))
    (t/is (nil? (yreq/query req)))
    (t/is (nil? (yreq/body req)))))

;; --- SINGLE OPTIONS MAP

(t/deftest make-dummy-request-single-map-arg-overrides-given-keys
  (let [req (th/make-dummy-request {:method :post
                                    :headers {"x-test" "yes"}
                                    :path "/custom"
                                    :query "a=1"})]
    (t/is (= :post (yreq/method req)))
    (t/is (= "yes" (yreq/get-header req "x-test")))
    (t/is (= "/custom" (yreq/path req)))
    (t/is (= "a=1" (yreq/query req)))))

(t/deftest make-dummy-request-single-map-arg-keeps-defaults-for-omitted-keys
  (let [req (th/make-dummy-request {:method :post})]
    (t/is (= "/test" (yreq/path req)))
    (t/is (= :http (yreq/scheme req)))
    (t/is (= "test" (yreq/server-name req)))))

;; --- KEYWORD ARGUMENTS

(t/deftest make-dummy-request-keyword-args-override-given-keys
  (let [req (th/make-dummy-request :method :delete
                                   :path "/by-id"
                                   :server-port 8080)]
    (t/is (= :delete (yreq/method req)))
    (t/is (= "/by-id" (yreq/path req)))
    (t/is (= 8080 (yreq/server-port req)))))

;; --- BODY

(t/deftest make-dummy-request-string-body-bytes-wrapped-in-stream
  (let [req (th/make-dummy-request :method :post :body-bytes "hello")]
    (t/is (instance? java.io.ByteArrayInputStream (yreq/body req)))
    (t/is (= "hello" (slurp (yreq/body req))))))

(t/deftest make-dummy-request-byte-array-body-bytes-wrapped-in-stream
  (let [req (th/make-dummy-request :body-bytes (utf8-bytes "raw"))]
    (t/is (= "raw" (slurp (yreq/body req))))))

(t/deftest make-dummy-request-body-stream-takes-precedence-over-body-bytes
  (let [stream (java.io.ByteArrayInputStream. (utf8-bytes "stream"))
        req    (th/make-dummy-request :body-stream stream :body-bytes "bytes")]
    (t/is (identical? stream (yreq/body req)))
    (t/is (= "stream" (slurp (yreq/body req))))))

;; --- COOKIES

(t/deftest make-dummy-request-cookie-readable-via-get-cookie
  (let [req (th/make-dummy-request {:cookies {"auth-token" "abc"}})]
    (t/is (= {:value "abc"} (yreq/get-cookie req "auth-token")))))

(t/deftest make-dummy-request-missing-cookie-returns-nil-value
  (t/is (= {:value nil} (yreq/get-cookie (th/make-dummy-request) "missing"))))

;; --- RPC PARAMS: REQUEST METADATA

(t/deftest prepare-rpc-params-preserves-supplied-request
  (let [supplied {:headers {"x-frontend-version" "1.2.3"}
                  :app.http/auth-key-id :admin-console}
        params   (#'th/prepare-rpc-params
                  (with-meta {::th/type :dummy
                              :events [{:name "event"}]}
                    {:app.http/request supplied}))
        request  (:app.http/request (meta params))]
    (t/is (= "1.2.3" (yreq/get-header request "x-frontend-version")))
    (t/is (= :admin-console (:app.http/auth-key-id request)))
    (t/is (= {:events [{:name "event"}]} (:params request)))))

(t/deftest prepare-rpc-params-builds-dummy-request-without-metadata
  (let [params  (#'th/prepare-rpc-params {::th/type :dummy
                                          :events [{:name "event"}]})
        request (:app.http/request (meta params))]
    (t/is (instance? backend_tests.helpers.DummyRequest request))
    (t/is (= {:events [{:name "event"}]} (:params request)))))

(t/deftest prepare-rpc-params-falls-back-to-dummy-for-non-map-request
  (let [supplied (reify yreq/IRequest
                   (get-header [_ _] nil))
        params   (#'th/prepare-rpc-params
                  (with-meta {::th/type :dummy
                              :events [{:name "event"}]}
                    {:app.http/request supplied}))
        request  (:app.http/request (meta params))]
    (t/is (instance? backend_tests.helpers.DummyRequest request))
    (t/is (= {:events [{:name "event"}]} (:params request)))))
