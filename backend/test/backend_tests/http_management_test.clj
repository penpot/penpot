;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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


(t/deftest authenticate-method
  (let [profile  (th/create-profile* 1)
        token    (#'sess/gen-token th/*system* {:profile-id (:id profile)})
        request  {:params {:token token}}
        response (#'mgmt/authenticate th/*system* request)]

    (t/is (= 200 (::yres/status response)))
    (t/is (= "authentication" (-> response ::yres/body :iss)))
    (t/is (= (:id profile) (-> response ::yres/body :uid)))))

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




