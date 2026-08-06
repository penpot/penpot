;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.http :as http]
   [app.http.access-token :as actoken]
   [app.http.session :as session]
   [app.rpc :as rpc]
   [app.rpc.cond :as cond]
   [app.rpc.helpers :as rph]
   [app.util.inet :as inet]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as yres]))

(defn- echo-handler
  "Handler that returns the data map it receives, for inspection."
  [data]
  data)

(defn- make-handler
  "Creates an RPC handler with a single :test-method backed by echo-handler.
  Extra methods are merged in; each value is [metadata handler-fn]."
  ([] (make-handler {}))
  ([extra-methods]
   (let [methods (merge {:test-method [{} echo-handler]}
                        extra-methods)]
     (rpc/make-rpc-handler methods))))

(defn- make-request
  "Builds a dummy request. Extra qualified-keyword keys are merged onto it."
  [& {:keys [method path-params headers extras]
      :or   {method :post
             path-params {:method-name "test-method"}
             headers {}
             extras {}}}]
  (-> (th/make-dummy-request {:method method
                              :headers headers})
      (assoc :path-params path-params
             :params {})
      (merge extras)))

(defn- call
  "Invokes handler with a built request, returns the full response map."
  ([handler opts]
   (handler (make-request opts)))
  ([handler]
   (call handler {})))

(defn- call-body
  "Invokes handler with a built request, returns ::yres/body from response."
  ([handler opts]
   (::yres/body (call handler opts)))
  ([handler]
   (::yres/body (call handler))))

(defn- call-ex
  "Invokes handler, catches and returns the exception."
  ([handler opts]
   (try (call handler opts) (catch Exception e e)))
  ([handler]
   (call-ex handler {})))

(def fixed-time (ct/now))
(def fixed-uuid (uuid/custom 11111111 22222222))

;; --- BASIC HANDLER DISPATCH

(t/deftest handler-receives-handler-name
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (= "test-method" (::rpc/handler-name data))))))

(t/deftest handler-receives-ip-addr
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "10.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (= "10.0.0.1" (::rpc/ip-addr data))))))

(t/deftest handler-receives-request-at
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (= fixed-time (::rpc/request-at data))))))

(t/deftest handler-receives-request-id
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (= fixed-uuid (::rpc/request-id data))))))

(t/deftest unknown-handler-name-raises-not-found
  (let [handler (make-handler)
        ex      (call-ex handler {:path-params {:method-name "unknown"}})]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :not-found))))

;; --- HANDLER-NAME EDGE CASES

(t/deftest nil-path-params-falls-to-default-handler
  (let [handler (make-handler)
        request (make-request {:path-params nil})
        ex      (try (handler request) (catch Exception e e))]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :not-found))))

(t/deftest missing-method-name-falls-to-default-handler
  (let [handler (make-handler)
        request (make-request {:path-params {}})
        ex      (try (handler request) (catch Exception e e))]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :not-found))))

(t/deftest empty-method-name-falls-to-default-handler
  (let [handler (make-handler)
        request (make-request {:path-params {:method-name ""}})
        ex      (try (handler request) (catch Exception e e))]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :not-found))))

(t/deftest get-with-exact-get-prefix-no-suffix-allowed
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:get- [{} echo-handler]})
          data    (call-body handler
                             {:method :get
                              :path-params {:method-name "get-"}})]
      (t/is (= "get-" (::rpc/handler-name data))))))

;; --- AUTH: SESSION

(t/deftest session-auth-profile-id
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::session/profile-id pid}})]
      (t/is (= pid (::rpc/profile-id data))))))

(t/deftest session-auth-type
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::session/profile-id pid}})]
      (t/is (= :session (::rpc/auth-type data))))))

(t/deftest session-auth-no-token-perms
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::session/profile-id pid}})]
      (t/is (not (contains? data ::rpc/token-perms))))))

(t/deftest nil-session-profile-id-not-used
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:extras {::session/profile-id nil}})]
      (t/is (not (contains? data ::rpc/profile-id)))
      (t/is (not (contains? data ::rpc/auth-type))))))

;; --- AUTH: TOKEN

(t/deftest token-auth-profile-id
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid}})]
      (t/is (= pid (::rpc/profile-id data))))))

(t/deftest token-auth-type
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid}})]
      (t/is (= :token (::rpc/auth-type data))))))

