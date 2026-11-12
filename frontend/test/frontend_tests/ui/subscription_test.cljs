;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.subscription-test
  (:require
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
                                                    :status "active"}}))))

(t/deftest team-plan-is-visible-for-other-owner-and-organization-cases
  (t/is (true? (ds/show-team-plan? true
                                   {:subscription {:type "professional"
                                                   :status "active"}})))
  (t/is (true? (ds/show-team-plan? true
                                   {:subscription {:type "enterprise"
                                                   :status "active"}
                                    :organization {:id "org-1"}})))
  (t/is (true? (ds/show-team-plan? false
                                   {:subscription {:type "enterprise"
                                                   :status "active"}}))))
