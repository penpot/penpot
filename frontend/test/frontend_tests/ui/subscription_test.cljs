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

(t/deftest team-plan-is-visible-when-owner-has-professional-or-unlimited-plan
  (doseq [owner-plan ["professional" "unlimited"]]
    (t/is (true? (ds/show-team-plan? {:subscription {:type owner-plan
                                                     :status "active"}})))))

(t/deftest team-plan-is-hidden-when-owner-of-team-outside-organization-has-enterprise-plan
  ;; The team `:subscription` carries the plan of the owner, so the result
  ;; is the same for the owner and for any other member.
  (doseq [owner-plan ["enterprise" "nitrate"]
          status     ["active" "trialing" "past_due"]]
    (t/is (false? (ds/show-team-plan? {:subscription {:type owner-plan
                                                      :status status}})))))

(t/deftest team-plan-is-visible-for-team-in-organization-whatever-the-owner-plan
  (doseq [owner-plan ["professional" "unlimited" "enterprise" "nitrate"]]
    (t/is (true? (ds/show-team-plan? {:subscription {:type owner-plan
                                                     :status "active"}
                                      :organization {:id "org-1"}})))))

(t/deftest team-plan-is-visible-when-owner-enterprise-plan-is-no-longer-paid
  (doseq [status ["unpaid" "canceled"]]
    (t/is (true? (ds/show-team-plan? {:subscription {:type "enterprise"
                                                     :status status}})))))