(t/deftest token-auth-with-perms
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid
                                       ::actoken/perms #{"view" "edit"}}})]
      (t/is (= #{"view" "edit"} (::rpc/token-perms data))))))

(t/deftest token-auth-perms-default-to-empty-set
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid}})]
      (t/is (= #{} (::rpc/token-perms data))))))

(t/deftest token-auth-perms-coerced-from-vector
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid
                                       ::actoken/perms ["view" "edit"]}})]
      (t/is (set? (::rpc/token-perms data)))
      (t/is (= #{"view" "edit"} (::rpc/token-perms data))))))

(t/deftest token-auth-perms-coerced-from-list
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid
                                       ::actoken/perms (list "view" "edit")}})]
      (t/is (set? (::rpc/token-perms data)))
      (t/is (= #{"view" "edit"} (::rpc/token-perms data))))))

(t/deftest token-auth-with-nil-perms-defaults-to-empty-set
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid
                                       ::actoken/perms nil}})]
      (t/is (= #{} (::rpc/token-perms data))))))

(t/deftest token-auth-with-empty-perms
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id pid
                                       ::actoken/perms #{}}})]
      (t/is (= #{} (::rpc/token-perms data))))))

(t/deftest nil-actoken-profile-id-not-used
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:extras {::actoken/profile-id nil}})]
      (t/is (not (contains? data ::rpc/profile-id)))
      (t/is (not (contains? data ::rpc/auth-type))))))

;; --- AUTH: SHARED KEY

(t/deftest key-auth-profile-id-is-zero
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:extras {::http/auth-key-id "some-key"}})]
      (t/is (= uuid/zero (::rpc/profile-id data))))))

(t/deftest key-auth-key-id-in-data
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:extras {::http/auth-key-id "some-key"}})]
      (t/is (= "some-key" (::rpc/auth-key-id data))))))

(t/deftest nil-auth-key-id-not-used
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:extras {::http/auth-key-id nil}})]
      (t/is (not (contains? data ::rpc/profile-id)))
      (t/is (not (contains? data ::rpc/auth-key-id))))))

;; --- AUTH: UNAUTHENTICATED

(t/deftest unauthenticated-no-profile-id
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (not (contains? data ::rpc/profile-id))))))

(t/deftest unauthenticated-no-auth-type
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (not (contains? data ::rpc/auth-type))))))

;; --- AUTH: PRIORITY

(t/deftest session-overrides-token
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [session-pid (uuid/custom 1 1)
          token-pid   (uuid/custom 2 2)
          handler     (make-handler)
          data        (call-body handler
                                 {:extras {::session/profile-id session-pid
                                           ::actoken/profile-id token-pid}})]
      (t/is (= session-pid (::rpc/profile-id data)))
      (t/is (= :session (::rpc/auth-type data))))))

(t/deftest session-overrides-key
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [session-pid (uuid/custom 1 1)
          handler     (make-handler)
          data        (call-body handler
                                 {:extras {::session/profile-id session-pid
                                           ::http/auth-key-id "key-id"}})]
      (t/is (= session-pid (::rpc/profile-id data)))
      (t/is (= :session (::rpc/auth-type data)))
      (t/is (= "key-id" (::rpc/auth-key-id data))))))

(t/deftest token-overrides-key
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [token-pid (uuid/custom 2 2)
          handler   (make-handler)
          data      (call-body handler
                               {:extras {::actoken/profile-id token-pid
                                         ::http/auth-key-id "key-id"}})]
      (t/is (= token-pid (::rpc/profile-id data)))
      (t/is (= :token (::rpc/auth-type data)))
      (t/is (= "key-id" (::rpc/auth-key-id data))))))

(t/deftest session-overrides-token-and-key
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [session-pid (uuid/custom 1 1)
          token-pid   (uuid/custom 2 2)
          handler     (make-handler)
          data        (call-body handler
                                 {:extras {::session/profile-id session-pid
                                           ::actoken/profile-id token-pid
                                           ::http/auth-key-id "key-id"}})]
      (t/is (= session-pid (::rpc/profile-id data)))
      (t/is (= :session (::rpc/auth-type data))))))

;; --- REQUEST METADATA: SESSION-ID

(t/deftest session-id-parsed-from-header
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [sid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:headers {"x-session-id" (str sid)}})]
      (t/is (= sid (::rpc/session-id data))))))

(t/deftest session-id-nil-when-no-header
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (nil? (::rpc/session-id data))))))

(t/deftest session-id-nil-when-invalid-uuid
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:headers {"x-session-id" "not-a-uuid"}})]
      (t/is (nil? (::rpc/session-id data))))))

