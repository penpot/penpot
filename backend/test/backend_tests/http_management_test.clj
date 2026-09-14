;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-management-test
  (:require
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.db :as db]
   [app.http.access-token]
   [app.http.management :as mgmt]
   [app.http.session :as sess]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest get-customer-method
  (let [profile  (th/create-profile* 1)
        request  {:params {:id (:id profile)}}
        response (#'mgmt/get-customer th/*system* request)]

    (t/is (= 200 (::yres/status response)))
    (t/is (= (:id profile) (-> response ::yres/body :id)))
    (t/is (= (:fullname profile) (-> response ::yres/body :name)))
    (t/is (= (:email profile) (-> response ::yres/body :email)))
    (t/is (= 1 (-> response ::yres/body :num-editors)))
    (t/is (nil? (-> response ::yres/body :subscription)))))

(t/deftest update-customer-method
  (let [profile  (th/create-profile* 1)

        subs     {:type "unlimited"
                  :description nil
                  :id "foobar"
                  :customer-id (str (:id profile))
                  :status "past_due"
                  :billing-period "week"
                  :quantity 1
                  :created-at (ct/truncate (ct/now) :day)
                  :cancel-at-period-end true
                  :start-date nil
                  :ended-at nil
                  :trial-end nil
                  :trial-start nil
                  :cancel-at nil
                  :canceled-at nil
                  :current-period-end nil
                  :current-period-start nil

                  :cancellation-details
                  {:comment "other"
                   :reason "other"
                   :feedback "other"}}

        request  {:params {:id (:id profile)
                           :subscription subs}}
        response (#'mgmt/update-customer th/*system* request)]

    (t/is (= 201 (::yres/status response)))
    (t/is (nil? (::yres/body response)))

    (let [request  {:params {:id (:id profile)}}
          response (#'mgmt/get-customer th/*system* request)]

      (t/is (= 200 (::yres/status response)))
      (t/is (= (:id profile) (-> response ::yres/body :id)))
      (t/is (= (:fullname profile) (-> response ::yres/body :name)))
      (t/is (= (:email profile) (-> response ::yres/body :email)))
      (t/is (= 1 (-> response ::yres/body :num-editors)))

      (let [subs' (-> response ::yres/body :subscription)]
        (t/is (= subs' subs))))))

;; ---- Shared Key Auth Middleware Tests

(t/deftest shared-key-auth-middleware
  (let [;; The shared-key-auth middleware is private, so we access it via var
        middleware-spec  @#'mgmt/shared-key-auth
        compile-fn       (:compile middleware-spec)
        make-middleware  (compile-fn nil nil)
        handler          (fn [req] {::yres/status 200})
        configured-key   "secret-management-key"]

    ;; Test 1: Request with no x-shared-key header should be rejected (403)
    (let [middleware (make-middleware handler configured-key)
          response   (middleware (th/make-dummy-request {}))]
      (t/is (= 403 (::yres/status response))))

    ;; Test 2: Request with wrong key should be rejected (403)
    (let [middleware (make-middleware handler configured-key)
          response   (middleware (th/make-dummy-request {:headers {"x-shared-key" "wrong-key"}}))]
      (t/is (= 403 (::yres/status response))))

    ;; Test 3: Request with correct key should pass (200)
    (let [middleware (make-middleware handler configured-key)
          response   (middleware (th/make-dummy-request {:headers {"x-shared-key" configured-key}}))]
      (t/is (= 200 (::yres/status response))))

    ;; Test 4: When no key is configured, all requests should be rejected (403)
    (let [middleware (make-middleware handler nil)
          response   (middleware (th/make-dummy-request {:headers {"x-shared-key" "any-key"}}))]
      (t/is (= 403 (::yres/status response))))

    ;; Test 5: Keys differing only in the last character must still be rejected
    (let [middleware (make-middleware handler "secret-key-12345")
          response1  (middleware (th/make-dummy-request {:headers {"x-shared-key" "secret-key-1234X"}}))
          response2  (middleware (th/make-dummy-request {:headers {"x-shared-key" "secret-key-12345"}}))]
      (t/is (= 403 (::yres/status response1)))
      (t/is (= 200 (::yres/status response2))))

    ;; Test 6: Empty string in header must be rejected when configured key is non-empty
    (let [middleware (make-middleware handler "secret-key")
          response   (middleware (th/make-dummy-request {:headers {"x-shared-key" ""}}))]
      (t/is (= 403 (::yres/status response))))

    ;; Test 7: Empty string as configured key (truthy but empty) must reject all requests
    (let [middleware (make-middleware handler "")
          response1  (middleware (th/make-dummy-request {:headers {"x-shared-key" "any-key"}}))
          response2  (middleware (th/make-dummy-request {:headers {"x-shared-key" ""}}))]
      (t/is (= 403 (::yres/status response1)))
      (t/is (= 200 (::yres/status response2))))))
