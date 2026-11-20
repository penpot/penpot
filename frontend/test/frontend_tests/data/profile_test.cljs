;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.profile-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.profile :as dprof]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]))

(t/deftest profile-update-params-omits-nil-values
  (t/is (= {:fullname "Updated Name"}
           (dprof/profile-update-params {:fullname "Updated Name"
                                         :lang nil
                                         :theme nil}))))

(t/deftest profile-update-params-preserves-present-values
  (t/is (= {:fullname "Updated Name"
            :lang "en"
            :theme "dark"}
           (dprof/profile-update-params {:fullname "Updated Name"
                                         :lang "en"
                                         :theme "dark"}))))

(t/deftest update-profile-accepts-nil-optional-values
  (t/is (some? (dprof/update-profile {:id uuid/zero
                                      :fullname "Updated Name"
                                      :theme nil}))))

;; The editors from the usage call reach the profile so the dashboard
;; predicates can count them; a failure must leave the profile untouched.

(defn- authenticated-profile
  [subscription]
  {:id (uuid/next)
   :props {:subscription subscription}})

(t/deftest ^:async fetching-the-profile-copies-the-editors-from-the-usage-call
  (let [editors [{:id (uuid/next) :name "Leia"}]
        profile (authenticated-profile {:quantity 2})
        result  (await
                 (mock/with-mocks*
                   {cf/flags (conj cf/flags :subscriptions)
                    rp/cmd!  (mock/stub
                              (fn [_ _]
                                (->> (rx/of {:editors editors})
                                     (rx/observe-on :async))))}
                   (await (async/->promise (dprof/on-fetch-profile-success profile)))))]
    (t/is (= editors (get-in result [:props :subscription :editors])))))

(t/deftest ^:async a-failing-usage-call-keeps-the-profile
  (let [profile        (authenticated-profile {:quantity 2})
        errors         (atom 0)
        previous-error (.-error js/console)]
    (try
      (set! (.-error js/console) (fn [& _] (swap! errors inc)))
      (let [result (await
                    (mock/with-mocks*
                      {cf/flags (conj cf/flags :subscriptions)
                       rp/cmd!  (mock/stub
                                 (fn [_ _]
                                   (->> (rx/throw (ex-info "boom" {}))
                                        (rx/observe-on :async))))}
                      (await (async/->promise (dprof/on-fetch-profile-success profile)))))]
        (t/is (= profile result))
        (t/is (= 1 @errors)))
      (finally
        (set! (.-error js/console) previous-error)))))

(t/deftest ^:async without-the-subscriptions-flag-the-usage-call-is-skipped
  (let [profile (authenticated-profile {:quantity 2})
        result  (await
                 (mock/with-mocks*
                   {cf/flags (disj cf/flags :subscriptions)
                    rp/cmd!  mock/rpc-cmd-mock}
                   (await (async/->promise (dprof/on-fetch-profile-success profile)))))]
    (t/is (empty? @mock/rpc-calls))
    (t/is (= profile result))))