(t/deftest session-id-nil-for-empty-string
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:headers {"x-session-id" ""}})]
      (t/is (nil? (::rpc/session-id data))))))

(t/deftest session-id-parses-uppercase-uuid
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [sid     (uuid/next)
          handler (make-handler)
          upper   (clojure.string/upper-case (str sid))
          data    (call-body handler
                             {:headers {"x-session-id" upper}})]
      (t/is (= sid (::rpc/session-id data))))))

;; --- REQUEST METADATA: ETAG / COND KEY

(t/deftest cond-key-from-if-none-match
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler
                             {:headers {"if-none-match" "W/\"abc123\""}})]
      (t/is (= "W/\"abc123\"" (::cond/key data))))))

(t/deftest cond-key-nil-when-no-header
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler)
          data    (call-body handler)]
      (t/is (nil? (::cond/key data))))))

;; --- REQUEST METADATA: META

(t/deftest data-meta-contains-request
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [request (make-request {})
          handler (make-handler)
          data    (call-body handler {})]
      (t/is (= request (::http/request (meta data)))))))

;; --- DATA COMPLETENESS

(t/deftest data-contains-all-expected-keys
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [pid     (uuid/next)
          handler (make-handler)
          data    (call-body handler
                             {:extras {::session/profile-id pid
                                       ::http/auth-key-id "key"}})]
      (t/is (contains? data ::rpc/handler-name))
      (t/is (contains? data ::rpc/ip-addr))
      (t/is (contains? data ::rpc/request-at))
      (t/is (contains? data ::rpc/request-id))
      (t/is (contains? data ::rpc/session-id))
      (t/is (contains? data ::cond/key))
      (t/is (contains? data ::rpc/profile-id))
      (t/is (contains? data ::rpc/auth-type))
      (t/is (contains? data ::rpc/auth-key-id)))))

;; --- GET/HEAD RESTRICTION

(t/deftest get-restriction-non-get-handler
  (let [handler (make-handler)
        ex      (call-ex handler
                         {:method :get
                          :path-params {:method-name "foo"}})]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :restriction))
    (t/is (th/ex-of-code? ex :method-not-allowed))))

(t/deftest head-restriction-non-get-handler
  (let [handler (make-handler)
        ex      (call-ex handler
                         {:method :head
                          :path-params {:method-name "foo"}})]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :restriction))
    (t/is (th/ex-of-code? ex :method-not-allowed))))

(t/deftest get-allowed-for-get-prefixed-handler
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:get-profile [{} echo-handler]})
          data    (call-body handler
                             {:method :get
                              :path-params {:method-name "get-profile"}})]
      (t/is (= "get-profile" (::rpc/handler-name data))))))

(t/deftest head-allowed-for-get-prefixed-handler
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:get-files [{} echo-handler]})
          data    (call-body handler
                             {:method :head
                              :path-params {:method-name "get-files"}})]
      (t/is (= "get-files" (::rpc/handler-name data))))))

(t/deftest post-always-allowed
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:any-name [{} echo-handler]})
          data    (call-body handler
                             {:method :post
                              :path-params {:method-name "any-name"}})]
      (t/is (= "any-name" (::rpc/handler-name data))))))

(t/deftest put-always-allowed
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:any-name [{} echo-handler]})
          data    (call-body handler
                             {:method :put
                              :path-params {:method-name "any-name"}})]
      (t/is (= "any-name" (::rpc/handler-name data))))))

(t/deftest delete-always-allowed
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:any-name [{} echo-handler]})
          data    (call-body handler
                             {:method :delete
                              :path-params {:method-name "any-name"}})]
      (t/is (= "any-name" (::rpc/handler-name data))))))

(t/deftest patch-always-allowed
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [handler (make-handler {:any-name [{} echo-handler]})
          data    (call-body handler
                             {:method :patch
                              :path-params {:method-name "any-name"}})]
      (t/is (= "any-name" (::rpc/handler-name data))))))

(t/deftest get-to-non-get-handler-restriction-checks-before-handler-execution
  (let [called? (atom false)
        handler (make-handler {:foo [{} (fn [data]
                                          (reset! called? true)
                                          data)]})
        ex      (call-ex handler
                         {:method :get
                          :path-params {:method-name "foo"}})]
    (t/is (some? ex))
    (t/is (false? @called?))))

;; --- RESPONSE: NIL -> 204

(t/deftest nil-response-returns-204
  (let [handler (make-handler {:nil-method [{} (constantly nil)]})
        response (call handler
                       {:path-params {:method-name "nil-method"}})]
    (t/is (= 204 (::yres/status response)))))

