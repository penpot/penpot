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

(def ^:private subscription-flags
  (conj cf/flags :subscriptions))

(def ^:private no-subscription-flags
  (disj cf/flags :subscriptions))

(defn- authenticated-profile
  [subscription]
  {:id (uuid/next)
   :props {:subscription subscription}})

(defn- collect
  "Subscribes to the profile stream and returns the delivered value."
  [stream]
  (let [result (atom nil)]
    (->> stream
         (rx/subs! #(reset! result %)))
    @result))

(t/deftest fetching-the-profile-copies-the-editors-from-the-usage-call
  (let [editors [{:id (uuid/next) :name "Leia"}]
        profile (authenticated-profile {:quantity 2})
        result  (with-redefs [cf/flags subscription-flags
                              rp/cmd! (mock/stub (fn [_ _] (rx/of {:editors editors})))]
                  (collect (dprof/on-fetch-profile-success profile)))]
    (t/is (= editors (get-in result [:props :subscription :editors])))))

(t/deftest a-failing-usage-call-keeps-the-profile
  (let [profile        (authenticated-profile {:quantity 2})
        errors         (atom [])
        previous-error (.-error js/console)
        result         (with-redefs [cf/flags subscription-flags
                                     rp/cmd! (mock/stub (fn [_ _] (rx/throw (ex-info "boom" {}))))]
                         (try
                           (set! (.-error js/console) (fn [& args] (swap! errors conj args)))
                           (collect (dprof/on-fetch-profile-success profile))
                           (finally
                             (set! (.-error js/console) previous-error))))]
    (t/is (= profile result))
    (t/is (= 1 (count @errors)))))

(t/deftest without-the-subscriptions-flag-the-usage-call-is-skipped
  (let [profile (authenticated-profile {:quantity 2})
        calls   (atom [])
        result  (with-redefs [cf/flags no-subscription-flags
                              rp/cmd! (mock/stub (fn [cmd _]
                                                   (swap! calls conj cmd)
                                                   (rx/of nil)))]
                  (collect (dprof/on-fetch-profile-success profile)))]
    (t/is (empty? @calls))
    (t/is (= profile result))))
