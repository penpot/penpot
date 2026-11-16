;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.subscription-test
  (:require
   [app.main.data.nitrate :as dnt]
   [app.main.ui.dashboard.subscription :as ds]
   [cljs.test :as t :include-macros true]))

(t/deftest team-plan-is-enterprise-while-team-belongs-to-an-organization
  (doseq [owner-plan ["professional" "unlimited"]]
    (t/is (= "enterprise"
             (ds/get-team-plan-type {:organization {:id "org-1"}
                                     :subscription {:type owner-plan
                                                    :status "active"}})))))

(t/deftest team-plan-returns-to-owner-plan-after-leaving-organization
  (doseq [owner-plan ["professional" "unlimited"]]
    (t/is (= owner-plan
             (ds/get-team-plan-type {:subscription {:type owner-plan
                                                    :status "active"}})))))

(t/deftest owner-enterprise-plan-is-hidden-for-team-outside-organization
  (t/is (false? (ds/show-team-plan? true
                                    {:subscription {:type "enterprise"
                                                    :status "active"}}
                                    {:props {:subscription {:type "enterprise"
                                                            :status "active"}}}))))

(t/deftest team-plan-is-visible-for-other-owner-and-organization-cases
  (doseq [owner-plan ["professional" "unlimited"]]
    (t/is (true? (ds/show-team-plan? true
                                     {:subscription {:type owner-plan
                                                     :status "active"}}
                                     {:props {:subscription {:type owner-plan
                                                             :status "active"}}}))))
  (t/is (true? (ds/show-team-plan? true
                                   {:subscription {:type "enterprise"
                                                   :status "active"}
                                    :organization {:id "org-1"}}
                                   {:props {:subscription {:type "enterprise"
                                                           :status "active"}}})))
  (t/is (true? (ds/show-team-plan? false
                                   {:subscription {:type "enterprise"
                                                   :status "active"}}
                                   {:props {:subscription {:type "enterprise"
                                                           :status "active"}}}))))

(t/deftest owner-enterprise-plan-is-detected-even-when-team-subscription-falls-back
  (t/is (false? (ds/show-team-plan? true
                                    {:subscription {:type "professional"
                                                    :status "active"}}
                                    {:props {:subscription {:type "enterprise"
                                                            :status "trialing"}}}))))

(t/deftest nitrate-owner-plan-hides-team-plan-when-team-is-outside-organization
  (with-redefs [dnt/is-valid-license? (constantly true)]
    (t/is (false? (ds/show-team-plan? true
                                      {:subscription {:type "professional"
                                                      :status "active"}}
                                      {:subscription {:type "nitrate"
                                                      :status "active"}
                                       :props {:subscription {:type "professional"
                                                              :status "active"}}})))))