(t/deftest nil-response-gets-stream-content-type
  (let [handler (make-handler {:nil-method [{} (constantly nil)]})
        response (call handler
                       {:path-params {:method-name "nil-method"}})]
    (t/is (= "application/octet-stream"
             (get (::yres/headers response) "content-type")))))

;; --- RESPONSE: MAP -> 200

(t/deftest map-response-returns-200
  (let [handler (make-handler {:map-method [{} (constantly {:foo "bar"})]})
        response (call handler
                       {:path-params {:method-name "map-method"}})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= {:foo "bar"} (::yres/body response)))))

;; --- RESPONSE: FALSY NON-NIL VALUES

(t/deftest false-response-returns-200
  (let [handler (make-handler {:false-method [{} (constantly false)]})
        response (call handler
                       {:path-params {:method-name "false-method"}})]
    (t/is (= 200 (::yres/status response)))
    (t/is (false? (::yres/body response)))))

(t/deftest zero-response-returns-200
  (let [handler (make-handler {:zero-method [{} (constantly 0)]})
        response (call handler
                       {:path-params {:method-name "zero-method"}})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= 0 (::yres/body response)))))

(t/deftest empty-map-response-returns-200
  (let [handler (make-handler {:empty-method [{} (constantly {})]})
        response (call handler
                       {:path-params {:method-name "empty-method"}})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= {} (::yres/body response)))))

;; --- RESPONSE: CUSTOM STATUS

(t/deftest custom-status-from-metadata
  (let [result  (with-meta {:ok true} {::http/status 201})
        handler (make-handler {:created [{} (constantly result)]})
        response (call handler
                       {:path-params {:method-name "created"}})]
    (t/is (= 201 (::yres/status response)))))

;; --- RESPONSE: CUSTOM HEADERS

(t/deftest custom-headers-from-metadata
  (let [result  (with-meta {:ok true} {::http/headers {"x-custom" "val"}})
        handler (make-handler {:hdr-method [{} (constantly result)]})
        response (call handler
                       {:path-params {:method-name "hdr-method"}})]
    (t/is (= "val" (get (::yres/headers response) "x-custom")))))

;; --- RESPONSE: CUSTOM STATUS AND HEADERS TOGETHER

(t/deftest custom-status-and-headers-from-metadata
  (let [result  (with-meta {:ok true}
                  {::http/status 202
                   ::http/headers {"x-request-id" "req-123"}})
        handler (make-handler {:both [{} (constantly result)]})
        response (call handler
                       {:path-params {:method-name "both"}})]
    (t/is (= 202 (::yres/status response)))
    (t/is (= "req-123" (get (::yres/headers response) "x-request-id")))))

;; --- RESPONSE: FUNCTION

(t/deftest function-response-called-with-request
  (let [captured (atom nil)
        resp-fn  (fn [req]
                   (reset! captured req)
                   {::yres/status 200
                    ::yres/body "from-fn"})
        handler  (make-handler {:fn-method [{} (constantly resp-fn)]})
        request  (make-request {:path-params {:method-name "fn-method"}})
        response (handler request)]
    (t/is (= request @captured))
    (t/is (= 200 (::yres/status response)))
    (t/is (= "from-fn" (::yres/body response)))))

(t/deftest function-response-returning-nil
  (let [handler (make-handler {:fn-nil [{} (constantly (fn [_] nil))]})
        response (call handler
                       {:path-params {:method-name "fn-nil"}})]
    (t/is (nil? (::yres/body response)))))

(t/deftest function-response-uses-return-value-as-is
  (let [resp-fn (fn [_req]
                  {:result "ok"})
        handler (make-handler {:fn-meta [{} (constantly resp-fn)]})
        response (call handler
                       {:path-params {:method-name "fn-meta"}})]
    ;; When handler returns a fn, handle-response calls (fn request)
    ;; and uses the return value directly as the response.
    (t/is (= {:result "ok"} response))))

;; --- RESPONSE: METADATA WRAPPER

(t/deftest metadata-wrapper-unwrapped-in-response
  (let [wrapped  (rph/wrap {:data "value"})
        handler  (make-handler {:wrap-method [{} (constantly wrapped)]})
        response (call handler
                       {:path-params {:method-name "wrap-method"}})]
    (t/is (= {:data "value"} (::yres/body response)))))

(t/deftest metadata-wrapper-preserves-metadata
  (let [wrapped  (-> (rph/wrap {:data "value"})
                     (vary-meta assoc ::http/status 201))
        handler  (make-handler {:wrap-meta [{} (constantly wrapped)]})
        response (call handler
                       {:path-params {:method-name "wrap-meta"}})]
    (t/is (= 201 (::yres/status response)))))

(t/deftest metadata-wrapper-with-custom-headers
  (let [wrapped  (-> (rph/wrap {:data "value"})
                     (vary-meta assoc ::http/headers {"x-wrap" "yes"}))
        handler  (make-handler {:wrap-hdr [{} (constantly wrapped)]})
        response (call handler
                       {:path-params {:method-name "wrap-hdr"}})]
    (t/is (= "yes" (get (::yres/headers response) "x-wrap")))))

;; --- RESPONSE: STREAM BODY

(t/deftest stream-response-default-content-type
  (let [stream   (yres/stream-body (fn [_response _output] nil))
        handler  (make-handler {:stream-method [{} (constantly stream)]})
        response (call handler
                       {:path-params {:method-name "stream-method"}})]
    (t/is (= "application/octet-stream"
             (get (::yres/headers response) "content-type")))))

(t/deftest stream-response-preserves-existing-content-type
  (let [stream   (with-meta
                   (yres/stream-body (fn [_response _output] nil))
                   {::http/headers {"content-type" "text/plain"}})
        handler  (make-handler {:stream-ct [{} (constantly stream)]})
        response (call handler
                       {:path-params {:method-name "stream-ct"}})]
    (t/is (= "text/plain"
             (get (::yres/headers response) "content-type")))))

;; --- RESPONSE TRANSFORMATION

(t/deftest response-transform-fns-applied
  (let [transform (fn [_req resp]
                    (assoc-in resp [::yres/headers "x-transformed"] "yes"))
        result    (with-meta {:ok true}
                    {::rpc/response-transform-fns [transform]})
        handler   (make-handler {:transform [{} (constantly result)]})
        response  (call handler
                        {:path-params {:method-name "transform"}})]
    (t/is (= "yes" (get (::yres/headers response) "x-transformed")))))

(t/deftest response-transform-fns-applied-in-order
  (let [t1      (fn [_req resp]
                  (assoc-in resp [::yres/headers "x-step"] "1"))
        t2      (fn [_req resp]
                  (update-in resp [::yres/headers "x-step"] str "-2"))
        result  (with-meta {:ok true}
                  {::rpc/response-transform-fns [t1 t2]})
        handler (make-handler {:multi-transform [{} (constantly result)]})
        response (call handler
                       {:path-params {:method-name "multi-transform"}})]
    (t/is (= "1-2" (get (::yres/headers response) "x-step")))))

(t/deftest transform-fn-can-modify-status
  (let [transform (fn [_req _resp]
                    {::yres/status 418
                     ::yres/body "teapot"
                     ::yres/headers {}})
        result    (with-meta {:ok true}
                    {::rpc/response-transform-fns [transform]})
        handler   (make-handler {:teapot [{} (constantly result)]})
        response  (call handler
                        {:path-params {:method-name "teapot"}})]
    (t/is (= 418 (::yres/status response)))))

(t/deftest empty-transform-fns-no-op
  (let [result   (with-meta {:ok true}
                   {::rpc/response-transform-fns []})
        handler  (make-handler {:empty-t [{} (constantly result)]})
        response (call handler
                       {:path-params {:method-name "empty-t"}})]
    (t/is (= 200 (::yres/status response)))))

(t/deftest transform-receives-request-and-response
  (let [captured-req  (atom nil)
        captured-resp (atom nil)
        transform     (fn [req resp]
                        (reset! captured-req req)
                        (reset! captured-resp resp)
                        resp)
        result        (with-meta {:ok true}
                        {::rpc/response-transform-fns [transform]})
        handler       (make-handler {:cap-t [{} (constantly result)]})
        request       (make-request {:path-params {:method-name "cap-t"}})
        response      (handler request)]
    (t/is (= request @captured-req))
    (t/is (some? @captured-resp))
    (t/is (= 200 (::yres/status @captured-resp)))))

;; --- BEFORE-COMPLETE HOOKS

(t/deftest before-complete-hooks-called
  (let [called?  (atom false)
        hook     (fn [] (reset! called? true))
        result   (with-meta {:ok true}
                   {::rpc/before-complete-fns [hook]})
        handler  (make-handler {:hook-method [{} (constantly result)]})]
    (call handler {:path-params {:method-name "hook-method"}})
    (t/is (true? @called?))))

(t/deftest multiple-hooks-all-called
  (let [called-a (atom false)
        called-b (atom false)
        hook-a   (fn [] (reset! called-a true))
        hook-b   (fn [] (reset! called-b true))
        result   (with-meta {:ok true}
                   {::rpc/before-complete-fns [hook-a hook-b]})
        handler  (make-handler {:multi-hook [{} (constantly result)]})]
    (call handler {:path-params {:method-name "multi-hook"}})
    (t/is (true? @called-a))
    (t/is (true? @called-b))))

(t/deftest before-complete-hook-errors-ignored
  (let [hook     (fn [] (throw (Exception. "hook error")))
        result   (with-meta {:ok true}
                   {::rpc/before-complete-fns [hook]})
        handler  (make-handler {:hook-err [{} (constantly result)]})]
    (let [response (call handler
                         {:path-params {:method-name "hook-err"}})]
      (t/is (= 200 (::yres/status response))))))

(t/deftest hook-error-does-not-prevent-other-hooks
  (let [called? (atom false)
        hook-a  (fn [] (throw (Exception. "boom")))
        hook-b  (fn [] (reset! called? true))
        result  (with-meta {:ok true}
                  {::rpc/before-complete-fns [hook-a hook-b]})
        handler (make-handler {:hook-chain [{} (constantly result)]})]
    (call handler {:path-params {:method-name "hook-chain"}})
    (t/is (true? @called?))))

(t/deftest empty-hooks-no-op
  (let [result   (with-meta {:ok true}
                   {::rpc/before-complete-fns []})
        handler  (make-handler {:empty-hooks [{} (constantly result)]})
        response (call handler
                       {:path-params {:method-name "empty-hooks"}})]
    (t/is (= 200 (::yres/status response)))))

;; --- METHODS MAP FORMAT

(t/deftest methods-map-uses-peek-to-extract-handler
  (with-redefs [ct/now    (constantly fixed-time)
                uuid/next (constantly fixed-uuid)
                inet/parse-request (constantly "127.0.0.1")]
    (let [metadata {:some-meta "value"}
          methods  {:peek-test [metadata echo-handler]}
          handler  (rpc/make-rpc-handler methods)
          data     (call-body handler
                              {:path-params {:method-name "peek-test"}})]
      (t/is (= "peek-test" (::rpc/handler-name data))))))

;; --- COND/*ENABLED* BINDING

(t/deftest cond-enabled-bound-to-true-inside-handler
  (let [captured (atom nil)
        handler  (make-handler
                  {:cond-test [{} (fn [data]
                                    (reset! captured cond/*enabled*)
                                    data)]})]
    (call handler {:path-params {:method-name "cond-test"}})
    (t/is (true? @captured))))

(t/deftest cond-enabled-false-outside-handler
  (let [handler (make-handler)]
    (call handler)
    (t/is (false? cond/*enabled*))))

(t/deftest cond-enabled-restored-after-handler-throws
  (let [handler (make-handler
                 {:throwing [{} (fn [_]
                                  (ex/raise :type :test :code :x))]})]
    (try (call handler {:path-params {:method-name "throwing"}})
         (catch Exception _))
    (t/is (false? cond/*enabled*))))

;; --- HANDLER EXCEPTION PROPAGATION

(t/deftest handler-exception-propagates-with-type-and-code
  (let [handler (make-handler
                 {:fail [{} (fn [_]
                              (ex/raise :type :test
                                        :code :boom))]})
        ex      (call-ex handler {:path-params {:method-name "fail"}})]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :test))
    (t/is (th/ex-of-code? ex :boom))))

;; --- DEFAULT HANDLER

(t/deftest default-handler-for-missing-method
  (let [handler (rpc/make-rpc-handler {})
        ex      (try (handler (make-request
                               {:path-params {:method-name "nonexistent"}}))
                     (catch Exception e e))]
    (t/is (some? ex))
    (t/is (th/ex-of-type? ex :not-found))))

;; --- RESPONSE: NIL BODY WITH CUSTOM HEADERS

(t/deftest nil-body-with-custom-headers
  (let [wrapped  (-> (rph/wrap nil)
                     (vary-meta assoc ::http/headers {"x-custom" "val"}))
        handler  (make-handler {:nil-hdr [{} (constantly wrapped)]})
        response (call handler
                       {:path-params {:method-name "nil-hdr"}})]
    (t/is (= 204 (::yres/status response)))
    (t/is (= "val" (get (::yres/headers response) "x-custom")))))
